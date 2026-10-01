package stirling.software.officeconvert.topdf.odf;

import java.util.List;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class Autofit {

    private static final double LINE = 1.2;

    private Autofit() {}

    static int[] fit(List<DmlText.Para> paras, double width, double height, FontLibrary fonts) {
        if (fonts == null || paras.isEmpty() || width <= 1 || height <= 1) {
            return null;
        }
        if (height(paras, width, 1, 0, fonts) <= height + 0.5) {
            return null;
        }
        for (double scale = 97.5; scale >= 25; scale -= 2.5) {
            double reduction = scale >= 85 ? 0 : 20;
            if (height(paras, width, scale / 100, reduction / 100, fonts) <= height + 0.5) {
                return new int[] {(int) Math.round(scale * 1000), (int) Math.round(reduction * 1000)};
            }
        }
        return new int[] {25_000, 20_000};
    }

    static double height(List<DmlText.Para> paras, double width, double scale, double reduction, FontLibrary fonts) {
        double total = 0;
        boolean first = true;
        for (DmlText.Para p : paras) {
            if (!first) {
                total += p.before() * (1 - reduction);
            }
            first = false;
            double avail = Math.max(1, width - p.margin());
            double x = Math.max(0, p.indent());
            double lineSize = 0;
            int lines = 0;
            double h = 0;
            for (DmlText.Run r : p.runs()) {
                double size = Math.max(1, r.size() * scale);
                if (r.lineBreak()) {
                    h += line(p, Math.max(lineSize, size), reduction);
                    lines++;
                    x = 0;
                    lineSize = 0;
                    continue;
                }
                FontFace face = fonts.find(r.font() == null ? "Liberation Sans" : r.font(), r.bold(), r.italic());
                for (String word : r.text().split("(?<= )")) {
                    double ww = face.width(word, (float) size);
                    if (x + ww > avail && x > 0) {
                        h += line(p, Math.max(lineSize, size), reduction);
                        lines++;
                        x = 0;
                        lineSize = 0;
                        while (ww > avail) {
                            h += line(p, size, reduction);
                            lines++;
                            ww -= avail;
                        }
                    }
                    x += ww;
                    lineSize = Math.max(lineSize, size);
                }
            }
            h += line(p, lineSize > 0 ? lineSize : p.endSize() * scale, reduction);
            total += h + p.after() * (1 - reduction);
            if (lines > 10_000) {
                break;
            }
        }
        return total;
    }

    private static double line(DmlText.Para p, double size, double reduction) {
        if (p.linePts() > 0) {
            return p.linePts() * (1 - reduction);
        }
        return size * LINE * p.linePct() / 100 * (1 - reduction);
    }
}
