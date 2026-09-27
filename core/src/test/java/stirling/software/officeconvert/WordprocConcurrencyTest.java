package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordprocConcurrencyTest {

    private static final PDType1Font SANS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font SERIF = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);

    private interface Format {
        void convert(PDDocument doc, java.io.OutputStream out) throws IOException;
    }

    private static final Map<String, Format> FORMATS = Map.of(
            "odt", (d, o) -> PdfToOdt.convert(d, o, PdfToDocx.Options.defaults()),
            "fodt", (d, o) -> PdfToOdt.convertFlat(d, o, PdfToDocx.Options.defaults()),
            "rtf", (d, o) -> PdfToRtf.convert(d, o, PdfToDocx.Options.defaults()),
            "txt", (d, o) -> PdfToText.convert(d, o, PdfToDocx.Options.defaults()));

    @TempDir Path dir;

    @Test
    void parallelConversionsMatchSequentialOnes() throws Exception {
        List<Path> pdfs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pdfs.add(makePdf(i));
        }
        List<String> formats = List.copyOf(new TreeMap<>(FORMATS).keySet());
        Map<String, Map<String, byte[]>> expected = new TreeMap<>();
        for (Path pdf : pdfs) {
            for (String f : formats) {
                expected.put(pdf.getFileName() + "|" + f, convert(pdf, f));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<String> keys = new ArrayList<>();
            List<Future<Map<String, byte[]>>> runs = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                for (Path pdf : pdfs) {
                    for (String f : formats) {
                        keys.add(pdf.getFileName() + "|" + f);
                        runs.add(pool.submit(() -> convert(pdf, f)));
                    }
                }
            }
            for (int i = 0; i < runs.size(); i++) {
                Map<String, byte[]> want = expected.get(keys.get(i));
                Map<String, byte[]> got = runs.get(i).get();
                assertEquals(want.keySet(), got.keySet(), keys.get(i));
                for (String part : want.keySet()) {
                    assertArrayEquals(want.get(part), got.get(part), keys.get(i) + " " + part);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, byte[]> convert(Path pdf, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            FORMATS.get(format).convert(doc, out);
        }
        Map<String, byte[]> parts = new TreeMap<>();
        if (!format.equals("odt")) {
            String s = out.toString(StandardCharsets.UTF_8)
                    .replaceAll("<office:meta>.*?</office:meta>", "")
                    .replaceAll("\\{\\\\creatim[^}]*}", "");
            parts.put("all", s.getBytes(StandardCharsets.UTF_8));
            return parts;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().equals("meta.xml")) {
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
            BufferedImage img = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 60; x++) {
                for (int y = 0; y < 40; y++) {
                    img.setRGB(x, y, (x * 4 + variant * 40) << 16 | y * 6 << 8 | 128);
                }
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDType1Font font = variant % 2 == 0 ? SANS : SERIF;
                text(cs, font, 18, 72, 780, "Report " + variant);
                for (int i = 0; i < 14; i++) {
                    text(cs, font, 10, 72, 750 - i * 13, "Left column line " + i + " of the first column " + variant);
                    text(cs, font, 10, 310, 750 - i * 13, "Right column line " + i + " of the second one " + variant);
                }
                for (int r = 0; r <= 3; r++) {
                    cs.moveTo(72, 540 - r * 20);
                    cs.lineTo(400, 540 - r * 20);
                }
                for (float x : new float[] {72, 200, 400}) {
                    cs.moveTo(x, 540);
                    cs.lineTo(x, 480);
                }
                cs.stroke();
                for (int r = 0; r < 3; r++) {
                    text(cs, font, 10, 76, 526 - r * 20, "Item " + (r + variant));
                    text(cs, font, 10, 204, 526 - r * 20, String.valueOf(100 * (r + 1) + variant));
                }
                cs.drawImage(LosslessFactory.createFromImage(doc, img), 72, 300, 120, 80);
                cs.moveTo(300, 350);
                cs.curveTo(300, 405, 380, 405, 380, 350);
                cs.curveTo(380, 295, 300, 295, 300, 350);
                cs.fill();
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
