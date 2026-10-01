package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

final class StreamFixer {

    private static final Set<String> INTENTS = Set.of("RelativeColorimetric", "AbsoluteColorimetric", "Perceptual",
            "Saturation");

    private static final Set<String> BLEND_MODES = Set.of("Normal", "Compatible", "Multiply", "Screen", "Overlay",
            "Darken", "Lighten", "ColorDodge", "ColorBurn", "HardLight", "SoftLight", "Difference", "Exclusion", "Hue",
            "Saturation", "Color", "Luminosity");

    private static final COSName TR2 = COSName.getPDFName("TR2");

    private static final COSName HT = COSName.getPDFName("HT");

    private static final COSName OPI = COSName.getPDFName("OPI");

    private static final COSName ALTERNATES = COSName.getPDFName("Alternates");

    private static final COSName SUBTYPE2 = COSName.getPDFName("Subtype2");

    private static final COSName REF = COSName.getPDFName("Ref");

    private static final COSName FFILTER = COSName.getPDFName("FFilter");

    private static final COSName FDECODE_PARMS = COSName.getPDFName("FDecodeParms");

    private final PDDocument doc;

    private final PdfALevel level;

    private final Report report;

    private StreamFixer(PDDocument doc, PdfALevel level, Report report) {
        this.doc = doc;
        this.level = level;
        this.report = report;
    }

    static void run(PDDocument doc, PdfALevel level, Report report) throws IOException {
        StreamFixer f = new StreamFixer(doc, level, report);
        CosWalk.walk(doc, f::visit);
    }

    private void visit(COSBase b) throws IOException {
        if (!(b instanceof COSDictionary d)) {
            return;
        }
        if (b instanceof COSStream s) {
            stream(s);
        }
        if (COSName.EXT_G_STATE.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.TR)
                || d.containsKey(TR2) || d.containsKey(HT)) {
            extGState(d);
        }
    }

    private void stream(COSStream s) throws IOException {
        s.removeItem(COSName.F);
        s.removeItem(FFILTER);
        s.removeItem(FDECODE_PARMS);
        if (needsReencode(s)) {
            reencode(s);
        }
        COSName sub = s.getCOSName(COSName.SUBTYPE);
        if (COSName.IMAGE.equals(sub)) {
            image(s);
        } else if (COSName.FORM.equals(sub)) {
            s.removeItem(OPI);
            s.removeItem(REF);
            s.removeItem(SUBTYPE2);
            s.removeItem(COSName.getPDFName("PS"));
        } else if (COSName.PS.equals(sub)) {
            empty(s);
            report.warn("Replaced a PostScript XObject, which PDF/A does not allow, with nothing");
        }
        if (COSName.METADATA.equals(s.getCOSName(COSName.TYPE)) && level.part() == 1 && s.getFilters() != null) {
            byte[] data = read(s);
            if (data != null) {
                s.removeItem(COSName.FILTER);
                s.removeItem(COSName.DECODE_PARMS);
                try (OutputStream out = s.createOutputStream()) {
                    out.write(data);
                }
            }
        }
    }

    private boolean needsReencode(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        List<COSName> names = new java.util.ArrayList<>();
        if (f instanceof COSName n) {
            names.add(n);
        } else if (f instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.getObject(i) instanceof COSName n) {
                    names.add(n);
                }
            }
        }
        for (COSName n : names) {
            String v = n.getName();
            if (v.equals("LZWDecode") || v.equals("LZW") || v.equals("Crypt")) {
                return true;
            }
        }
        return false;
    }

    private void reencode(COSStream s) throws IOException {
        byte[] data = read(s);
        if (data == null) {
            report.warn("A stream with a filter PDF/A does not allow could not be decoded");
            return;
        }
        s.removeItem(COSName.FILTER);
        s.removeItem(COSName.DECODE_PARMS);
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(data);
        }
        report.warn("Recompressed LZW streams with Flate, as PDF/A needs");
    }

    private void image(COSStream s) {
        if (s.getBoolean(COSName.INTERPOLATE, false)) {
            s.setBoolean(COSName.INTERPOLATE, false);
        }
        s.removeItem(OPI);
        if (s.containsKey(ALTERNATES)) {
            s.removeItem(ALTERNATES);
        }
        COSBase intent = s.getDictionaryObject(COSName.INTENT);
        if (intent != null && !(intent instanceof COSName n && INTENTS.contains(n.getName()))) {
            s.removeItem(COSName.INTENT);
        }
        if (level.part() > 1 && COSName.JPX_DECODE.equals(s.getDictionaryObject(COSName.FILTER))) {
            s.removeItem(COSName.getPDFName("SMaskInData"));
        }
    }

    private void extGState(COSDictionary gs) {
        gs.removeItem(COSName.TR);
        COSBase tr2 = gs.getDictionaryObject(TR2);
        if (tr2 != null && !COSName.DEFAULT.equals(tr2)) {
            gs.removeItem(TR2);
        }
        gs.removeItem(HT);
        COSBase ri = gs.getDictionaryObject(COSName.RI);
        if (ri != null && !(ri instanceof COSName n && INTENTS.contains(n.getName()))) {
            gs.setItem(COSName.RI, COSName.getPDFName("RelativeColorimetric"));
        }
        COSBase bm = gs.getDictionaryObject(COSName.BM);
        if (bm instanceof COSArray a) {
            COSName first = null;
            for (int i = 0; i < a.size() && first == null; i++) {
                if (a.getObject(i) instanceof COSName n && BLEND_MODES.contains(n.getName())) {
                    first = n;
                }
            }
            gs.setItem(COSName.BM, first == null ? COSName.getPDFName("Normal") : first);
        } else if (bm != null && !(bm instanceof COSName n && BLEND_MODES.contains(n.getName()))) {
            gs.setItem(COSName.BM, COSName.getPDFName("Normal"));
        }
    }

    private void empty(COSStream s) throws IOException {
        s.clear();
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.BBOX, new PDRectangle(0, 0, 0, 0).getCOSArray());
        try (OutputStream out = s.createOutputStream()) {
            out.write(new byte[0]);
        }
    }

    static byte[] read(COSStream s) {
        try (InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
