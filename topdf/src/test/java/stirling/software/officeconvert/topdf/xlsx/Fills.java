package stirling.software.officeconvert.topdf.xlsx;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

final class Fills {

    private Fills() {}

    // Filled path bounds on one page, in points from the page's top-left corner
    static List<Rectangle2D> of(Path pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage p = doc.getPage(page);
            float height = p.getMediaBox().getHeight();
            List<Rectangle2D> out = new ArrayList<>();
            new PDFGraphicsStreamEngine(p) {
                private Rectangle2D path;

                private final Point2D.Float at = new Point2D.Float();

                private void add(double x, double y) {
                    if (path == null) {
                        path = new Rectangle2D.Double(x, height - y, 0, 0);
                    } else {
                        path.add(x, height - y);
                    }
                    at.setLocation(x, y);
                }

                @Override
                public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                    add(p0.getX(), p0.getY());
                    add(p1.getX(), p1.getY());
                    add(p2.getX(), p2.getY());
                    add(p3.getX(), p3.getY());
                }

                @Override
                public void moveTo(float x, float y) {
                    add(x, y);
                }

                @Override
                public void lineTo(float x, float y) {
                    add(x, y);
                }

                @Override
                public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                    add(x3, y3);
                }

                @Override
                public Point2D getCurrentPoint() {
                    return at;
                }

                @Override
                public void closePath() {}

                @Override
                public void endPath() {
                    path = null;
                }

                @Override
                public void strokePath() {
                    path = null;
                }

                @Override
                public void fillPath(int windingRule) {
                    if (path != null) {
                        out.add(path);
                    }
                    path = null;
                }

                @Override
                public void fillAndStrokePath(int windingRule) {
                    fillPath(windingRule);
                }

                @Override
                public void drawImage(PDImage pdImage) {}

                @Override
                public void clip(int windingRule) {}

                @Override
                public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {}

                void run() throws IOException {
                    processPage(p);
                }
            }.run();
            return out;
        }
    }
}
