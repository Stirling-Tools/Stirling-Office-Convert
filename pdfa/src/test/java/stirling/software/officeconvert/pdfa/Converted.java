package stirling.software.officeconvert.pdfa;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

final class Converted {

    private Converted() {}

    static PdfToPdfA.Result convert(Path dir, String sample, PdfALevel level) throws Exception {
        Path in = Samples.write(dir, sample);
        return PdfToPdfA.convert(in, out(dir, sample, level), PdfToPdfA.Options.defaults().level(level));
    }

    static Path out(Path dir, String sample, PdfALevel level) {
        return dir.resolve(sample + "-" + level + ".pdf");
    }

    static String text(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    static List<float[]> glyphs(Path pdf) throws Exception {
        List<float[]> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition t) {
                    out.add(new float[] {t.getXDirAdj(), t.getYDirAdj(), t.getWidthDirAdj()});
                }
            };
            s.getText(d);
        }
        return out;
    }
}
