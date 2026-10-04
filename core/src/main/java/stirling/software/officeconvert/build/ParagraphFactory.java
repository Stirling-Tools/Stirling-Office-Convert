package stirling.software.officeconvert.build;

import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LogicalOrder;
import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph.LineRule;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.ScriptWidths;
import stirling.software.officeconvert.model.StyleSheet;

final class ParagraphFactory {

    private final DocStats stats;
    private final RunBuilder runs;
    private final StyleSheet styles;
    private final Numbering numbering;
    private final ListTracker lists = new ListTracker();

    ParagraphFactory(DocStats stats, RunBuilder runs, StyleSheet styles, Numbering numbering) {
        this.stats = stats;
        this.runs = runs;
        this.styles = styles;
        this.numbering = numbering;
    }

    float lineHeight(ParaDraft d) {
        float size = d.size();
        if (d.lines.size() >= 2 && d.pitch > 0) {
            return Math.max(d.pitch, size * 0.95f);
        }
        return stats.lineHeightFor(size);
    }

    float wordTop(ParaDraft d) {
        return d.first().baseline - DocStats.WORD_BASELINE * lineHeight(d);
    }

    float wordBottom(ParaDraft d) {
        return d.last().baseline + (1 - DocStats.WORD_BASELINE) * lineHeight(d);
    }

    Paragraph paragraph(ParaDraft d, float colLeft, float colRight, float spaceBefore, boolean pageBreak) {
        return paragraph(d, colLeft, colRight, spaceBefore, pageBreak, true);
    }

    Paragraph detached(ParaDraft d, float colLeft, float colRight, float spaceBefore) {
        return paragraph(d, colLeft, colRight, spaceBefore, false, false, true);
    }

    private Paragraph paragraph(
            ParaDraft d, float colLeft, float colRight, float spaceBefore, boolean pageBreak, boolean flow) {
        return paragraph(d, colLeft, colRight, spaceBefore, pageBreak, flow, false);
    }

    private Paragraph paragraph(ParaDraft d, float colLeft, float colRight, float spaceBefore, boolean pageBreak,
            boolean flow, boolean markers) {
        if (flow) {
            classify(d);
        }
        Paragraph p = new Paragraph();
        p.pageBreakBefore = pageBreak;
        p.spaceBefore = spaceBefore;
        p.align = d.align;
        p.sourceTop = wordTop(d);
        p.sourceBottom = wordBottom(d);
        p.indentLeft = d.left;
        p.indentFirst = d.first;
        p.indentRight = d.right;
        p.lineHeight = lineHeight(d);
        p.lineRule = LineRule.EXACT;
        float hostSize = d.size();
        int skip = 0;
        switch (d.role) {
            case TITLE -> p.style = "Title";
            case HEADING -> p.style = "Heading" + d.headingLevel;
            case LIST -> {
                skip = 1;
                p.style = "ListParagraph";
                applyList(p, d, colLeft);
            }
            default -> p.style = "Normal";
        }
        if (d.role == ParaDraft.Role.TITLE || d.role == ParaDraft.Role.HEADING) {
            styles.use(p.style, dominantStyle(d, hostSize));
        } else if (d.role == ParaDraft.Role.LIST) {
            styles.use(p.style, styles.normal);
        }
        if (flow && d.role != ParaDraft.Role.LIST) {
            lists.body(d.left + Math.min(0, d.first), d.role != ParaDraft.Role.BODY);
        }
        if (hasMixedSizes(d)) {
            p.lineRule = LineRule.AT_LEAST;
        }
        int rtlLines = 0;
        boolean anyRtl = false;
        for (Line l : d.lines) {
            if (LogicalOrder.rtlBase(l)) {
                rtlLines++;
            }
            anyRtl |= LogicalOrder.hasRtl(l);
        }
        p.bidi = d.rtl || rtlLines * 2 > d.lines.size() || anyRtl && d.align == Align.RIGHT && stats.scripts.rightToLeft();
        boolean markerTab = markers && d.role == ParaDraft.Role.BODY && (d.align == Align.LEFT || d.align == Align.JUSTIFY)
                && literalMarker(d);
        if (markerTab) {
            hangFromMarker(p, d, colLeft);
        }
        float justify = flow && d.align == Align.JUSTIFY ? d.justifySlack : Float.NaN;
        runs.fill(p, d.lines, skip, colLeft, colRight, hostSize, d.hardBreaks, d.pageBreaks, markerTab, justify);
        if (flow && p.align == Align.JUSTIFY && !Float.isNaN(d.justifySlack)) {
            p.indentRight += SectionPlanner.RIGHT_SLACK - d.justifySlack;
        }
        boolean startSet = p.align == (p.bidi ? Align.RIGHT : Align.LEFT) || p.align == Align.JUSTIFY;
        float slack = standInSlack(d);
        if (flow && (d.lines.size() >= 2 || startSet) && slack > 0 && !d.first().unspaced()
                && !(p.bidi && p.list != null)) {
            float widest = 0;
            for (Line l : d.lines) {
                widest = Math.max(widest, l.width());
            }
            if (p.bidi) {
                p.indentLeft -= Math.min(5f, slack * widest);
            } else {
                p.indentRight -= Math.min(5f, slack * widest);
            }
        }

        if (skip == 1 && p.list == null) {
            p.inlines.clear();
            runs.fill(p, d.lines, 0, colLeft, colRight, hostSize, d.hardBreaks, d.pageBreaks, false, justify);
        }
        p.markStyle = lastStyle(p);
        p.sourceLines = d.lines.size();
        float widest = 0;
        for (Line l : d.lines) {
            widest = Math.max(widest, l.width());
        }
        p.textWidth = widest;
        return p;
    }

    private static float standInSlack(ParaDraft d) {
        int standIn = 0;
        int modeled = 0;
        int total = 0;
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    total++;
                    standIn += g.font.substituted() ? 1 : 0;
                    modeled += ScriptWidths.clustered(RunBuilder.modeled(g))
                            || g.font.substituted() && RunBuilder.hebrew(g) ? 1 : 0;
                }
            }
        }
        return standIn * 2 > total && modeled * 2 <= total ? STAND_IN_SLACK : 0f;
    }

    private static final float STAND_IN_SLACK = 0.025f;

    private static boolean hasMixedSizes(ParaDraft d) {
        float min = Float.MAX_VALUE;
        float max = 0;
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    if (g.vertAlign == 0) {
                        min = Math.min(min, g.size);
                        max = Math.max(max, g.size);
                    }
                }
            }
        }
        return max > min * 1.25f;
    }

    static RunStyle lastStyle(Paragraph p) {
        for (int i = p.inlines.size() - 1; i >= 0; i--) {
            if (p.inlines.get(i) instanceof Inline.Text t) {
                return t.style().withVertAlign(0);
            }
        }
        return null;
    }

    private static RunStyle dominantStyle(ParaDraft d, float hostSize) {
        Map<RunStyle, Integer> counts = new HashMap<>();
        for (Line l : d.lines) {
            for (Word w : l.words) {
                for (Glyph g : w.glyphs) {
                    RunStyle s = RunBuilder.styleOf(g, hostSize);
                    if (g.vertAlign == 0) {
                        counts.merge(new RunStyle(s.font(), s.size(), s.bold(), s.italic(), false, false, s.rgb(), -1, 0, s.symbol(),
                                0f, s.scale(), s.smallCaps()),
                                g.text.length(), Integer::sum);
                    }
                }
            }
        }
        RunStyle best = null;
        int bestN = -1;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best;
    }

    private void classify(ParaDraft d) {
        if (d.role != ParaDraft.Role.BODY) {
            return;
        }
        Line first = d.first();
        if (first.words.size() >= 2 && startsWithMarker(first)) {
            Marker m = Marker.leading(first);
            if (m != null) {
                d.role = ParaDraft.Role.LIST;
                d.marker = m;
                Word next = first.words.get(Marker.nextIndex(first));
                d.markerTextX = Marker.startIndex(first) > 0 ? d.colRight - next.right : next.x - d.colLeft;
                return;
            }
        }
        int chars = d.chars();
        if (d.lines.size() > 3 || chars > 220) {
            return;
        }
        float size = d.size();
        boolean bold = d.allBold();
        int level = stats.headingLevel(size, bold);
        if (level == 0) {
            return;
        }
        String text = d.text().strip();
        if (text.isEmpty() || !Character.isLetterOrDigit(text.codePointAt(0)) && !Character.isLetter(text.codePointAt(0))) {
            return;
        }
        boolean bodySized = Math.abs(size - stats.bodySize) < 0.6f;
        if (bodySized && (d.lines.size() > 1 || chars > 90 || text.endsWith(".") && !text.matches("^[0-9.]+\\s.*"))) {
            return;
        }
        DocStats.Tier tier = level - 1 < stats.tiers.size() ? stats.tiers.get(level - 1) : null;
        if (level == 1 && tier != null && tier.lines() <= 3 && tier.firstPage() == 0 && size >= stats.bodySize * 1.5f
                && stats.tiers.size() > 1) {
            d.role = ParaDraft.Role.TITLE;
            return;
        }
        d.role = ParaDraft.Role.HEADING;
        boolean hasTitle = !stats.tiers.isEmpty() && stats.tiers.getFirst().lines() <= 3
                && stats.tiers.getFirst().firstPage() == 0 && stats.tiers.size() > 1
                && stats.tiers.getFirst().size() >= stats.bodySize * 1.5f;
        d.headingLevel = Math.max(1, Math.min(6, hasTitle ? level - 1 : level));
    }

    private static boolean startsWithMarker(Line line) {
        Marker m = Marker.leading(line);
        if (m == null) {
            return false;
        }
        if (Marker.tabAfter(line)) {
            return true;
        }
        float gap = Marker.gapAfter(line);
        return m.isBullet() ? gap > 0.15f * line.size : gap > 0.35f * line.size;
    }

    private static boolean literalMarker(ParaDraft d) {
        Line first = d.first();
        return first.words.size() >= 2 && startsWithMarker(first) && !LogicalOrder.rtlBase(first);
    }

    private static void hangFromMarker(Paragraph p, ParaDraft d, float colLeft) {
        Line first = d.first();
        float markerX = first.x - colLeft;
        float textX = first.words.get(1).x - colLeft;
        p.indentLeft = textX;
        p.indentFirst = markerX - textX;
        if (d.lines.size() >= 2) {
            float cont = d.lines.get(1).x - colLeft;
            if (Math.abs(cont - textX) > 2f) {
                p.indentLeft = cont;
                p.indentFirst = markerX - cont;
            }
        }
    }

    private void applyList(Paragraph p, ParaDraft d, float colLeft) {
        boolean rtl = Marker.startIndex(d.first()) > 0;
        float markerX = rtl ? d.colRight - d.first().right : d.first().x - colLeft;
        float textX = d.markerTextX;
        Glyph mg = d.first().words.get(Marker.startIndex(d.first())).first();
        RunStyle markerStyle = RunBuilder.styleOf(mg, d.size());
        ListTracker.Slot slot = lists.place(d.marker, markerX, numbering, markerStyle, textX, markerX);
        if (slot == null) {
            return;
        }
        p.list = new Paragraph.ListRef(slot.numId(), slot.level());
        if (p.align == Align.CENTER && markerX < (d.colRight - colLeft) / 4f) {
            p.align = rtl ? Align.RIGHT : Align.LEFT;
        }
        float start = textX;
        if (d.lines.size() >= 2) {
            float cont = rtl ? d.colRight - d.lines.get(1).right : d.lines.get(1).x - colLeft;
            start = Math.abs(cont - textX) > 2f ? cont : textX;
        }
        if (rtl) {
            p.indentRight = start;
            p.indentLeft = 0;
            p.align = p.align == Align.LEFT ? Align.RIGHT : p.align;
        } else {
            p.indentLeft = start;
        }
        p.indentFirst = markerX - start;
    }

    Paragraph notePara(ParaDraft d) {
        Paragraph p = paragraph(d, d.colLeft, d.colRight, 0, false, false);
        p.indentLeft = 0;
        p.indentFirst = 0;
        return p;
    }

    Paragraph spacer(float height) {
        Paragraph sp = new Paragraph();
        sp.markStyle = styles.normal.withSize(1);
        sp.lineRule = LineRule.EXACT;
        sp.lineHeight = Math.max(1f, height);
        return sp;
    }
}
