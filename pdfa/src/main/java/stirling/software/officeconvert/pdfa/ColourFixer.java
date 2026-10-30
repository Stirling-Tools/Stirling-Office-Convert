package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class ColourFixer {

    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");

    private static final COSName DEFAULT_RGB = COSName.getPDFName("DefaultRGB");

    private static final COSName DEST_OUTPUT_PROFILE = COSName.getPDFName("DestOutputProfile");

    private static final COSName DEST_OUTPUT_PROFILE_REF = COSName.getPDFName("DestOutputProfileRef");

    private static final COSName GTS_PDFA1 = COSName.getPDFName("GTS_PDFA1");

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private final DeviceColours colours;

    private final List<COSDictionary> shadings = new ArrayList<>();

    private ColourFixer(PDDocument doc, PdfALevel level, Report report, DeviceColours colours) {
        this.doc = doc;
        this.level = level;
        this.report = report;
        this.colours = colours;
    }

    static void run(PDDocument doc, ContentGraph graph, PdfALevel level, Report report, DeviceColours colours,
            CosWalk.Visitor alongside) throws IOException {
        ColourFixer c = new ColourFixer(doc, level, report, colours);
        CosWalk.walk(doc, b -> {
            c.visit(b);
            alongside.visit(b);
        });
        int components = c.outputIntent();
        if (level.part() == 4) {
            c.pageIntents(graph, components);
        }
        c.shadings();
        Map<COSName, COSArray> fallbacks = new LinkedHashMap<>();
        if (components != 4 && colours.cmyk()) {
            fallbacks.put(DEFAULT_CMYK, c.iccBased(IccProfiles.cmyk(), 4));
        }
        if (components != 3 && colours.rgb()) {
            fallbacks.put(DEFAULT_RGB, c.iccBased(IccProfiles.srgb(), 3));
        }
        for (Map.Entry<COSName, COSArray> f : fallbacks.entrySet()) {
            for (COSDictionary res : graph.resources()) {
                COSDictionary cs = ContentGraph.dict(res.getDictionaryObject(COSName.COLORSPACE));
                if (cs == null) {
                    cs = new COSDictionary();
                    res.setItem(COSName.COLORSPACE, cs);
                }
                if (cs.getDictionaryObject(f.getKey()) == null) {
                    cs.setItem(f.getKey(), f.getValue());
                }
            }
            for (ContentGraph.Node n : graph.nodes()) {
                if (n.kind() != ContentGraph.Kind.PAGE && n.kind() != ContentGraph.Kind.APPEARANCE) {
                    continue;
                }
                COSDictionary res = ContentGraph.dict(n.owner().getDictionaryObject(COSName.RESOURCES));
                if (res == null) {
                    res = new COSDictionary();
                    n.owner().setItem(COSName.RESOURCES, res);
                }
                COSDictionary cs = ContentGraph.dict(res.getDictionaryObject(COSName.COLORSPACE));
                if (cs == null) {
                    cs = new COSDictionary();
                    res.setItem(COSName.COLORSPACE, cs);
                }
                if (cs.getDictionaryObject(f.getKey()) == null) {
                    cs.setItem(f.getKey(), f.getValue());
                }
            }
        }
    }

    private void pageIntents(ContentGraph graph, int components) {
        boolean dropped = false;
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.kind() != ContentGraph.Kind.PAGE || !n.owner().containsKey(COSName.OUTPUT_INTENTS)) {
                continue;
            }
            COSArray intents = ContentGraph.array(n.owner().getDictionaryObject(COSName.OUTPUT_INTENTS));
            if (!keepable(intents, components)) {
                n.owner().removeItem(COSName.OUTPUT_INTENTS);
                dropped = true;
            }
        }
        if (dropped) {
            report.warn("Removed page output intents that PDF/A-4 does not accept or that would change how the page's"
                    + " device colours are read");
        }
    }

    private boolean keepable(COSArray intents, int components) {
        if (intents == null || intents.size() == 0) {
            return false;
        }
        COSStream shared = null;
        for (int i = 0; i < intents.size(); i++) {
            COSDictionary oi = ContentGraph.dict(intents.getObject(i));
            if (oi == null) {
                return false;
            }
            oi.removeItem(DEST_OUTPUT_PROFILE_REF);
            if (!(oi.getDictionaryObject(DEST_OUTPUT_PROFILE) instanceof COSStream p)) {
                continue;
            }
            if (shared != null && shared != p) {
                return false;
            }
            shared = p;
            IccProfiles.Header h = IccProfiles.header(read(p));
            if (h == null || !valid(h) || !output(h) || !intentSpace(h)
                    || IccProfiles.components(h.colourSpace()) != components) {
                return false;
            }
        }
        return shared != null;
    }

    private boolean intentSpace(IccProfiles.Header h) {
        return "RGB ".equals(h.colourSpace()) || "CMYK".equals(h.colourSpace())
                || level.part() == 4 && "GRAY".equals(h.colourSpace());
    }

    private void shadings() throws IOException {
        Map<COSName, COSArray> spaces = new HashMap<>();
        for (COSDictionary shading : shadings) {
            if (!(shading.getDictionaryObject(COSName.COLORSPACE) instanceof COSName name)) {
                continue;
            }
            int n = COSName.DEVICECMYK.equals(name) ? 4 : COSName.DEVICERGB.equals(name) ? 3
                    : COSName.DEVICEGRAY.equals(name) ? 1 : 0;
            if (n == 0) {
                continue;
            }
            COSArray space = spaces.get(name);
            if (space == null) {
                byte[] profile = n == 4 ? IccProfiles.cmyk() : n == 3 ? IccProfiles.srgb() : IccProfiles.gray();
                space = iccBased(profile, n);
                spaces.put(name, space);
            }
            shading.setItem(COSName.COLORSPACE, space);
        }
    }

    private COSArray iccBased(byte[] profile, int n) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(profile);
        }
        s.setInt(COSName.N, n);
        COSArray a = new COSArray();
        a.add(COSName.ICCBASED);
        a.add(s);
        return a;
    }

    private int outputIntent() throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSArray intents = ContentGraph.array(cat.getDictionaryObject(COSName.OUTPUT_INTENTS));
        COSDictionary keep = null;
        int components = 0;
        if (intents != null) {
            for (int i = 0; i < intents.size() && keep == null; i++) {
                COSDictionary oi = ContentGraph.dict(intents.getObject(i));
                if (oi == null || !(oi.getDictionaryObject(DEST_OUTPUT_PROFILE) instanceof COSStream p)) {
                    continue;
                }
                IccProfiles.Header h = IccProfiles.header(read(p));
                if (h != null && valid(h) && output(h) && intentSpace(h)) {
                    keep = oi;
                    components = IccProfiles.components(h.colourSpace());
                    p.setInt(COSName.N, components);
                }
            }
        }
        if (keep == null) {
            keep = new COSDictionary();
            keep.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
            COSStream p = doc.getDocument().createCOSStream();
            try (OutputStream out = p.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(IccProfiles.srgb());
            }
            p.setInt(COSName.N, 3);
            keep.setItem(DEST_OUTPUT_PROFILE, p);
            keep.setString(COSName.getPDFName("OutputConditionIdentifier"), IccProfiles.SRGB_ID);
            keep.setString(COSName.INFO, IccProfiles.SRGB_ID);
            keep.setString(COSName.getPDFName("RegistryName"), "http://www.color.org");
            components = 3;
        }
        if (level.part() == 4 && keep.getCOSName(COSName.S) != null && !GTS_PDFA1.equals(keep.getCOSName(COSName.S))) {
            COSDictionary own = new COSDictionary();
            for (Map.Entry<COSName, COSBase> e : keep.entrySet()) {
                own.setItem(e.getKey(), e.getValue());
            }
            keep = own;
        }
        keep.setItem(COSName.S, GTS_PDFA1);
        if (keep.getDictionaryObject(COSName.getPDFName("OutputConditionIdentifier")) == null) {
            keep.setString(COSName.getPDFName("OutputConditionIdentifier"), "Custom");
        }
        COSArray out = new COSArray();
        out.add(keep);
        if (level.part() == 4 && intents != null) {
            COSBase profile = keep.getDictionaryObject(DEST_OUTPUT_PROFILE);
            for (int i = 0; i < intents.size(); i++) {
                COSDictionary oi = ContentGraph.dict(intents.getObject(i));
                if (oi != null && oi != keep && oi.getDictionaryObject(DEST_OUTPUT_PROFILE) == profile) {
                    out.add(oi);
                }
            }
        }
        for (int i = 0; i < out.size(); i++) {
            ((COSDictionary) out.getObject(i)).removeItem(DEST_OUTPUT_PROFILE_REF);
        }
        cat.setItem(COSName.OUTPUT_INTENTS, out);
        return components;
    }

    private boolean valid(IccProfiles.Header h) {
        if (h.major() < 2 || h.major() > 4 || level.part() == 1 && h.major() >= 4) {
            return false;
        }
        String c = h.deviceClass();
        return "mntr".equals(c) || "prtr".equals(c) || "scnr".equals(c) || "spac".equals(c);
    }

    private static boolean output(IccProfiles.Header h) {
        return "mntr".equals(h.deviceClass()) || "prtr".equals(h.deviceClass());
    }

    private void visit(COSBase b) throws IOException {
        if (b instanceof COSDictionary d) {
            if (d.getInt(COSName.SHADING_TYPE, 0) > 0) {
                shadings.add(d);
            }
            for (COSBase v : d.getValues()) {
                colours.value(v);
            }
        } else if (b instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                colours.value(a.get(i));
            }
        }
        profiles(b);
    }

    private void profiles(COSBase b) throws IOException {
        if (!(b instanceof COSArray a) || a.size() < 2 || !COSName.ICCBASED.equals(a.getObject(0))
                || !(a.getObject(1) instanceof COSStream s)) {
            return;
        }
        byte[] data = read(s);
        IccProfiles.Header h = IccProfiles.header(data);
        int n = s.getInt(COSName.N, -1);
        boolean ok = h != null && valid(h) && IccProfiles.components(h.colourSpace()) == n
                && !"Lab ".equals(h.colourSpace()) && !"XYZ ".equals(h.colourSpace());
        if (ok) {
            return;
        }
        if (n < 0 && h != null) {
            n = IccProfiles.components(h.colourSpace());
        }
        byte[] replacement = switch (n) {
            case 1 -> IccProfiles.gray();
            case 3 -> IccProfiles.srgb();
            case 4 -> IccProfiles.cmyk();
            default -> null;
        };
        if (replacement == null) {
            report.warn("An ICC colour profile is not valid for PDF/A and has no replacement");
            return;
        }
        for (COSName k : new COSName[] {COSName.FILTER, COSName.DECODE_PARMS, COSName.ALTERNATE, COSName.RANGE,
                COSName.METADATA}) {
            s.removeItem(k);
        }
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(replacement);
        }
        s.setInt(COSName.N, n);
        report.warn("Replaced an ICC colour profile that PDF/A does not accept");
    }

    static byte[] read(COSStream s) {
        try (InputStream in = s.createInputStream()) {
            return in.readNBytes(16 << 20);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
