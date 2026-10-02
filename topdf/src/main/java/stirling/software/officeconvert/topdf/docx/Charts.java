package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

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
        Key key = new Key(chartPart, themePart);
        Object known = job.memo(key);
        Read read = known instanceof Read r ? r : read(job, zip, chartPart, themePart);
        job.memo(key, read);
        if (read.chart() == null) {
            return false;
        }
        Size size = new Size(key, w, h);
        Object form = job.memo(size);
        if (form == null) {
            boolean first = job.memo(new Drawn(size)) == null;
            if (first || !(w > 0 && h > 0)) {
                job.memo(new Drawn(size), Boolean.TRUE);
                List<Op> ops = paint(job, read, x, y, w, h);
                if (ops == null) {
                    return false;
                }
                new Painter(job).draw(canvas, ops);
                return true;
            }
            List<Op> ops = paint(job, read, 0, 0, w, h);
            if (ops == null) {
                return false;
            }
            try (PdfCanvas f = canvas.output().newForm(w, h, Math.max(w, h))) {
                new Painter(job).draw(f, ops);
                form = f.form();
            }
            job.memo(size, form);
        }
        float margin = Math.max(w, h);
        canvas.form((PDFormXObject) form, x - margin, y - margin, w + 2 * margin, h + 2 * margin);
        return true;
    }

    private static List<Op> paint(RenderJob job, Read read, float x, float y, float w, float h) {
        List<Op> ops = new ArrayList<>();
        try {
            ChartPainter.paint(read.chart(), x, y, w, h, ops, new Fonts(job, read.theme(), null), read.theme());
            return ops;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private record Key(String chart, String theme) {}

    private record Size(Key key, float w, float h) {}

    private record Drawn(Size size) {}

    private record Read(Chart chart, Theme theme) {}

    // A chart part is read once per conversion; the first place it shows at a size draws it there, later ones share
    // a form XObject
    private static Read read(RenderJob job, OfficeZip zip, String chartPart, String themePart) throws IOException {
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
        try {
            Chart chart = job.format() == OfficeToPdf.Format.PPTX ? ChartReader.readSlide(root, theme)
                    : ChartReader.read(root, theme);
            return new Read(chart, theme);
        } catch (RuntimeException | StackOverflowError e) {
            return new Read(null, theme);
        }
    }
}
