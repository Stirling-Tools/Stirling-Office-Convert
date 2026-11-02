package stirling.software.officeconvert.topdf.html;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;

final class FlowImages {

    private static final float LABEL_LINE = 1.2f;
    private static final float LABEL_ASCENT = 0.9f;

    private FlowImages() {}

    static void turn(Picture pic, Map<String, String> d, Look look) {
        Transform t = Transform.of(d.get("transform"), look.size, pic.width, pic.height);
        if (t.shiftOnly()) {
            return;
        }
        pic.width *= t.scaleX();
        pic.height *= t.scaleY();
        pic.rotation = Math.round(t.rotation());
    }

    // A picture with a fixed width and no max-width shows at that width, past the page if it is wider
    static void measureImage(HtmlContext ctx, Element e, Map<String, String> d, Look look, Flow.Frame frame) {
        if (d.containsKey("max-width") && !d.get("max-width").strip().equalsIgnoreCase("none")) {
            return;
        }
        Float w = BoxCss.absolute(d.get("width"), look);
        if (w == null && !d.containsKey("width") && e.attr("width").matches("[0-9]{1,5}")) {
            w = Integer.parseInt(e.attr("width")) * 0.75f;
        }
        if (w != null && w > frame.width()) {
            ctx.wants(frame.left() + w);
        }
    }

    // A percentage width is of the nearest box with a fixed width, like a map marker's icon, not of the page
    static float percentBase(HtmlContext ctx, Element e, Look look, float room) {
        float scale = 1;
        Element p = e.parent();
        for (int i = 0; p != null && i < 12; i++, p = p.parent()) {
            String w = ctx.declared(p, look).getOrDefault("width", "auto").strip().toLowerCase(Locale.ROOT);
            if (w.equals("auto")) {
                continue;
            }
            if (w.matches("[0-9]{1,6}([.][0-9]{1,6})?%")) {
                scale *= Math.max(0, Math.min(1, Float.parseFloat(w.substring(0, w.length() - 1)) / 100f));
                continue;
            }
            Float pt = w.contains("%") ? null : Css.length(w, look.size, room);
            return pt == null || pt <= 0 ? room : Math.min(room, pt * scale);
        }
        return room;
    }

    static Inline.TextBox textBox(Svg.Label label, float left, Look look) {
        RunStyle base = look.run();
        String family = label.family() == null ? null : Look.family(label.family());
        float size = Math.max(1, Math.min(label.size(), 400));
        RunStyle style = new RunStyle(family == null ? base.font() : family, size, label.bold(), false, false, false,
                label.rgb(), -1, 0, false);
        float width = label.width() * 1.3f + size;
        float x = label.x() - width * switch (label.anchor()) {
            case "middle" -> 0.5f;
            case "end" -> 1f;
            default -> 0f;
        };
        Paragraph line = new Paragraph();
        line.align = switch (label.anchor()) {
            case "middle" -> Paragraph.Align.CENTER;
            case "end" -> Paragraph.Align.RIGHT;
            default -> Paragraph.Align.LEFT;
        };
        line.lineRule = Paragraph.LineRule.EXACT;
        line.lineHeight = size * LABEL_LINE;
        line.inlines.add(new Inline.Text(label.text(), style, null, -1));
        return new Inline.TextBox(left + x, label.baseline() - size * LABEL_ASCENT, width, line.lineHeight, -1, 0, 0, 0,
                List.of(line), -1, 0, false, true, 0, -1, false, 0, true, false);
    }
}
