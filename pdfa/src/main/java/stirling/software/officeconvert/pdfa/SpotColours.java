package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class SpotColours {

    private static final COSName COLORANTS = COSName.getPDFName("Colorants");

    private static final COSName PROCESS = COSName.getPDFName("Process");

    private static final COSName COMPONENTS = COSName.getPDFName("Components");

    private static final Set<String> IGNORED = Set.of("Cyan", "Magenta", "Yellow", "Black", "None");

    private static final int SAMPLES = 256;

    private final PDDocument doc;

    private final List<COSArray> deviceNs = new ArrayList<>();

    private final Set<COSArray> inColorants = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<String, List<COSArray>> separations = new LinkedHashMap<>();

    private final Set<String> used = new HashSet<>();

    private SpotColours(PDDocument doc) {
        this.doc = doc;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        if (level.part() == 1) {
            return;
        }
        SpotColours s = new SpotColours(doc);
        CosWalk.walk(doc, s::collect);
        if (s.deviceNs.isEmpty() && s.separations.isEmpty()) {
            return;
        }
        Map<String, COSArray> canonical = s.unify(report);
        s.complete(canonical, report);
    }

    private void collect(COSBase b) {
        if (!(b instanceof COSArray a) || a.size() < 4 || !(a.getObject(0) instanceof COSName kind)) {
            return;
        }
        if (COSName.SEPARATION.equals(kind) && a.getObject(1) instanceof COSName name) {
            separations.computeIfAbsent(name.getName(), k -> new ArrayList<>()).add(a);
            used.add(name.getName());
        } else if (COSName.DEVICEN.equals(kind) && ContentGraph.array(a.getObject(1)) != null) {
            deviceNs.add(a);
            COSDictionary colorants = colorants(a, false);
            if (colorants != null) {
                for (COSName k : colorants.keySet()) {
                    COSArray sep = ContentGraph.array(colorants.getDictionaryObject(k));
                    if (sep != null) {
                        inColorants.add(sep);
                    }
                }
            }
        }
    }

    private Map<String, COSArray> unify(Report report) {
        Map<String, COSArray> canonical = new LinkedHashMap<>();
        boolean renamed = false;
        boolean shared = false;
        for (Map.Entry<String, List<COSArray>> e : separations.entrySet()) {
            if ("All".equals(e.getKey()) || "None".equals(e.getKey())) {
                continue;
            }
            List<COSArray> list = e.getValue();
            COSArray first = list.stream().filter(a -> !inColorants.contains(a)).findFirst().orElse(list.get(0));
            canonical.put(e.getKey(), first);
            for (COSArray a : list) {
                if (a == first) {
                    continue;
                }
                boolean same = CosEquality.same(a.get(2), first.get(2)) && CosEquality.same(a.get(3), first.get(3));
                if (same || inColorants.contains(a)) {
                    shared |= !same;
                    a.set(2, first.get(2));
                    a.set(3, first.get(3));
                } else {
                    a.set(1, COSName.getPDFName(fresh(e.getKey())));
                    renamed = true;
                }
            }
        }
        if (renamed) {
            report.warn("Renamed spot colours that share a name but not a definition, which PDF/A does not allow");
        }
        if (shared) {
            report.warn("Gave DeviceN colorant entries the definition of the spot colour of the same name");
        }
        return canonical;
    }

    private String fresh(String name) {
        for (int i = 2; ; i++) {
            String n = Limits.name(COSName.getPDFName(name + " " + i)).getName();
            if (used.add(n)) {
                return n;
            }
        }
    }

    private void complete(Map<String, COSArray> canonical, Report report) throws IOException {
        boolean added = false;
        for (COSArray a : deviceNs) {
            COSArray names = ContentGraph.array(a.getObject(1));
            Set<String> have = new HashSet<>();
            COSDictionary existing = colorants(a, false);
            if (existing != null) {
                for (COSName k : existing.keySet()) {
                    have.add(k.getName());
                }
            }
            COSDictionary process = ContentGraph.dict(attributes(a, false) == null ? null
                    : attributes(a, false).getDictionaryObject(PROCESS));
            COSArray components = process == null ? null : ContentGraph.array(process.getDictionaryObject(COMPONENTS));
            if (components != null) {
                for (int i = 0; i < components.size(); i++) {
                    if (components.getObject(i) instanceof COSName n) {
                        have.add(n.getName());
                    }
                }
            }
            for (int i = 0; i < names.size(); i++) {
                if (!(names.getObject(i) instanceof COSName n) || IGNORED.contains(n.getName())
                        || have.contains(n.getName())) {
                    continue;
                }
                COSArray sep = canonical.get(n.getName());
                if (sep == null) {
                    Tint t = Tint.of(a);
                    if (t == null) {
                        continue;
                    }
                    sep = separation(n, t, i, names.size());
                    canonical.put(n.getName(), sep);
                }
                colorants(a, true).setItem(n, sep);
                have.add(n.getName());
                added = true;
            }
        }
        if (added) {
            report.warn("Described the spot colours of DeviceN colour spaces in their Colorants, as PDF/A needs");
        }
    }

    private COSArray separation(COSName name, Tint t, int index, int count) throws IOException {
        int m = t.outputs();
        float[] r = t.ranges();
        byte[] samples = new byte[SAMPLES * m * 2];
        float[] in = new float[count];
        for (int k = 0; k < SAMPLES; k++) {
            in[index] = k / (float) (SAMPLES - 1);
            float[] v = t.eval(in);
            for (int c = 0; c < m; c++) {
                int q = Math.round((v[c] - r[2 * c]) / (r[2 * c + 1] - r[2 * c]) * 65535);
                samples[(k * m + c) * 2] = (byte) (q >> 8);
                samples[(k * m + c) * 2 + 1] = (byte) q;
            }
        }
        COSStream f = doc.getDocument().createCOSStream();
        f.setInt(COSName.FUNCTION_TYPE, 0);
        COSArray domain = new COSArray();
        domain.add(COSInteger.ZERO);
        domain.add(COSInteger.ONE);
        f.setItem(COSName.DOMAIN, domain);
        COSArray range = new COSArray();
        for (float v : r) {
            range.add(new COSFloat(v));
        }
        f.setItem(COSName.RANGE, range);
        COSArray size = new COSArray();
        size.add(COSInteger.get(SAMPLES));
        f.setItem(COSName.SIZE, size);
        f.setInt(COSName.BITS_PER_SAMPLE, 16);
        try (OutputStream o = f.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(samples);
        }
        COSArray sep = new COSArray();
        sep.add(COSName.SEPARATION);
        sep.add(name);
        sep.add(t.alternate());
        sep.add(f);
        return sep;
    }

    private static COSDictionary attributes(COSArray deviceN, boolean create) {
        COSDictionary attrs = deviceN.size() > 4 ? ContentGraph.dict(deviceN.getObject(4)) : null;
        if (attrs == null && create) {
            attrs = new COSDictionary();
            while (deviceN.size() > 4) {
                deviceN.remove(deviceN.size() - 1);
            }
            deviceN.add(attrs);
        }
        return attrs;
    }

    private static COSDictionary colorants(COSArray deviceN, boolean create) {
        COSDictionary attrs = attributes(deviceN, create);
        if (attrs == null) {
            return null;
        }
        COSDictionary c = ContentGraph.dict(attrs.getDictionaryObject(COLORANTS));
        if (c == null && create) {
            c = new COSDictionary();
            attrs.setItem(COLORANTS, c);
        }
        return c;
    }
}
