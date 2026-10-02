package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.font.PDFont;

import stirling.software.officeconvert.extract.PdfFiles;

final class FontUsage {

    static final int MAX_CODES_PER_FONT = 70_000;

    private final Map<COSDictionary, TreeSet<Integer>> codes = new IdentityHashMap<>();

    private final Map<COSDictionary, CodeReader> readers = new IdentityHashMap<>();

    private final Map<COSDictionary, Integer> lengths = new IdentityHashMap<>();

    private final Map<COSDictionary, PDFont> unchanged = new IdentityHashMap<>();

    private final Map<COSDictionary, List<byte[]>> inheritedText = new IdentityHashMap<>();

    private final Map<COSDictionary, Set<COSDictionary>> entryFonts = new IdentityHashMap<>();

    private final Map<COSDictionary, Set<COSDictionary>> callers = new IdentityHashMap<>();

    FontUsage() {}

    Map<COSDictionary, TreeSet<Integer>> codes() {
        return codes;
    }

    void unchanged(PDFont font) {
        unchanged.put(font.getCOSObject(), font);
    }

    PDFont unchanged(COSDictionary font) {
        return unchanged.get(font);
    }

    int bytesPerCode(COSDictionary font) {
        return lengths.getOrDefault(font, COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE)) ? 2 : 1);
    }

    TreeSet<Integer> codes(COSDictionary font) {
        return codes.getOrDefault(font, new TreeSet<>());
    }

    void scan(List<Object> tokens, COSDictionary resources, COSDictionary owner) {
        Deque<COSDictionary> stack = new ArrayDeque<>();
        COSDictionary font = null;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            String name = op.getName();
            switch (name) {
                case "q" -> {
                    if (stack.size() < 1024) {
                        stack.push(font == null ? NONE : font);
                    }
                }
                case "Q" -> {
                    if (!stack.isEmpty()) {
                        COSDictionary f = stack.pop();
                        font = f == NONE ? null : f;
                    }
                }
                case "Tf" -> {
                    if (i - start >= 2 && tokens.get(i - 2) instanceof COSName fn) {
                        font = font(resources, fn);
                    }
                }
                case "gs" -> {
                    if (i - start >= 1 && tokens.get(i - 1) instanceof COSName gn) {
                        COSDictionary f = gsFont(resources, gn);
                        if (f != null) {
                            font = f;
                        }
                    }
                }
                case "Tj", "'", "\"" -> {
                    if (i - start >= 1 && tokens.get(i - 1) instanceof COSString s) {
                        show(font, owner, s.getBytes());
                    }
                }
                case "TJ" -> {
                    if (i - start >= 1 && tokens.get(i - 1) instanceof COSArray a) {
                        for (int k = 0; k < a.size(); k++) {
                            if (a.getObject(k) instanceof COSString s) {
                                show(font, owner, s.getBytes());
                            }
                        }
                    }
                }
                case "Do" -> {
                    if (i - start >= 1 && tokens.get(i - 1) instanceof COSName xn
                            && TransparencyScan.lookup(resources, COSName.XOBJECT, xn) instanceof COSStream form
                            && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))) {
                        if (font != null) {
                            entryFonts.computeIfAbsent(form, k -> identitySet()).add(font);
                        } else if (owner != null) {
                            callers.computeIfAbsent(form, k -> identitySet()).add(owner);
                        }
                    }
                }
                default -> {
                }
            }
            start = i + 1;
        }
    }

    private static final COSDictionary NONE = new COSDictionary();

    private void show(COSDictionary font, COSDictionary owner, byte[] bytes) {
        if (font != null) {
            record(font, bytes);
        } else if (owner != null) {
            List<byte[]> pending = inheritedText.computeIfAbsent(owner, k -> new ArrayList<>());
            if (pending.size() < MAX_CODES_PER_FONT) {
                pending.add(bytes);
            }
        }
    }

    void resolve() throws InterruptedIOException {
        Map<COSDictionary, Set<COSDictionary>> inherited = inheritedFonts();
        for (Map.Entry<COSDictionary, List<byte[]>> e : inheritedText.entrySet()) {
            PdfFiles.stopIfInterrupted();
            for (COSDictionary font : inherited.getOrDefault(e.getKey(), Set.of())) {
                for (byte[] b : e.getValue()) {
                    record(font, b);
                }
            }
        }
        inheritedText.clear();
    }

    private Map<COSDictionary, Set<COSDictionary>> inheritedFonts() throws InterruptedIOException {
        Map<COSDictionary, Set<COSDictionary>> called = new IdentityHashMap<>();
        for (Map.Entry<COSDictionary, Set<COSDictionary>> e : callers.entrySet()) {
            for (COSDictionary caller : e.getValue()) {
                called.computeIfAbsent(caller, k -> identitySet()).add(e.getKey());
            }
        }
        Map<COSDictionary, Set<COSDictionary>> fonts = new IdentityHashMap<>();
        Deque<COSDictionary> todo = new ArrayDeque<>();
        for (Map.Entry<COSDictionary, Set<COSDictionary>> e : entryFonts.entrySet()) {
            fonts.computeIfAbsent(e.getKey(), k -> identitySet()).addAll(e.getValue());
            todo.push(e.getKey());
        }
        int steps = 0;
        while (!todo.isEmpty()) {
            if ((++steps & 0xFFF) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            COSDictionary f = todo.pop();
            Set<COSDictionary> mine = fonts.get(f);
            for (COSDictionary form : called.getOrDefault(f, Set.of())) {
                if (fonts.computeIfAbsent(form, k -> identitySet()).addAll(mine)) {
                    todo.push(form);
                }
            }
        }
        return fonts;
    }

    private static Set<COSDictionary> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private void record(COSDictionary font, byte[] bytes) {
        TreeSet<Integer> set = codes.computeIfAbsent(font, k -> new TreeSet<>());
        if (set.size() >= MAX_CODES_PER_FONT) {
            return;
        }
        CodeReader r = readers.computeIfAbsent(font, CodeReader::of);
        if (!r.multiByte()) {
            for (byte b : bytes) {
                set.add(b & 0xFF);
            }
            if (bytes.length > 0) {
                lengths.putIfAbsent(font, 1);
            }
            return;
        }
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        while (in.available() > 0) {
            int before = in.available();
            try {
                set.add(r.read(in));
            } catch (IOException e) {
                return;
            }
            lengths.merge(font, before - in.available(), Math::max);
        }
    }

    static COSDictionary font(COSDictionary resources, COSName name) {
        COSDictionary fonts = resources == null ? null : ContentGraph.dict(resources.getDictionaryObject(COSName.FONT));
        return fonts == null ? null : ContentGraph.dict(fonts.getDictionaryObject(name));
    }

    static COSDictionary gsFont(COSDictionary resources, COSName name) {
        COSDictionary states = resources == null ? null : ContentGraph.dict(resources.getDictionaryObject(COSName.EXT_G_STATE));
        COSDictionary gs = states == null ? null : ContentGraph.dict(states.getDictionaryObject(name));
        COSArray f = gs == null ? null : ContentGraph.array(gs.getDictionaryObject(COSName.FONT));
        if (f == null || f.size() < 1) {
            return null;
        }
        COSBase b = f.getObject(0);
        return ContentGraph.dict(b);
    }
}
