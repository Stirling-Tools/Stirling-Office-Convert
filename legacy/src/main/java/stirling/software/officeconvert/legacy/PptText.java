package stirling.software.officeconvert.legacy;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.hslf.record.EscherTextboxWrapper;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.hslf.record.TextRulerAtom;
import org.apache.poi.hslf.usermodel.HSLFHyperlink;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.TabStop.TabStopType;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.VerticalAlignment;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.slides.Bullet;
import stirling.software.officeconvert.slides.LineBoxes;
import stirling.software.officeconvert.slides.TextPara;
import stirling.software.officeconvert.slides.TextShape;

final class PptText {

    private final IntFunction<HSLFSlide> slideOf;
    private final Scripts.Profile scripts;

    PptText(IntFunction<HSLFSlide> slideOf, Scripts.Profile scripts) {
        this.slideOf = slideOf;
        this.scripts = scripts;
    }

    void shape(HSLFSlide s, TextShape t) {
        HSLFTextBox box = s.createTextBox();
        box.setAnchor(PptWriter.anchor(t.frame()));
        if (t.frame().rotation() != 0) {
            box.setRotation(PptWriter.rotation(t.frame()));
        }
        box.setWordWrap(t.wrap() || mixedAlignment(t));
        box.setHorizontalCentered(false);
        box.setVerticalAlignment(VerticalAlignment.TOP);
        box.setInsets(new Insets2D(t.insetTop(), t.insetLeft(), 0, t.insetRight()));
        box.setFillColor(t.fillRgb() >= 0 ? new Color(t.fillRgb()) : null);
        if (t.lineRgb() >= 0 && t.lineWidth() > 0) {
            box.setLineColor(new Color(t.lineRgb()));
            box.setLineWidth(t.lineWidth());
        } else {
            box.setLineColor(null);
        }
        boolean first = true;
        for (TextPara p : t.paras()) {
            List<Inline> inlines = p.content().inlines;
            boolean numbered = p.bullet() != null && p.bullet().numbered();
            if (numbered) {
                inlines = new ArrayList<>(inlines);
                inlines.addFirst(new Inline.Tab(p.bullet().style()));
                inlines.addFirst(new Inline.Text(p.bullet().marker().text(), p.bullet().style(), null, -1));
            }
            HSLFTextParagraph para = fill(box, inlines, first);
            first = false;
            float left = p.marginLeft();
            para.setLeftMargin((double) left);
            para.setIndent((double) Math.max(0, left + p.indent()));
            para.setTextAlign(align(p.align()));
            spacing(para, p.lineHeight(), p.size(), p.spaceBefore());
            bullet(para, numbered ? null : p.bullet(), p.size());
            if (numbered) {
                para.addTabStops(left, TabStopType.LEFT);
            }
            tabs(para, p.content().tabs);
        }
        plainRuler(box);
    }

    private static boolean mixedAlignment(TextShape t) {
        return t.paras().stream().map(p -> p.align() == Align.JUSTIFY ? Align.LEFT : p.align()).distinct().count() > 1;
    }

    void cell(HSLFTextShape cell, List<Paragraph> paras) {
        boolean first = true;
        for (Paragraph p : paras) {
            HSLFTextParagraph para = fill(cell, p.inlines, first);
            para.setLeftMargin((double) Math.max(0, p.indentLeft));
            para.setIndent((double) Math.max(0, p.indentLeft + p.indentFirst));
            para.setTextAlign(align(p.align));
            float size = 0;
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Text text) {
                    size = Math.max(size, text.style().size());
                }
            }
            spacing(para, p.lineRule == Paragraph.LineRule.EXACT ? p.lineHeight : 0, size, first ? 0 : p.spaceBefore);
            tabs(para, p.tabs);
            first = false;
        }
        plainRuler(cell);
    }

    private static void plainRuler(HSLFTextShape box) {
        EscherTextboxWrapper text = box.getTextParagraphs().getFirst().getTextboxWrapper();
        if (text != null && text.findFirstOfType(RecordTypes.TextRulerAtom.typeID) instanceof TextRulerAtom ruler) {
            Arrays.fill(ruler.getTextOffsets(), null);
            Arrays.fill(ruler.getBulletOffsets(), null);
        }
    }

    private HSLFTextParagraph fill(HSLFTextShape box, List<Inline> inlines, boolean first) {
        HSLFTextRun last = null;
        boolean newParagraph = !first;
        for (Inline in : inlines) {
            String text;
            RunStyle style;
            String link = null;
            int anchor = -1;
            switch (in) {
                case Inline.Text t -> {
                    text = t.text();
                    style = t.style();
                    link = t.link();
                    anchor = t.anchorPage();
                }
                case Inline.Tab t -> {
                    text = "\t";
                    style = t.style();
                }
                case Inline.Break b -> {
                    text = "\u000B";
                    style = null;
                }
                default -> {
                    continue;
                }
            }
            last = box.appendText(text, newParagraph);
            newParagraph = false;
            if (style != null) {
                style(last, style, text);
            }
            link(last, link, anchor);
        }
        if (last == null) {
            last = box.appendText("", newParagraph);
        }
        return last.getTextParagraph();
    }

    private void style(HSLFTextRun r, RunStyle s, String text) {
        r.getCharacterStyle().removeByName("char_flags");
        r.getCharacterStyle().removeByName("superscript");
        if (s.font() != null) {
            Scripts.Fonts slots = Scripts.fonts(s.font(), text, scripts);
            r.setFontFamily(slots.latin());
            if (!slots.eastAsian().equals(slots.latin())) {
                r.setFontFamily(slots.eastAsian(), FontGroup.EAST_ASIAN);
            }
            if (!slots.complex().equals(slots.latin())) {
                r.setFontFamily(slots.complex(), FontGroup.COMPLEX_SCRIPT);
            }
        }
        r.setFontSize((double) Math.max(1f, s.size()));
        if (s.bold()) {
            r.setBold(true);
        }
        if (s.italic()) {
            r.setItalic(true);
        }
        if (s.underline()) {
            r.setUnderlined(true);
        }
        if (s.strike()) {
            r.setStrikethrough(true);
        }
        r.setFontColor(new Color(Math.max(0, s.rgb())));
        if (s.vertAlign() != 0) {
            r.setSuperscript(s.vertAlign() > 0 ? 30 : -25);
        }
    }

    private void link(HSLFTextRun r, String url, int anchorPage) {
        String safe = url == null ? null : Links.safeUrl(url);
        if (safe != null) {
            HSLFHyperlink h = r.createHyperlink();
            if (safe.regionMatches(true, 0, "mailto:", 0, 7)) {
                h.linkToEmail(safe.substring(7));
            } else {
                h.linkToUrl(safe);
            }
        } else if (anchorPage >= 0) {
            HSLFSlide target = slideOf.apply(anchorPage);
            if (target != null) {
                r.createHyperlink().linkToSlide(target);
            }
        }
    }

    private static void spacing(HSLFTextParagraph p, float lineHeight, float size, float spaceBefore) {
        if (lineHeight > 0 && size > 0) {
            p.setLineSpacing((double) Math.max(1, Math.round(100 * lineHeight / LineBoxes.single(size))));
        } else {
            p.setLineSpacing(100.0);
        }
        p.setSpaceBefore(-(double) Math.round(Math.max(0, spaceBefore)));
        p.setSpaceAfter(0.0);
    }

    private static void bullet(HSLFTextParagraph p, Bullet b, float textSize) {
        if (b == null) {
            p.setBullet(false);
            return;
        }
        p.setBullet(true);
        String text = b.marker().text();
        char c = text.charAt(0);
        RunStyle s = b.style();
        if (s.font() != null) {
            p.setBulletFont(s.font());
        }
        if (c >= 0xF000 && c <= 0xF0FF) {
            c = (char) (c & 0xFF);
        }
        p.setBulletChar(c);
        if (s.rgb() >= 0) {
            p.setBulletColor(new Color(s.rgb()));
        }
        if (textSize > 0) {
            p.setBulletSize((double) Math.clamp(Math.round(100 * s.size() / textSize), 25, 400));
        }
    }

    private static void tabs(HSLFTextParagraph p, List<Paragraph.TabStop> tabs) {
        for (Paragraph.TabStop t : tabs) {
            TabStopType type = switch (t.kind()) {
                case CENTER -> TabStopType.CENTER;
                case RIGHT -> TabStopType.RIGHT;
                case DECIMAL -> TabStopType.DECIMAL;
                default -> TabStopType.LEFT;
            };
            p.addTabStops(t.pos(), type);
        }
    }

    private static TextAlign align(Align a) {
        return switch (a) {
            case CENTER -> TextAlign.CENTER;
            case RIGHT -> TextAlign.RIGHT;
            case JUSTIFY -> TextAlign.JUSTIFY;
            default -> TextAlign.LEFT;
        };
    }
}
