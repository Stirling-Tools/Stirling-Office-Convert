package stirling.software.officeconvert.topdf.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

final class XlsxTesting {

    record Converted(OfficeToPdf.Result result, List<String> pages, List<float[]> sizes, int images) {

        String all() {
            return String.join("\n", pages);
        }
    }

    private XlsxTesting() {}

    static byte[] workbook(Consumer<XSSFWorkbook> build) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Converted convert(Path dir, String name, byte[] xlsx) throws IOException {
        Path in = Fixtures.write(dir, name, xlsx);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<String> pages = new ArrayList<>();
            List<float[]> sizes = new ArrayList<>();
            int images = 0;
            PDFTextStripper strip = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                strip.setStartPage(i);
                strip.setEndPage(i);
                pages.add(strip.getText(doc));
                PDPage page = doc.getPage(i - 1);
                sizes.add(new float[] {page.getMediaBox().getWidth(), page.getMediaBox().getHeight()});
                for (COSName n : page.getResources().getXObjectNames()) {
                    PDXObject x = page.getResources().getXObject(n);
                    if (x instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            return new Converted(result, pages, sizes, images);
        }
    }
}
