package stirling.software.officeconvert.model;

import java.util.ArrayList;
import java.util.List;

public final class Section {

    public float pageWidth;
    public float pageHeight;
    public float marginTop;
    public float marginBottom;
    public float marginLeft;
    public float marginRight;
    public float headerDistance;
    public float footerDistance;
    public boolean continuous;
    public final List<float[]> columns = new ArrayList<>();
    public boolean titlePage;
    public int pageNumberStart = 1;

    public Section copy() {
        Section s = new Section();
        s.pageWidth = pageWidth;
        s.pageHeight = pageHeight;
        s.marginTop = marginTop;
        s.marginBottom = marginBottom;
        s.marginLeft = marginLeft;
        s.marginRight = marginRight;
        s.headerDistance = headerDistance;
        s.footerDistance = footerDistance;
        s.continuous = continuous;
        s.columns.addAll(columns);
        s.titlePage = titlePage;
        s.pageNumberStart = pageNumberStart;
        return s;
    }

    public boolean samePage(Section o) {
        return Math.abs(pageWidth - o.pageWidth) < 1 && Math.abs(pageHeight - o.pageHeight) < 1;
    }
}
