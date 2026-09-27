package stirling.software.officeconvert.rtf;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Stacking;
import stirling.software.officeconvert.sink.WrapGap;

final class RtfShapes {

    interface Images {
        Picture.MediaRef shaped(Picture pic) throws IOException;

        byte[] bytes(Picture.MediaRef ref) throws IOException;
    }

    static final class Counter {
        private int next;

        int next() {
            return next++;
        }
    }

    private static final HexFormat HEX = HexFormat.of();

    private final Images images;
    private final RtfBody body;
    private final Counter ids;

    RtfShapes(Images images, RtfBody body, Counter ids) {
        this.images = images;
        this.body = body;
        this.ids = ids;
    }

    void picture(StringBuilder sb, Picture pic) throws IOException {
        boolean quarter = Math.floorMod(pic.rotation, 180) == 90;
        float w = Math.max(0.1f, quarter ? pic.height : pic.width);
        float h = Math.max(0.1f, quarter ? pic.width : pic.height);
        if (pic.wrap == Picture.Wrap.INLINE) {
            int shift = Math.round(pic.baselineShift * 2);
            sb.append(raise(shift)).append("{\\*\\shppict");
            pict(sb, pic, w, h);
            sb.append("}}");
            return;
        }
        int id = ids.next();
        String wrap = switch (pic.wrap) {
            case SQUARE -> "\\shpwr2\\shpwrk0";
            case TOP_BOTTOM -> "\\shpwr1";
            default -> "\\shpwr3";
        };
        float y = Math.max(0, pic.y);
        open(sb, pic.x, y, w, h, pic.fromParagraph, wrap, pic.behind, id, Stacking.picture(pic, id) - Stacking.BACKDROP);
        prop(sb, "shapeType", "75");
        prop(sb, "fBehindDocument", pic.behind ? "1" : "0");
        prop(sb, "posrelh", "1");
        prop(sb, "posrelv", pic.fromParagraph ? "2" : "1");
        long gap = RtfText.emu(WrapGap.clear(pic.wrapGap));
        prop(sb, "dxWrapDistLeft", Long.toString(gap));
        prop(sb, "dxWrapDistRight", Long.toString(gap));
        prop(sb, "fLayoutInCell", "1");
        prop(sb, "fAllowOverlap", "1");
        sb.append("{\\sp{\\sn pib}{\\sv ");
        pict(sb, pic, w, h);
        sb.append("}}");
        if (pic.description != null && !pic.description.isBlank()) {
            sb.append("{\\sp{\\sn wzDescription}{\\sv ");
            RtfText.text(sb, pic.description);
            sb.append("}}");
        }
        sb.append("}}");
    }

    private void pict(StringBuilder sb, Picture pic, float w, float h) throws IOException {
        Picture.MediaRef ref = images.shaped(pic);
        byte[] bytes = images.bytes(ref);
        int tw = Math.max(1, RtfText.twips(w));
        int th = Math.max(1, RtfText.twips(h));
        sb.append("{\\pict").append("image/jpeg".equals(ref.contentType()) ? "\\jpegblip" : "\\pngblip")
                .append("\\picw").append(Math.round(w * 2540 / 72f)).append("\\pich").append(Math.round(h * 2540 / 72f))
                .append("\\picwgoal").append(tw).append("\\pichgoal").append(th).append("\\picscalex100\\picscaley100\n");
        String hex = HEX.formatHex(bytes);
        for (int i = 0; i < hex.length(); i += 128) {
            sb.append(hex, i, Math.min(hex.length(), i + 128)).append('\n');
        }
        sb.append('}');
    }

    void textBox(StringBuilder sb, Inline.TextBox t) throws IOException {
        int id = ids.next();
        String wrap = t.overlay() ? "\\shpwr3" : "\\shpwr2\\shpwrk0";
        open(sb, t.x(), Math.max(0, t.y()), Math.max(1, t.width()), Math.max(1, t.height()), false, wrap, false, id,
                Stacking.box(id) - Stacking.BACKDROP);
        prop(sb, "shapeType", t.rounded() ? "2" : "202");
        prop(sb, "lTxid", Long.toString((id + 1L) * 65536));
        prop(sb, "dxTextLeft", Long.toString(RtfText.emu(Math.max(0, t.insetLeft()))));
        prop(sb, "dyTextTop", "0");
        prop(sb, "dxTextRight", Long.toString(RtfText.emu(Math.max(0, t.insetRight()))));
        prop(sb, "dyTextBottom", "0");
        prop(sb, "fFitShapeToText", "0");
        int direction = Math.floorMod(t.direction(), 360);
        if (direction == 270) {
            prop(sb, "txflTextFlow", "3");
        } else if (direction == 90) {
            prop(sb, "txflTextFlow", "2");
        } else if (direction == 180) {
            prop(sb, "rotation", Long.toString(180L * 65536));
            prop(sb, "fRotateText", "1");
        }
        fill(sb, t.fillRgb());
        line(sb, t.lineRgb(), t.lineWidth());
        long gap = RtfText.emu(WrapGap.clear(t.wrapGap()));
        prop(sb, "dxWrapDistLeft", Long.toString(gap));
        prop(sb, "dxWrapDistRight", Long.toString(gap));
        prop(sb, "posrelh", "1");
        prop(sb, "posrelv", "1");
        prop(sb, "fLayoutInCell", "1");
        prop(sb, "fAllowOverlap", "1");
        sb.append("{\\shptxt ");
        List<Paragraph> paras = t.paragraphs().isEmpty() ? List.of(new Paragraph()) : t.paragraphs();
        for (Paragraph p : paras) {
            body.paragraph(sb, p, RtfBody.End.PAR, false, false);
        }
        sb.append("}}}");
    }

    void shape(StringBuilder sb, Inline.Shape s) {
        int id = ids.next();
        float y = s.fromParagraph() ? s.y() : Math.max(0, s.y());
        float w = Math.max(0.25f, s.width());
        float h = Math.max(0.25f, s.height());
        open(sb, s.x(), y, w, h, s.fromParagraph(), "\\shpwr3", true, id, Stacking.shape(s, id) - Stacking.BACKDROP);
        prop(sb, "shapeType", shapeType(s));
        if (s.rounded() && !s.ellipse()) {
            long adjust = Math.round(Math.min(0.5f, Stacking.CORNER / Math.min(w, h)) * 21600);
            prop(sb, "adjustValue", Long.toString(adjust));
        }
        fill(sb, s.rgb());
        line(sb, s.lineRgb(), s.lineWidth());
        prop(sb, "fBehindDocument", "1");
        prop(sb, "posrelh", "1");
        prop(sb, "posrelv", s.fromParagraph() ? "2" : "1");
        prop(sb, "fLayoutInCell", "1");
        prop(sb, "fAllowOverlap", "1");
        sb.append("}}");
    }

    private static String shapeType(Inline.Shape s) {
        if (s.ellipse()) {
            return "3";
        }
        return s.rounded() ? "2" : "1";
    }

    private static String raise(int shift) {
        if (shift > 0) {
            return "{\\up" + shift;
        }
        return shift < 0 ? "{\\dn" + -shift : "{";
    }

    private static void open(StringBuilder sb, float x, float y, float w, float h, boolean fromParagraph, String wrap,
            boolean behind, int id, int z) {
        int left = RtfText.twips(x);
        int top = RtfText.twips(y);
        sb.append("{\\shp{\\*\\shpinst\\shpleft").append(left).append("\\shptop").append(top).append("\\shpright")
                .append(left + Math.max(1, RtfText.twips(w))).append("\\shpbottom").append(top + Math.max(1, RtfText.twips(h)))
                .append("\\shpfhdr0\\shpbxpage\\shpbxignore").append(fromParagraph ? "\\shpbypara" : "\\shpbypage")
                .append("\\shpbyignore").append(wrap).append("\\shpfblwtxt").append(behind ? 1 : 0).append("\\shpz").append(z)
                .append("\\shplid").append(1025 + id);
    }

    private void fill(StringBuilder sb, int rgb) {
        if (rgb >= 0) {
            prop(sb, "fillColor", Integer.toString(RtfText.bgr(rgb)));
            prop(sb, "fFilled", "1");
        } else {
            prop(sb, "fFilled", "0");
        }
    }

    private void line(StringBuilder sb, int rgb, float width) {
        if (rgb < 0 || width <= 0) {
            prop(sb, "fLine", "0");
            return;
        }
        prop(sb, "lineColor", Integer.toString(RtfText.bgr(rgb)));
        prop(sb, "lineWidth", Long.toString(RtfText.emu(width)));
        prop(sb, "fLine", "1");
    }

    private static void prop(StringBuilder sb, String name, String value) {
        sb.append("{\\sp{\\sn ").append(name).append("}{\\sv ").append(value).append("}}");
    }
}
