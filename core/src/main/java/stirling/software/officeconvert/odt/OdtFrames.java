package stirling.software.officeconvert.odt;

import java.io.IOException;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Stacking;
import stirling.software.officeconvert.sink.WrapGap;

final class OdtFrames {

    private final OdtStyles styles;
    private final OdtMedia media;
    private final OdtBody body;
    private int z;
    private int names;

    OdtFrames(OdtStyles styles, OdtMedia media, OdtBody body) {
        this.styles = styles;
        this.media = media;
        this.body = body;
    }

    void picture(StringBuilder sb, Picture pic) throws IOException {
        boolean quarter = Math.floorMod(pic.rotation, 180) == 90;
        float w = quarter ? pic.height : pic.width;
        float h = quarter ? pic.width : pic.height;
        String name = "Image" + ++names;
        if (pic.wrap == Picture.Wrap.INLINE) {
            boolean shifted = Math.abs(pic.baselineShift) > 0.01f;
            String style = styles.get("graphic", null, "<style:graphic-properties style:vertical-pos=\""
                    + (shifted ? "from-top" : "bottom") + "\" style:vertical-rel=\"baseline\" fo:margin-left=\"0pt\""
                    + " fo:margin-right=\"0pt\" fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\" fo:padding=\"0pt\""
                    + " fo:border=\"none\" style:mirror=\"none\"/>");
            sb.append("<draw:frame draw:style-name=\"").append(style).append("\" draw:name=\"").append(name)
                    .append("\" text:anchor-type=\"as-char\"");
            if (shifted) {
                sb.append(" svg:y=\"").append(OdtXml.pt(-(h + pic.baselineShift))).append('"');
            }
        } else {
            String wrap = switch (pic.wrap) {
                case SQUARE -> "style:wrap=\"parallel\" style:number-wrapped-paragraphs=\"no-limit\" style:wrap-contour=\"false\"";
                case TOP_BOTTOM -> "style:wrap=\"none\"";
                default -> "style:wrap=\"run-through\" style:run-through=\"" + (pic.behind ? "background" : "foreground") + "\"";
            };
            float gap = WrapGap.clear(pic.wrapGap);
            String style = styles.get("graphic", null, "<style:graphic-properties " + wrap + " style:vertical-pos=\"from-top\""
                    + " style:vertical-rel=\"" + (pic.fromParagraph ? "paragraph" : "page") + "\" style:horizontal-pos=\"from-left\""
                    + " style:horizontal-rel=\"page\" style:flow-with-text=\"false\" fo:margin-left=\"" + OdtXml.pt(gap)
                    + "\" fo:margin-right=\"" + OdtXml.pt(gap) + "\" fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\""
                    + " fo:padding=\"0pt\" fo:border=\"none\" style:mirror=\"none\"/>");
            sb.append("<draw:frame draw:style-name=\"").append(style).append("\" draw:name=\"").append(name)
                    .append("\" text:anchor-type=\"paragraph\" svg:x=\"").append(OdtXml.pt(pic.x)).append("\" svg:y=\"")
                    .append(OdtXml.pt(Math.max(0, pic.y))).append('"');
        }
        sb.append(" svg:width=\"").append(OdtXml.pt(Math.max(0.1f, w))).append("\" svg:height=\"")
                .append(OdtXml.pt(Math.max(0.1f, h))).append("\" draw:z-index=\"").append(Stacking.picture(pic, z++) - Stacking.BACKDROP)
                .append("\">");
        media.image(sb, pic);
        if (pic.description != null && !pic.description.isBlank()) {
            sb.append("<svg:desc>").append(String.join("&#10;", pic.description.lines().map(OdtXml::esc).toList())).append("</svg:desc>");
        }
        sb.append("</draw:frame>");
    }

    void textBox(StringBuilder sb, Inline.TextBox t) throws IOException {
        int direction = t.upright() ? 0 : Math.floorMod(t.direction(), 360);
        StringBuilder g = new StringBuilder("<style:graphic-properties ");
        g.append(t.overlay() ? "style:wrap=\"run-through\" style:run-through=\"foreground\""
                : "style:wrap=\"parallel\" style:number-wrapped-paragraphs=\"no-limit\" style:wrap-contour=\"false\"");
        g.append(" style:vertical-pos=\"from-top\" style:vertical-rel=\"page\" style:horizontal-pos=\"from-left\"")
                .append(" style:horizontal-rel=\"page\" style:flow-with-text=\"false\"");
        float gap = WrapGap.clear(t.wrapGap());
        g.append(" fo:margin-left=\"").append(OdtXml.pt(gap)).append("\" fo:margin-right=\"").append(OdtXml.pt(gap))
                .append("\" fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\"");
        Paragraph first = t.paragraphs().isEmpty() ? null : t.paragraphs().getFirst();
        float lead = first == null ? 0 : Math.max(0, first.spaceBefore);
        g.append(" fo:padding-left=\"").append(OdtXml.pt(Math.max(0, t.insetLeft()))).append("\" fo:padding-right=\"")
                .append(OdtXml.pt(Math.max(0, t.insetRight()))).append("\" fo:padding-top=\"").append(OdtXml.pt(lead))
                .append("\" fo:padding-bottom=\"0pt\"");
        fill(g, t.fillRgb());
        boolean line = t.lineRgb() >= 0 && t.lineWidth() > 0;
        g.append(" fo:border=\"").append(line ? OdtXml.pt(t.lineWidth()) + " solid " + OdtXml.colour(t.lineRgb()) : "none")
                .append("\" draw:textarea-vertical-align=\"top\" style:shadow=\"none\"")
                .append(t.upright() ? " style:writing-mode=\"tb-rl\"/>" : "/>");
        String style = styles.get("graphic", "Frame", g.toString());
        boolean quarter = direction == 90 || direction == 270;
        float w = Math.max(1, quarter ? t.height() : t.width());
        float h = Math.max(1, quarter ? t.width() : t.height());
        float top = Math.max(0, t.y());
        sb.append("<draw:frame draw:style-name=\"").append(style).append("\" draw:name=\"Text Box ").append(++names)
                .append("\" text:anchor-type=\"paragraph\"");
        if (direction == 0) {
            sb.append(" svg:x=\"").append(OdtXml.pt(t.x())).append("\" svg:y=\"").append(OdtXml.pt(top)).append('"');
        } else {
            String angle = switch (direction) {
                case 90 -> "1.5707963267949";
                case 270 -> "-1.5707963267949";
                default -> "3.14159265358979";
            };
            sb.append(" draw:transform=\"translate(").append(OdtXml.pt(-w / 2)).append(' ').append(OdtXml.pt(-h / 2))
                    .append(") rotate(").append(angle).append(") translate(").append(OdtXml.pt(t.x() + t.width() / 2))
                    .append(' ').append(OdtXml.pt(top + t.height() / 2)).append(")\"");
        }
        sb.append(" svg:width=\"").append(OdtXml.pt(w)).append("\" svg:height=\"").append(OdtXml.pt(h))
                .append("\" draw:z-index=\"").append(Stacking.box(z++) - Stacking.BACKDROP).append("\"><draw:text-box>");
        float saved = first == null ? 0 : first.spaceBefore;
        if (first != null && lead > 0) {
            first.spaceBefore = 0;
        }
        try {
            paragraphs(sb, t);
        } finally {
            if (first != null) {
                first.spaceBefore = saved;
            }
        }
        sb.append("</draw:text-box></draw:frame>");
    }

    private void paragraphs(StringBuilder sb, Inline.TextBox t) throws IOException {
        if (t.paragraphs().isEmpty()) {
            sb.append("<text:p/>");
        }
        for (Paragraph p : t.paragraphs()) {
            body.paragraph(sb, p, OdtBody.Place.NESTED, false);
        }
    }

    void shape(StringBuilder sb, Inline.Shape s) {
        StringBuilder g = new StringBuilder("<style:graphic-properties style:wrap=\"run-through\" style:run-through=\"background\"");
        g.append(" style:vertical-pos=\"from-top\" style:vertical-rel=\"").append(s.fromParagraph() ? "paragraph" : "page")
                .append("\" style:horizontal-pos=\"from-left\" style:horizontal-rel=\"page\" style:flow-with-text=\"false\"");
        fill(g, s.rgb());
        stroke(g, s.lineRgb(), s.lineWidth());
        g.append(" draw:shadow=\"hidden\"/>");
        String style = styles.get("graphic", null, g.toString());
        float w = Math.max(0.25f, s.width());
        float h = Math.max(0.25f, s.height());
        String element = s.ellipse() ? "draw:ellipse" : "draw:rect";
        sb.append('<').append(element).append(" draw:style-name=\"").append(style).append("\" draw:name=\"Shape ").append(++names)
                .append("\" text:anchor-type=\"paragraph\" svg:x=\"").append(OdtXml.pt(s.x())).append("\" svg:y=\"")
                .append(OdtXml.pt(s.fromParagraph() ? s.y() : Math.max(0, s.y()))).append("\" svg:width=\"").append(OdtXml.pt(w))
                .append("\" svg:height=\"")
                .append(OdtXml.pt(h)).append('"');
        if (s.rounded() && !s.ellipse()) {
            sb.append(" draw:corner-radius=\"").append(OdtXml.pt(Math.min(Stacking.CORNER, Math.min(w, h) / 2f))).append('"');
        }
        sb.append(" draw:z-index=\"").append(Stacking.shape(s, z++) - Stacking.BACKDROP).append("\"/>");
    }

    private static void fill(StringBuilder g, int rgb) {
        if (rgb >= 0) {
            String c = OdtXml.colour(rgb);
            g.append(" draw:fill=\"solid\" draw:fill-color=\"").append(c).append("\" fo:background-color=\"").append(c).append('"');
        } else {
            g.append(" draw:fill=\"none\" fo:background-color=\"transparent\" style:background-transparency=\"100%\"");
        }
    }

    private static void stroke(StringBuilder g, int rgb, float width) {
        if (rgb < 0 || width <= 0) {
            g.append(" draw:stroke=\"none\"");
        } else {
            g.append(" draw:stroke=\"solid\" svg:stroke-width=\"").append(OdtXml.pt(width)).append("\" svg:stroke-color=\"")
                    .append(OdtXml.colour(rgb)).append('"');
        }
    }
}
