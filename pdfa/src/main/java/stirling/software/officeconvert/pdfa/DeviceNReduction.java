package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class DeviceNReduction {

    private record Space(Tint tint, boolean pattern) {}

    private record Entry(Space stroke, Space fill) {}

    private final Map<COSDictionary, Set<Entry>> entries = new IdentityHashMap<>();

    private final PDDocument doc;

    private final Map<COSArray, Tint> tints;

    private boolean lost;

    private DeviceNReduction(PDDocument doc, Map<COSArray, Tint> tints) {
        this.doc = doc;
        this.tints = tints;
    }

    static int maxColourants(PdfALevel level) {
        return level.part() == 1 ? 8 : 32;
    }

    static void run(PDDocument doc, Census census, ContentGraph graph, PdfALevel level, Report report)
            throws IOException {
        int max = maxColourants(level);
        Map<COSArray, Tint> tints = new IdentityHashMap<>();
        boolean unknown = false;
        for (COSArray a : census.deviceNs) {
            if (ContentGraph.array(a.getObject(1)) instanceof COSArray names && names.size() > max) {
                Tint t = Tint.of(a);
                if (t == null) {
                    unknown = true;
                } else {
                    tints.put(a, t);
                }
            }
        }
        if (unknown) {
            report.warn("A DeviceN colour space has more colourants than " + level.label()
                    + " allows and an alternate space that could not be read");
        }
        if (tints.isEmpty()) {
            return;
        }
        DeviceNReduction r = new DeviceNReduction(doc, tints);
        Map<COSDictionary, Map<COSName, Space>> names = r.names(graph);
        CosWalk.walk(doc, r::replace);
        for (int pass = 0; pass < 3; pass++) {
            for (ContentGraph.Node n : graph.nodes()) {
                r.visit(n, names, false);
            }
        }
        for (ContentGraph.Node n : graph.nodes()) {
            r.visit(n, names, true);
        }
        report.warn("Drew DeviceN colours with more colourants than " + level.label()
                + " allows in their alternate colour space");
        if (r.lost) {
            report.warn("Some DeviceN colours with more colourants than " + level.label()
                    + " allows could not be converted");
        }
    }

    private Tint tint(COSBase b) {
        COSArray a = ContentGraph.array(b);
        return a == null ? null : tints.get(a);
    }

    private Map<COSDictionary, Map<COSName, Space>> names(ContentGraph graph) {
        Map<COSDictionary, Map<COSName, Space>> out = new IdentityHashMap<>();
        for (COSDictionary res : graph.resources()) {
            COSDictionary cs = ContentGraph.dict(res.getDictionaryObject(COSName.COLORSPACE));
            if (cs == null) {
                continue;
            }
            Map<COSName, Space> m = new HashMap<>();
            for (COSName k : cs.keySet()) {
                COSBase v = cs.getDictionaryObject(k);
                Tint t = tint(v);
                if (t != null) {
                    m.put(k, new Space(t, false));
                } else if (v instanceof COSArray a && a.size() == 2 && COSName.PATTERN.equals(a.getObject(0))
                        && tint(a.getObject(1)) != null) {
                    m.put(k, new Space(tint(a.getObject(1)), true));
                }
            }
            out.put(res, m);
        }
        return out;
    }

    private void replace(COSBase b) throws IOException {
        if (b instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
            Tint t = tint(s.getDictionaryObject(COSName.COLORSPACE));
            if (t != null && !TintedData.image(s, t)) {
                lost = true;
            }
            return;
        }
        if (b instanceof COSDictionary d && d.containsKey(COSName.SHADING_TYPE)) {
            Tint t = tint(d.getDictionaryObject(COSName.COLORSPACE));
            if (t != null && !TintedData.shading(doc, d, t)) {
                lost = true;
            }
            return;
        }
        if (b instanceof COSDictionary d) {
            for (Map.Entry<COSName, COSBase> e : new ArrayList<>(d.entrySet())) {
                Tint t = tint(e.getValue());
                if (t != null) {
                    d.setItem(e.getKey(), t.alternate());
                }
            }
        } else if (b instanceof COSArray a && a.size() >= 2) {
            Tint t = tint(a.getObject(1));
            if (t == null) {
                return;
            }
            if (COSName.INDEXED.equals(a.getObject(0)) && a.size() == 4 && a.getObject(2) instanceof COSNumber hival) {
                COSString table = TintedData.lookup(a.getObject(3), Math.max(0, Math.min(255, hival.intValue())), t);
                if (table == null) {
                    lost = true;
                    return;
                }
                a.set(3, table);
                a.set(1, t.alternate());
            } else if (COSName.PATTERN.equals(a.getObject(0))) {
                a.set(1, t.alternate());
            }
        }
    }

    private void visit(ContentGraph.Node n, Map<COSDictionary, Map<COSName, Space>> names, boolean write)
            throws IOException {
        Map<COSName, Space> spaces = n.resources() == null ? null : names.get(n.resources());
        Set<Entry> entered = entries.getOrDefault(n.owner(), Set.of());
        if ((spaces == null || spaces.isEmpty()) && entered.isEmpty()) {
            return;
        }
        Space[] start = new Space[2];
        if (entered.size() == 1) {
            Entry e = entered.iterator().next();
            start[0] = e.stroke();
            start[1] = e.fill();
        } else if (entered.size() > 1 && write) {
            lost = true;
        }
        content(n, spaces == null ? Map.of() : spaces, start, write);
    }

    private void content(ContentGraph.Node n, Map<COSName, Space> spaces, Space[] entry, boolean write)
            throws IOException {
        List<Object> tokens;
        try {
            tokens = ContentTokens.parse(n.streams());
        } catch (IOException e) {
            Decoded.rethrowFatal(e);
            lost |= write;
            return;
        }
        List<Object> out = new ArrayList<>(tokens.size());
        Deque<Space[]> stack = new ArrayDeque<>();
        Space[] current = entry.clone();
        boolean changed = false;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> operands = tokens.subList(start, i);
            start = i + 1;
            String name = op.getName();
            int which = Character.isUpperCase(name.charAt(0)) ? 0 : 1;
            switch (name) {
                case "q" -> stack.push(current.clone());
                case "Q" -> current = stack.isEmpty() ? entry.clone() : stack.pop();
                case "Do" -> {
                    if (operands.size() == 1 && operands.get(0) instanceof COSName xn
                            && TransparencyScan.lookup(n.resources(), COSName.XOBJECT, xn) instanceof COSStream form
                            && COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))
                            && (current[0] != null || current[1] != null)) {
                        entries.computeIfAbsent(form, k -> new HashSet<>()).add(new Entry(current[0], current[1]));
                    }
                }
                case "CS", "cs" -> {
                    current[which] = operands.size() == 1 && operands.get(0) instanceof COSName cs ? spaces.get(cs)
                            : null;
                    out.addAll(operands);
                    out.add(op);
                    if (current[which] != null && !current[which].pattern()) {
                        out.addAll(numbers(current[which].tint().initial()));
                        out.add(Operator.getOperator(which == 0 ? "SCN" : "scn"));
                        changed = true;
                    }
                    continue;
                }
                case "SC", "SCN", "sc", "scn" -> {
                    if (current[which] != null) {
                        List<Object> converted = convert(operands, current[which]);
                        if (converted != null) {
                            out.addAll(converted);
                            out.add(op);
                            changed = true;
                            continue;
                        }
                    }
                }
                case "BI" -> {
                    COSDictionary p = op.getImageParameters();
                    COSBase cs = p == null ? null : p.getDictionaryObject(COSName.CS);
                    if (cs instanceof COSName csName && spaces.containsKey(csName)) {
                        lost |= write;
                    }
                }
                default -> {
                }
            }
            out.addAll(operands);
            out.add(op);
        }
        if (changed && write) {
            ContentTokens.replace(n, out);
        }
    }

    private static List<Object> convert(List<Object> operands, Space space) {
        int count = operands.size() - (space.pattern() ? 1 : 0);
        Tint t = space.tint();
        if (count != t.inputs() || space.pattern() && !(operands.get(count) instanceof COSName)) {
            return null;
        }
        float[] in = new float[count];
        for (int i = 0; i < count; i++) {
            if (!(operands.get(i) instanceof COSNumber v)) {
                return null;
            }
            in[i] = v.floatValue();
        }
        List<Object> out = new ArrayList<>(numbers(t.eval(in)));
        if (space.pattern()) {
            out.add(operands.get(count));
        }
        return out;
    }

    private static List<Object> numbers(float[] v) {
        List<Object> out = new ArrayList<>(v.length);
        for (float f : v) {
            out.add(new COSFloat(Math.round(f * 10000) / 10000f));
        }
        return out;
    }
}
