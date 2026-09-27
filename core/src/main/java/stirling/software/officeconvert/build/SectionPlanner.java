package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.PageFrame;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.model.Section;

final class SectionPlanner {

    static final float RIGHT_SLACK = 1.5f;

    private SectionPlanner() {}

    static Section baseSection(PageData page, DocStats stats) {
        PageFrame f = stats.frame(page.width(), page.height());
        Section s = new Section();
        s.pageWidth = page.width();
        s.pageHeight = page.height();
        s.marginTop = Math.max(0, f.firstLineTop());
        s.marginLeft = Math.max(0, f.textLeft());
        s.marginRight = Math.max(0, page.width() - f.textRight() - RIGHT_SLACK);
        if (s.marginLeft + s.marginRight > page.width() - 72) {
            s.marginLeft = Math.min(s.marginLeft, 36);
            s.marginRight = Math.min(s.marginRight, 36);
        }
        s.marginBottom = page.height() - f.flowBottom(page.height());
        if (!Float.isNaN(f.footerBaseline())) {
            float lh = runningLineHeight(f.footerSize());
            float footerBottom = f.footerBaseline() + (1 - DocStats.WORD_BASELINE) * lh;
            s.footerDistance = Math.max(0, page.height() - footerBottom);
        } else {
            s.footerDistance = Math.min(36, s.marginBottom / 2);
        }
        if (!Float.isNaN(f.headerBaseline())) {
            float lh = runningLineHeight(f.headerSize());
            s.headerDistance = Math.max(0, f.headerBaseline() - DocStats.WORD_BASELINE * lh);
            float headerBottom = f.headerBottom() + 1;
            if (s.marginTop < headerBottom) {
                s.marginTop = headerBottom;
            }
        } else {
            s.headerDistance = Math.min(36, s.marginTop / 2);
        }
        if (s.marginTop + s.marginBottom > page.height() - 72) {
            s.marginTop = Math.min(s.marginTop, 36);
            s.marginBottom = Math.min(s.marginBottom, 36);
        }
        s.columns.add(new float[] {page.width() - s.marginLeft - s.marginRight, 0});
        s.titlePage = stats.headerFooterInfo().firstPageDiffers();
        return s;
    }

    static float runningLineHeight(float size) {
        return Math.max(1f, size) * 1.2f;
    }

    static List<float[]> columnsOf(PageLayout.Band band, Section base) {
        List<float[]> cols = new ArrayList<>();
        List<PageLayout.Column> cs = band.columns();
        if (cs.size() == 1) {
            cols.add(new float[] {base.pageWidth - base.marginLeft - base.marginRight, 0});
            return cols;
        }
        float left = base.marginLeft;
        float right = base.pageWidth - base.marginRight;
        for (int i = 0; i < cs.size(); i++) {
            float l = i == 0 ? left : cs.get(i).left();
            float r = i == cs.size() - 1 ? right : cs.get(i).right();
            float gap = i + 1 < cs.size() ? cs.get(i + 1).left() - r : 0;
            cols.add(new float[] {Math.max(18, r - l), Math.max(0, gap)});
        }
        return cols;
    }

    static boolean sameColumns(Section s, List<float[]> cols) {
        if (s.columns.size() != cols.size()) {
            return false;
        }
        if (cols.size() == 1) {
            return true;
        }
        for (int i = 0; i < cols.size(); i++) {
            if (Math.abs(s.columns.get(i)[0] - cols.get(i)[0]) > 3
                    || Math.abs(s.columns.get(i)[1] - cols.get(i)[1]) > 3) {
                return false;
            }
        }
        return true;
    }
}
