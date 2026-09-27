package stirling.software.officeconvert.build;

import java.util.List;

import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.PageLayout;

final class ColumnFlow {

    private final DocStats stats;

    ColumnFlow(DocStats stats) {
        this.stats = stats;
    }

    boolean flowsOn(PageLayout layout, PageLayout.Band band, int ci, float bodyBottom) {
        float line = stats.pitchFor(stats.bodySize);
        return columnFull(band, ci - 1) && lastBand(layout, band) && !startsLow(band, ci)
                && columnBottom(band.columns().get(ci - 1)) >= bodyBottom - 4 * line;
    }

    static boolean inFlow(PageLayout.Item it) {
        return !(it instanceof PageLayout.TextBoxItem || it instanceof PageLayout.FloatItem || it.behindText());
    }

    private boolean columnFull(PageLayout.Band band, int ci) {
        float deepest = -Float.MAX_VALUE;
        for (PageLayout.Column c : band.columns()) {
            deepest = Math.max(deepest, columnBottom(c));
        }
        float bottom = columnBottom(band.columns().get(ci));
        return bottom > -Float.MAX_VALUE && bottom >= deepest - 1.6f * stats.pitchFor(stats.bodySize);
    }

    private boolean startsLow(PageLayout.Band band, int ci) {
        float gap = firstTop(band.columns().get(ci)) - firstTop(band.columns().get(ci - 1));
        return gap > stats.pitchFor(stats.bodySize);
    }

    private static boolean lastBand(PageLayout layout, PageLayout.Band band) {
        List<PageLayout.Band> bands = layout.bands();
        for (int i = bands.indexOf(band) + 1; i < bands.size(); i++) {
            for (PageLayout.Column c : bands.get(i).columns()) {
                if (c.items().stream().anyMatch(ColumnFlow::inFlow)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static float columnBottom(PageLayout.Column col) {
        float bottom = -Float.MAX_VALUE;
        for (PageLayout.Item it : col.items()) {
            if (inFlow(it)) {
                bottom = Math.max(bottom, it.bottom());
            }
        }
        return bottom;
    }

    private static float firstTop(PageLayout.Column col) {
        for (PageLayout.Item it : col.items()) {
            if (inFlow(it)) {
                return it.top();
            }
        }
        return Float.NaN;
    }
}
