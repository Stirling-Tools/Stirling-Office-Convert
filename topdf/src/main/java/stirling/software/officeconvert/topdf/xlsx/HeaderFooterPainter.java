package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.util.List;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class HeaderFooterPainter {

    static final double INSET = 0.96;

    private final Typesetter type;

    private final PdfCanvas canvas;

    HeaderFooterPainter(Typesetter type, PdfCanvas canvas) {
        this.type = type;
        this.canvas = canvas;
    }

    void header(HeaderFooterText.Sections s, PageSetup setup, double scale) throws IOException {
        double top = setup.header() + PrintMetrics.ORIGIN;
        section(s.left(), setup, scale, top, true, 0);
        section(s.center(), setup, scale, top, true, 1);
        section(s.right(), setup, scale, top, true, 2);
    }

    void footer(HeaderFooterText.Sections s, PageSetup setup, double scale) throws IOException {
        double bottom = setup.output().height() - setup.footer();
        section(s.left(), setup, scale, bottom, false, 0);
        section(s.center(), setup, scale, bottom, false, 1);
        section(s.right(), setup, scale, bottom, false, 2);
    }

    void picture(DecodedPicture pic, double w, double h, int where, boolean header, PageSetup setup)
            throws IOException {
        double width = setup.output().width();
        double pw = w > 0 ? w : pic.naturalWidth();
        double ph = h > 0 ? h : pic.naturalHeight();
        double left = setup.bandOffsetX() + (setup.headerFooter().alignWithMargins() ? setup.left() + INSET : 0.5 * 72);
        double right = width - (setup.headerFooter().alignWithMargins() ? setup.right() + INSET : 36);
        double x = where == 0 ? left : where == 2 ? right - pw : (width + setup.bandOffsetX() - pw) / 2;
        double y = header ? setup.header() + PrintMetrics.ORIGIN
                : setup.output().height() - setup.footer() - ph;
        canvas.image(pic, (float) x, (float) y, (float) pw, (float) ph, Crop.NONE, 0, false, false, 1);
    }

    private void section(List<List<TextRun>> lines, PageSetup setup, double scale, double edge, boolean header,
            int where) throws IOException {
        if (lines.isEmpty() || lines.stream().allMatch(List::isEmpty)) {
            return;
        }
        double s = setup.headerFooter().scaleWithDoc() ? scale : 1;
        double[] pitch = new double[lines.size()];
        double[] ascent = new double[lines.size()];
        double[] descent = new double[lines.size()];
        double total = 0;
        for (int i = 0; i < lines.size(); i++) {
            double p = 0;
            double a = 0;
            double d = 0;
            List<TextRun> line = lines.get(i);
            if (line.isEmpty()) {
                line = List.of(new TextRun("", lines.stream().filter(l -> !l.isEmpty()).findFirst().get().get(0)
                        .font()));
            }
            for (TextRun r : line) {
                FontMeasure m = type.measure(r.font());
                double size = r.font().size() * s;
                a = Math.max(a, m.ascent(size) + m.externalLeading(size) + 0.5);
                d = Math.max(d, m.descent(size));
                p = Math.max(p, m.printerLinePx(size) * PrintMetrics.PX);
            }
            pitch[i] = p;
            ascent[i] = a;
            descent[i] = d;
            total += p;
        }
        double width = setup.output().width();
        double left = setup.bandOffsetX() + (setup.headerFooter().alignWithMargins() ? setup.left() + INSET : 0.5 * 72);
        double right = width - (setup.headerFooter().alignWithMargins() ? setup.right() + INSET : 36);
        double center = (width + setup.bandOffsetX()) / 2;
        double baseline;
        if (header) {
            baseline = edge + ascent[0];
        } else {
            double last = edge - descent[lines.size() - 1] - 1.88;
            baseline = last - (total - pitch[lines.size() - 1]);
        }
        for (int i = 0; i < lines.size(); i++) {
            List<TextRun> line = lines.get(i);
            double w = 0;
            for (TextRun r : line) {
                w += type.width(r.text(), r.font(), r.font().drawSize() * s);
            }
            double x = switch (where) {
                case 0 -> left;
                case 2 -> right - w;
                default -> center - w / 2;
            };
            for (TextRun r : line) {
                double shift = r.font().offset() == FontSpec.Offset.SUPER ? -r.font().size() * s * 0.33
                        : r.font().offset() == FontSpec.Offset.SUB ? r.font().size() * s * 0.14 : 0;
                x += type.draw(canvas, r.text(), r.font(), r.font().drawSize() * s, x, baseline + shift);
            }
            if (i + 1 < lines.size()) {
                baseline += pitch[i + 1];
            }
        }
    }
}
