package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;

final class Annotations {

    private static final Set<String> ALLOWED_A1 = Set.of("Text", "Link", "FreeText", "Line", "Square", "Circle",
            "Highlight", "Underline", "Squiggly", "StrikeOut", "Stamp", "Ink", "Popup", "Widget", "PrinterMark",
            "TrapNet");

    private static final Set<String> ALLOWED = Set.of("Text", "Link", "FreeText", "Line", "Square", "Circle",
            "Polygon", "PolyLine", "Highlight", "Underline", "Squiggly", "StrikeOut", "Stamp", "Caret", "Ink", "Popup",
            "FileAttachment", "Widget", "PrinterMark", "TrapNet", "Watermark", "Redact");

    private static final int INVISIBLE = 1;

    private static final int HIDDEN = 2;

    private static final int PRINT = 4;

    private static final int NO_ZOOM = 8;

    private static final int NO_ROTATE = 16;

    private static final int NO_VIEW = 32;

    private static final int TOGGLE_NO_VIEW = 256;

    private Annotations() {}

    static void run(PDDocument doc, PDPage page, PdfALevel level, Report report) throws IOException {
        COSArray annots = ContentGraph.array(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
        if (annots == null) {
            return;
        }
        COSArray kept = new COSArray();
        for (int i = 0; i < annots.size(); i++) {
            COSDictionary a = ContentGraph.dict(annots.getObject(i));
            if (a == null) {
                continue;
            }
            COSName sub = a.getCOSName(COSName.SUBTYPE);
            String type = sub == null ? "" : sub.getName();
            if (!(level.part() == 1 ? ALLOWED_A1 : ALLOWED).contains(type)) {
                report.warn("Removed " + (type.isEmpty() ? "an annotation without a type" : "a " + type
                        + " annotation") + ", which " + level.label() + " does not allow");
                continue;
            }
            int flags = a.getInt(COSName.F, 0);
            if ((flags & (HIDDEN | NO_VIEW)) != 0 && !"Popup".equals(type)) {
                report.warn("Removed a hidden " + type + " annotation, which PDF/A does not allow");
                continue;
            }
            flags = (flags | PRINT) & ~(INVISIBLE | HIDDEN | NO_VIEW | TOGGLE_NO_VIEW);
            if ("Text".equals(type)) {
                flags |= NO_ZOOM | NO_ROTATE;
            }
            a.setInt(COSName.F, flags);
            if (a.containsKey(COSName.AA)) {
                a.removeItem(COSName.AA);
                report.warn("Removed annotation actions, which PDF/A does not allow");
            }
            if ("Widget".equals(type) && a.containsKey(COSName.A)) {
                a.removeItem(COSName.A);
                report.warn("Removed form field actions, which PDF/A does not allow");
            }
            Actions.filterKey(a, COSName.A, level, report);
            if (a.getDictionaryObject(COSName.CA) instanceof COSBase ca
                    && !(ca instanceof COSInteger || ca instanceof COSFloat)) {
                a.removeItem(COSName.CA);
            }
            appearance(doc, a, type, level, report);
            kept.add(annots.get(i));
        }
        if (kept.size() != annots.size()) {
            page.getCOSObject().setItem(COSName.ANNOTS, kept);
        }
    }

    private static void appearance(PDDocument doc, COSDictionary a, String type, PdfALevel level, Report report)
            throws IOException {
        COSDictionary ap = ContentGraph.dict(a.getDictionaryObject(COSName.AP));
        boolean needs = !"Popup".equals(type) && !"Link".equals(type) && !zeroSize(a);
        if (ap != null) {
            ap.removeItem(COSName.R);
            ap.removeItem(COSName.D);
        }
        if ("Link".equals(type) || "Popup".equals(type)) {
            if (level.part() == 1 && ap != null && ap.getDictionaryObject(COSName.N) == null) {
                a.removeItem(COSName.AP);
            }
            return;
        }
        if (!needs) {
            return;
        }
        if (ap == null || ap.getDictionaryObject(COSName.N) == null) {
            try {
                PDAnnotation annot = PDAnnotation.createAnnotation(a);
                annot.constructAppearances(doc);
            } catch (IOException | RuntimeException e) {
                report.warn("Could not draw a " + type + " annotation's appearance: " + e.getMessage());
            }
            ap = ContentGraph.dict(a.getDictionaryObject(COSName.AP));
            if (ap != null) {
                ap.removeItem(COSName.R);
                ap.removeItem(COSName.D);
            }
        }
        if (ap == null || ap.getDictionaryObject(COSName.N) == null) {
            ap = new COSDictionary();
            ap.setItem(COSName.N, empty(doc, a));
            a.setItem(COSName.AP, ap);
            report.warn("Gave a " + type + " annotation an empty appearance, as it had none");
        }
        COSBase n = ap.getDictionaryObject(COSName.N);
        boolean button = "Widget".equals(type) && COSName.BTN.equals(fieldType(a));
        if (button && n instanceof COSStream s) {
            COSDictionary states = new COSDictionary();
            COSName state = a.getCOSName(COSName.AS);
            states.setItem(state == null ? COSName.getPDFName("Off") : state, s);
            ap.setItem(COSName.N, states);
            if (state == null) {
                a.setItem(COSName.AS, COSName.getPDFName("Off"));
            }
        } else if (!button && n instanceof COSDictionary states && !(n instanceof COSStream)) {
            COSName state = a.getCOSName(COSName.AS);
            COSBase chosen = state == null ? null : states.getDictionaryObject(state);
            if (!(chosen instanceof COSStream)) {
                for (COSName k : states.keySet()) {
                    if (states.getDictionaryObject(k) instanceof COSStream s) {
                        chosen = s;
                        break;
                    }
                }
            }
            ap.setItem(COSName.N, chosen instanceof COSStream ? chosen : empty(doc, a));
        }
    }

    private static COSName fieldType(COSDictionary widget) {
        COSDictionary f = widget;
        for (int i = 0; i < 32 && f != null; i++) {
            COSName ft = f.getCOSName(COSName.FT);
            if (ft != null) {
                return ft;
            }
            f = ContentGraph.dict(f.getDictionaryObject(COSName.PARENT));
        }
        return null;
    }

    private static boolean zeroSize(COSDictionary a) {
        COSArray r = ContentGraph.array(a.getDictionaryObject(COSName.RECT));
        if (r == null || r.size() < 4) {
            return true;
        }
        PDRectangle rect = new PDRectangle(r);
        return rect.getWidth() == 0 && rect.getHeight() == 0;
    }

    private static COSStream empty(PDDocument doc, COSDictionary a) throws IOException {
        COSArray r = ContentGraph.array(a.getDictionaryObject(COSName.RECT));
        PDRectangle rect = r == null || r.size() < 4 ? new PDRectangle(0, 0) : new PDRectangle(r);
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.BBOX, new PDRectangle(0, 0, Math.abs(rect.getWidth()), Math.abs(rect.getHeight())).getCOSArray());
        try (OutputStream out = s.createOutputStream()) {
            out.write(new byte[0]);
        }
        return s;
    }
}
