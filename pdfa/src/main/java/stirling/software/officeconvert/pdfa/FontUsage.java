package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;


final class FontUsage {

    static final int MAX_CODES_PER_FONT = 70_000;

    private final Map<COSDictionary, TreeSet<Integer>> codes = new IdentityHashMap<>();

    private final Map<COSDictionary, CodeReader> readers = new IdentityHashMap<>();

    private final Map<COSDictionary, Integer> lengths = new IdentityHashMap<>();

    FontUsage() {}

    Map<COSDictionary, TreeSet<Integer>> codes() {
        return codes;
    }

    int bytesPerCode(COSDictionary font) {
        return lengths.getOrDefault(font, COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE)) ? 2 : 1);
    }

    TreeSet<Integer> codes(COSDictionary font) {
        return codes.getOrDefault(font, new TreeSet<>());
    }

    void scan(List<Object> tokens, COSDictionary resources) {
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
                    if (font != null && i - start >= 1 && tokens.get(i - 1) instanceof COSString s) {
                        record(font, s.getBytes());
                    }
                }
                case "TJ" -> {
                    if (font != null && i - start >= 1 && tokens.get(i - 1) instanceof COSArray a) {
                        for (int k = 0; k < a.size(); k++) {
                            if (a.getObject(k) instanceof COSString s) {
                                record(font, s.getBytes());
                            }
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

    private void record(COSDictionary font, byte[] bytes) {
        TreeSet<Integer> set = codes.computeIfAbsent(font, k -> new TreeSet<>());
        if (set.size() >= MAX_CODES_PER_FONT) {
            return;
        }
        CodeReader r = readers.computeIfAbsent(font, CodeReader::of);
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
