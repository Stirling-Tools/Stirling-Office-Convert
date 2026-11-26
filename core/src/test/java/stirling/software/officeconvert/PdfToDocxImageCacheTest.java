package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToDocxImageCacheTest {

    @TempDir Path dir;

    @Test
    void picturesReachTheDocxWithoutImageIoDiskCache() throws Exception {
        Path pdf = dir.resolve("pictures.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(LosslessFactory.createFromImage(doc, picture(false)), 72, 500, 200, 200);
                cs.drawImage(LosslessFactory.createFromImage(doc, picture(true)), 300, 500, 200, 200);
            }
            doc.save(pdf.toFile());
        }
        List<byte[]> plain = media(convert(pdf, "plain.docx"));
        List<byte[]> uncached = NoImageCache.run(() -> media(convert(pdf, "uncached.docx")));
        assertFalse(plain.isEmpty());
        assertArrayEquals(plain.toArray(), uncached.toArray());
    }

    private Path convert(Path pdf, String name) throws IOException {
        Path docx = dir.resolve(name);
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        return docx;
    }

    private static BufferedImage picture(boolean noise) {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                img.setRGB(x, y, noise ? random.nextInt(0x1000000) : (x * 255 / 200) << 16 | (y * 255 / 200) << 8);
            }
        }
        return img;
    }

    private static List<byte[]> media(Path docx) throws IOException {
        List<String> names = new ArrayList<>();
        List<byte[]> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.getName().startsWith("word/media/")) {
                    names.add(e.getName());
                }
            }
            Collections.sort(names);
            for (String n : names) {
                out.add(zip.getInputStream(zip.getEntry(n)).readAllBytes());
            }
        }
        return out;
    }
}
