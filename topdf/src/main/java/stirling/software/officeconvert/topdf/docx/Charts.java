package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

/** Draws a DrawingML chart part from its cached values (never its workbook); shared by every format. */
public final class Charts {

    private Charts() {}

    /**
     * Draws the chart part into the box on the canvas, with the scheme colours of the theme part (or Office's
     * defaults when null); false when the part is not a chart that can be read.
     */
    public static boolean draw(RenderJob job, String chartPart, String themePart, PdfCanvas canvas, float x, float y,
            float w, float h) throws IOException {
        OfficeZip zip = job.zip();
        if (chartPart == null || !zip.exists(chartPart)) {
            return false;
        }
        XEl root;
        try (InputStream in = zip.open(chartPart)) {
            root = XTree.parse(in);
        }
        Theme theme = Theme.DEFAULT;
        if (themePart != null && zip.exists(themePart)) {
            try (InputStream in = zip.open(themePart)) {
                theme = new Theme(XTree.parse(in));
            }
        }
        List<Op> ops = new ArrayList<>();
        try {
            Chart chart = job.format() == OfficeToPdf.Format.PPTX ? ChartReader.readSlide(root, theme)
                    : ChartReader.read(root, theme);
            if (chart == null) {
                return false;
            }
            ChartPainter.paint(chart, x, y, w, h, ops, new Fonts(job, theme, null), theme);
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
        new Painter(job).draw(canvas, ops);
        return true;
    }
}
