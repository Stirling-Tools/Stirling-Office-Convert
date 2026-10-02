package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.docx.Charts;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

// A chart sheet prints its chart on one page, filling the space inside the margins
record ChartSheet(String name, String chart, boolean portrait, double[] margins) {

    static ChartSheet read(RenderJob job, WorkbookModel.SheetRef ref) throws InterruptedIOException {
        try {
            OfficeZip zip = job.zip();
            Element root = zip.xml(ref.part()).getDocumentElement();
            Element setup = Dml.child(root, "pageSetup");
            boolean portrait = setup != null && "portrait".equals(Dml.attr(setup, "orientation"));
            Element m = Dml.child(root, "pageMargins");
            double[] margins = {inches(m, "left", 0.7), inches(m, "top", 0.75), inches(m, "right", 0.7),
                inches(m, "bottom", 0.75)};
            for (Relationship r : zip.relationships(ref.part()).ofType("drawing")) {
                if (!ActiveContent.mayFollow(r)) {
                    continue;
                }
                Document drawing = zip.xml(r);
                NodeList charts = drawing.getElementsByTagNameNS("*", "chart");
                for (int i = 0; i < charts.getLength(); i++) {
                    Node n = charts.item(i);
                    String id = n instanceof Element e ? Dml.attrNs(e, "id") : null;
                    Relationship c = id == null ? null : zip.relationships(r.part()).get(id);
                    if (c != null && ActiveContent.mayFollow(c)) {
                        return new ChartSheet(ref.name(), c.part(), portrait, margins);
                    }
                }
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("Chart sheet " + ref.name() + " could not be read");
        }
        return null;
    }

    private static double inches(Element m, String side, double fallback) {
        String v = Dml.attr(m, side);
        try {
            return (v == null ? fallback : Double.parseDouble(v)) * 72;
        } catch (NumberFormatException e) {
            return fallback * 72;
        }
    }

    void print(RenderJob job, String themePart) throws IOException {
        PageSize paper = PageSetup.DEFAULT_PAPER;
        float w = portrait ? paper.width() : paper.height();
        float h = portrait ? paper.height() : paper.width();
        try (PdfCanvas canvas = job.newPage(w, h)) {
            float cw = (float) Math.max(36, w - margins[0] - margins[2]);
            float ch = (float) Math.max(36, h - margins[1] - margins[3]);
            if (!Charts.draw(job, chart, themePart, canvas, (float) margins[0], (float) margins[1], cw, ch)) {
                job.warn("Chart sheet " + name + " could not be drawn");
            }
        }
    }
}
