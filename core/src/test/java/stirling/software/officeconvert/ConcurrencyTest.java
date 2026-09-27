package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyTest {

    private static final PDType1Font SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font SERIF = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);

    @TempDir Path dir;

    @Test
    void parallelConversionsMatchSequentialOnes() throws Exception {
        List<Path> pdfs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pdfs.add(makePdf(i));
        }
        List<Map<String, byte[]>> expected = new ArrayList<>();
        for (Path pdf : pdfs) {
            expected.add(convert(pdf));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, byte[]>>> runs = new ArrayList<>();
            for (int round = 0; round < 6; round++) {
                for (Path pdf : pdfs) {
                    runs.add(pool.submit(() -> convert(pdf)));
                }
            }
            for (int i = 0; i < runs.size(); i++) {
                Map<String, byte[]> want = expected.get(i % pdfs.size());
                Map<String, byte[]> got = runs.get(i).get();
                assertEquals(want.keySet(), got.keySet());
                for (String part : want.keySet()) {
                    assertArrayEquals(want.get(part), got.get(part), part + " of document " + i % pdfs.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, byte[]> convert(Path pdf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        }
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().equals("docProps/core.xml")) {
                    parts.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        return parts;
    }

    private Path makePdf(int variant) throws IOException {
        Path pdf = dir.resolve("doc" + variant + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDType1Font font = variant % 2 == 0 ? SANS : SERIF;
                text(cs, font, 18, 72, 780, "Report " + variant);
                for (int i = 0; i < 6; i++) {
                    text(cs, font, 11, 72, 750 - i * 14, "Line " + i + " of running text that fills most of the width for document " + variant);
                }
                for (int r = 0; r <= 3; r++) {
                    cs.moveTo(72, 640 - r * 20);
                    cs.lineTo(400, 640 - r * 20);
                }
                for (float x : new float[] {72, 200, 400}) {
                    cs.moveTo(x, 640);
                    cs.lineTo(x, 580);
                }
                cs.stroke();
                for (int r = 0; r < 3; r++) {
                    text(cs, font, 10, 76, 626 - r * 20, "Item " + (r + variant));
                    text(cs, font, 10, 204, 626 - r * 20, String.valueOf(100 * (r + 1) + variant));
                }
                cs.moveTo(300, 450);
                cs.curveTo(300, 505, 380, 505, 380, 450);
                cs.curveTo(380, 395, 300, 395, 300, 450);
                cs.fill();
                text(cs, font, 7, 318, 385, "Share " + variant);
                cs.beginText();
                cs.setFont(font, 9);
                cs.setTextMatrix(Matrix.getRotateInstance(Math.PI / 2, 40, 300));
                cs.showText("Sideways stamp " + variant);
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
