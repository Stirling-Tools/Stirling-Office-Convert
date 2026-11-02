package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;

import stirling.software.officeconvert.extract.PdfFiles;

final class SilentGlyphs {

    private static final COSName ACTUAL_TEXT = COSName.getPDFName("ActualText");

    private static final COSName SPAN = COSName.getPDFName("Span");

    private final Map<COSDictionary, Set<Integer>> silent;

    private final Map<COSDictionary, PDFont> fonts = new IdentityHashMap<>();

    private SilentGlyphs(Map<COSDictionary, Set<Integer>> silent) {
        this.silent = silent;
    }

    static void run(ContentGraph graph, Map<COSDictionary, Set<Integer>> silent, PdfALevel level) throws IOException {
        if (silent.isEmpty() || !level.unicode() || level.part() == 1) {
            return;
        }
        SilentGlyphs s = new SilentGlyphs(silent);
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            if (n.kind() == ContentGraph.Kind.GLYPH) {
                continue;
            }
            COSDictionary own = ContentGraph.dict(n.owner().getDictionaryObject(COSName.RESOURCES));
            COSDictionary res = own != null ? own : n.resources();
            COSDictionary fontRes = res == null ? null : ContentGraph.dict(res.getDictionaryObject(COSName.FONT));
            if (fontRes == null || !s.usesSilentFont(fontRes)) {
                continue;
            }
            List<Object> tokens;
            try {
                tokens = ContentTokens.parse(n.streams());
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
                continue;
            }
            List<Object> out = s.rewrite(tokens, fontRes,
                    ContentGraph.dict(res.getDictionaryObject(COSName.PROPERTIES)));
            if (out != null) {
                ContentTokens.replace(n, out);
            }
        }
    }

    private boolean usesSilentFont(COSDictionary fontRes) {
        for (COSBase v : fontRes.getValues()) {
            COSDictionary d = ContentGraph.dict(v);
            if (d != null && silent.containsKey(d)) {
                return true;
            }
        }
        return false;
    }

    private List<Object> rewrite(List<Object> tokens, COSDictionary fontRes, COSDictionary properties) {
        Deque<Boolean> spans = new ArrayDeque<>();
        int actual = 0;
        List<Object> out = new ArrayList<>(tokens.size());
        boolean changed = false;
        COSDictionary current = null;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> operation = tokens.subList(start, i + 1);
            start = i + 1;
            String name = op.getName();
            if ("BMC".equals(name)) {
                spans.push(false);
            } else if ("BDC".equals(name)) {
                boolean has = operation.size() == 3 && actualText(operation.get(1), properties);
                spans.push(has);
                actual += has ? 1 : 0;
            } else if ("EMC".equals(name) && !spans.isEmpty()) {
                actual -= spans.pop() ? 1 : 0;
            }
            if ("Tf".equals(name) && operation.size() == 3 && operation.get(0) instanceof COSName f) {
                current = ContentGraph.dict(fontRes.getDictionaryObject(f));
            }
            Set<Integer> codes = current == null || actual > 0 ? null : silent.get(current);
            PDFont font = codes == null ? null : font(current);
            List<Object> split = font == null ? null : split(operation, name, font, codes);
            if (split != null) {
                out.addAll(split);
                changed = true;
            } else {
                out.addAll(operation);
            }
        }
        if (start < tokens.size()) {
            out.addAll(tokens.subList(start, tokens.size()));
        }
        return changed ? out : null;
    }

    private static boolean actualText(Object operand, COSDictionary properties) {
        COSDictionary d = operand instanceof COSDictionary inline ? inline
                : operand instanceof COSName n && properties != null ? ContentGraph.dict(properties.getDictionaryObject(n))
                : null;
        return d != null && d.containsKey(ACTUAL_TEXT);
    }

    private PDFont font(COSDictionary dict) {
        return fonts.computeIfAbsent(dict, d -> {
            try {
                return PDFontFactory.createFont(d);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    private List<Object> split(List<Object> operation, String name, PDFont font, Set<Integer> codes) {
        List<Object> prefix = new ArrayList<>();
        List<Object> items = new ArrayList<>();
        switch (name) {
            case "Tj" -> {
                if (operation.size() != 2 || !(operation.get(0) instanceof COSString s)) {
                    return null;
                }
                items.add(s);
            }
            case "'" -> {
                if (operation.size() != 2 || !(operation.get(0) instanceof COSString s)) {
                    return null;
                }
                prefix.add(Operator.getOperator("T*"));
                items.add(s);
            }
            case "\"" -> {
                if (operation.size() != 4 || !(operation.get(2) instanceof COSString s)) {
                    return null;
                }
                prefix.add(operation.get(0));
                prefix.add(Operator.getOperator("Tw"));
                prefix.add(operation.get(1));
                prefix.add(Operator.getOperator("Tc"));
                prefix.add(Operator.getOperator("T*"));
                items.add(s);
            }
            case "TJ" -> {
                if (operation.size() != 2 || !(operation.get(0) instanceof COSArray a)) {
                    return null;
                }
                for (int i = 0; i < a.size(); i++) {
                    items.add(a.getObject(i));
                }
            }
            default -> {
                return null;
            }
        }
        List<Object> out = new ArrayList<>(prefix);
        COSArray pending = new COSArray();
        boolean any = false;
        for (Object item : items) {
            if (item instanceof COSNumber num) {
                pending.add(num);
                continue;
            }
            if (!(item instanceof COSString s)) {
                continue;
            }
            List<byte[]> runs = new ArrayList<>();
            List<Boolean> quiet = new ArrayList<>();
            if (!runs(s.getBytes(), font, codes, runs, quiet)) {
                return null;
            }
            for (int r = 0; r < runs.size(); r++) {
                if (!quiet.get(r)) {
                    pending.add(new COSString(runs.get(r)));
                    continue;
                }
                any = true;
                flush(out, pending);
                pending = new COSArray();
                COSDictionary props = new COSDictionary();
                props.setItem(ACTUAL_TEXT, new COSString(""));
                out.add(SPAN);
                out.add(props);
                out.add(Operator.getOperator("BDC"));
                COSArray one = new COSArray();
                one.add(new COSString(runs.get(r)));
                out.add(one);
                out.add(Operator.getOperator("TJ"));
                out.add(Operator.getOperator("EMC"));
            }
        }
        if (!any) {
            return null;
        }
        flush(out, pending);
        return out;
    }

    private static void flush(List<Object> out, COSArray pending) {
        if (pending.size() > 0) {
            out.add(pending);
            out.add(Operator.getOperator("TJ"));
        }
    }

    private static boolean runs(byte[] bytes, PDFont font, Set<Integer> codes, List<byte[]> runs, List<Boolean> quiet) {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        int at = 0;
        int runStart = 0;
        Boolean state = null;
        try {
            while (in.available() > 0) {
                int code = font.readCode(in);
                int next = bytes.length - in.available();
                if (next <= at) {
                    return false;
                }
                boolean q = codes.contains(code);
                if (state != null && q != state) {
                    runs.add(Arrays.copyOfRange(bytes, runStart, at));
                    quiet.add(state);
                    runStart = at;
                }
                state = q;
                at = next;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (state != null) {
            runs.add(Arrays.copyOfRange(bytes, runStart, at));
            quiet.add(state);
        }
        return true;
    }
}
