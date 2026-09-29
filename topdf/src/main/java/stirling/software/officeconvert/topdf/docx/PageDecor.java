package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// What Word draws around a finished page's text: column rules and line numbers
final class PageDecor {

    private final Ctx ctx;

    private final DocxPackage pkg;

    PageDecor(Ctx ctx) {
        this.ctx = ctx;
        this.pkg = ctx.pkg;
    }

    // Word draws the column rule down the middle of each gap, as tall as the section's text on the page
    void columnRules(PageBox p) {
        Map<Integer, float[]> regions = new HashMap<>();
        for (Placed pl : p.placed) {
            if (pl.fixed) {
                continue;
            }
            float[] r = regions.computeIfAbsent(pl.section, k -> new float[] {Float.MAX_VALUE, 0, 0});
            r[0] = Math.min(r[0], pl.y);
            r[1] = Math.max(r[1], pl.y + pl.strip.height);
            r[2] = Math.max(r[2], pl.column);
        }
        for (Map.Entry<Integer, float[]> e : regions.entrySet()) {
            SectionProps s = pkg.sections.get(Math.min(e.getKey(), pkg.sections.size() - 1)).props();
            float[] r = e.getValue();
            if (!s.colSeparator || s.cols < 2 || r[2] < 1) {
                continue;
            }
            float[] lefts = s.columnLefts();
            float[] widths = s.columnWidths();
            for (int i = 0; i + 1 < s.cols && i < r[2]; i++) {
                float x = (lefts[i] + widths[i] + lefts[i + 1]) / 2 + p.shift;
                p.decor.add(new Op.Line(x, r[0], x, r[1], stirling.software.officeconvert.topdf.pdf.Stroke.solid(0.75f,
                        java.awt.Color.BLACK)));
            }
        }
    }

    private int lineNumber;

    private int lineNumberSection = -1;

    void lineNumbers(PageBox p) {
        SectionProps s = p.sect;
        if (s.lineNumberStep <= 0) {
            return;
        }
        if (!"continuous".equals(s.lineNumberRestart) && ("newPage".equals(s.lineNumberRestart)
                || lineNumberSection != p.section)) {
            lineNumber = s.lineNumberStart;
        }
        lineNumberSection = p.section;
        RunProps rp = pkg.styles.defaultRun.copy();
        rp.mergeFrom(pkg.styles.character("LineNumber"));
        Look look = Look.of(ctx.fonts.face(ctx.fonts.family(rp, Fonts.Slot.ASCII), rp.isBold(), rp.isItalic()), rp,
                rp.fontSize(), null, java.awt.Color.BLACK);
        List<Placed> lines = new ArrayList<>(p.placed);
        lines.sort((a, b) -> a.column != b.column ? Integer.compare(a.column, b.column) : Float.compare(a.y, b.y));
        for (Placed pl : lines) {
            if (pl.fixed || Float.isNaN(pl.strip.baseline) || !pl.strip.numbered || pl.section != p.section) {
                continue;
            }
            lineNumber++;
            if (lineNumber % s.lineNumberStep != 0) {
                continue;
            }
            String text = Integer.toString(lineNumber);
            float[] lefts = s.columnLefts();
            float x = lefts[Math.min(pl.column, lefts.length - 1)] + p.shift - s.lineNumberDistance
                    - look.style().width(text);
            p.decor.add(new Op.Text(x, pl.y + pl.strip.baseline, text, look.style()));
        }
    }
}
