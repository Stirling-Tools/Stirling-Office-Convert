package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

final class ButtonAppearances {

    private static final COSName OFF = COSName.getPDFName("Off");

    private ButtonAppearances() {}

    static void run(PDDocument doc, COSDictionary widget, Report report) throws IOException {
        COSDictionary ap = ContentGraph.dict(widget.getDictionaryObject(COSName.AP));
        if (ap != null && ap.getDictionaryObject(COSName.N) != null) {
            return;
        }
        COSBase flags = inherited(widget, COSName.FF);
        int bits = flags instanceof org.apache.pdfbox.cos.COSNumber number ? number.intValue() : 0;
        if ((bits & (1 << 16)) != 0) {
            return;
        }
        boolean radio = (bits & (1 << 15)) != 0;
        COSBase value = inherited(widget, COSName.V);
        COSName state = widget.getCOSName(COSName.AS);
        COSName on = state != null && !OFF.equals(state) ? state
                : value instanceof COSName name && !OFF.equals(name) ? name : COSName.getPDFName("Yes");
        boolean checked = radio && state != null ? !OFF.equals(state)
                : value instanceof COSName name ? !OFF.equals(name) : state != null && !OFF.equals(state);
        PDRectangle rect = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget(widget).getRectangle();
        if (rect == null || rect.getWidth() <= 0 || rect.getHeight() <= 0) {
            return;
        }
        COSDictionary states = new COSDictionary();
        states.setItem(OFF, draw(doc, rect, radio, false).getCOSObject());
        states.setItem(on, draw(doc, rect, radio, true).getCOSObject());
        ap = ap == null ? new COSDictionary() : ap;
        ap.setItem(COSName.N, states);
        widget.setItem(COSName.AP, ap);
        widget.setItem(COSName.AS, checked ? on : OFF);
        report.warn("Generated the on and off appearances of a " + (radio ? "radio button" : "checkbox"));
    }

    static COSBase inherited(COSDictionary widget, COSName key) {
        COSDictionary field = widget;
        for (int depth = 0; field != null && depth < 64; depth++) {
            COSBase value = field.getDictionaryObject(key);
            if (value != null) {
                return value;
            }
            field = ContentGraph.dict(field.getDictionaryObject(COSName.PARENT));
        }
        return null;
    }

    private static PDFormXObject draw(PDDocument doc, PDRectangle rect, boolean radio, boolean checked)
            throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        float w = rect.getWidth();
        float h = rect.getHeight();
        form.setBBox(new PDRectangle(w, h));
        form.setResources(new PDResources());
        try (PDFormContentStream content = new PDFormContentStream(form)) {
            content.setStrokingColor(0f);
            content.setNonStrokingColor(0f);
            content.setLineWidth(Math.max(0.5f, Math.min(w, h) / 16));
            if (radio) {
                circle(content, w / 2, h / 2, Math.max(0, Math.min(w, h) / 2 - 1));
                content.stroke();
                if (checked) {
                    circle(content, w / 2, h / 2, Math.min(w, h) / 4);
                    content.fill();
                }
            } else {
                content.addRect(1, 1, Math.max(0, w - 2), Math.max(0, h - 2));
                content.stroke();
                if (checked) {
                    content.moveTo(w * 0.2f, h * 0.5f);
                    content.lineTo(w * 0.4f, h * 0.25f);
                    content.lineTo(w * 0.8f, h * 0.8f);
                    content.stroke();
                }
            }
        }
        return form;
    }

    private static void circle(PDFormContentStream content, float x, float y, float r) throws IOException {
        float c = r * 0.55228475f;
        content.moveTo(x + r, y);
        content.curveTo(x + r, y + c, x + c, y + r, x, y + r);
        content.curveTo(x - c, y + r, x - r, y + c, x - r, y);
        content.curveTo(x - r, y - c, x - c, y - r, x, y - r);
        content.curveTo(x + c, y - r, x + r, y - c, x + r, y);
        content.closePath();
    }
}
