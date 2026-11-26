package stirling.software.officeconvert.topdf.iwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.extract.StreamGuard;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.ZipBytes;

class IWorkPreviewTest {

    @TempDir
    Path dir;

    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] preview(String... pages) throws IOException {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage p = new PDPage(PDRectangle.A4);
                d.addPage(p);
                try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                    c.beginText();
                    c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    c.newLineAtOffset(72, 700);
                    c.showText(text);
                    c.endText();
                }
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aPreviewContentStreamPastTheDecodeLimitIsEmptiedBeforeItIsImported() throws IOException {
        byte[] bomb;
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            long size = StreamGuard.MAX_DECODED + (1 << 20);
            InputStream spaces = new InputStream() {
                long left = size;

                @Override
                public int read() {
                    return left-- > 0 ? ' ' : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    if (left <= 0) {
                        return -1;
                    }
                    int n = (int) Math.min(len, left);
                    Arrays.fill(b, off, off + n, (byte) ' ');
                    left -= n;
                    return n;
                }
            };
            p.setContents(new PDStream(d, spaces, COSName.FLATE_DECODE));
            d.save(out);
            bomb = out.toByteArray();
        }
        ByteArrayOutputStream key = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(key)) {
            ZipEntry e = new ZipEntry("QuickLook/Preview.pdf");
            e.setMethod(ZipEntry.STORED);
            e.setSize(bomb.length);
            CRC32 crc = new CRC32();
            crc.update(bomb);
            e.setCrc(crc.getValue());
            z.putNextEntry(e);
            z.write(bomb);
            z.closeEntry();
        }
        Path in = Files.write(dir.resolve("bomb.key"), key.toByteArray());
        Path out = dir.resolve("bomb.pdf");
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> OfficeToPdf.convert(in, out));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDResources res = d.getPage(0).getResources();
            for (COSName n : res.getXObjectNames()) {
                if (res.getXObject(n) instanceof PDFormXObject form) {
                    assertTrue(form.getCOSObject().getLength() < 4096, "form of " + form.getCOSObject().getLength());
                }
            }
        }
    }

    @Test
    void aPagesDocumentIsDrawnFromItsPdfPreviewAndSaysSo() throws IOException {
        byte[] pages = zip(Map.of("index.xml", "<sl:document/>".getBytes(), "QuickLook/Preview.pdf",
                preview("First page of the report", "Second page")));
        Path in = Files.write(dir.resolve("report.pages"), pages);
        Path out = dir.resolve("report.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out);
        assertEquals(2, r.pages());
        assertTrue(r.warnings().toString().contains("PDF preview"), r.warnings().toString());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String t = new PDFTextStripper().getText(d);
            assertTrue(t.contains("First page of the report") && t.contains("Second page"), t);
        }
    }

    @Test
    void aKeynoteWithOnlyAPreviewPictureIsMarkedIncomplete() throws IOException {
        byte[] key = zip(Map.of("Index/Document.iwa", new byte[] {1, 2, 3}, "preview.jpg",
                Fixtures.png(40, 30, Color.BLUE)));
        Path in = Files.write(dir.resolve("deck.key"), key);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("deck.pdf"));
        assertTrue(r.truncated());
        assertTrue(r.warnings().toString().contains("first page only"), r.warnings().toString());
    }

    @Test
    void anIWorkFileWithoutAPreviewIsRefusedPlainly() throws IOException {
        Path in = Files.write(dir.resolve("table.numbers"), zip(Map.of("index.xml", "<ls:document/>".getBytes())));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("table.pdf")));
        assertTrue(e.getMessage().contains("holds no preview"), e.getMessage());
    }

    private static byte[] blackPage(PDRectangle media, PDRectangle crop, int rotate) throws IOException {
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage p = new PDPage(media);
            p.setCropBox(crop);
            p.setRotation(rotate);
            d.addPage(p);
            try (PDPageContentStream c = new PDPageContentStream(d, p)) {
                c.setNonStrokingColor(Color.BLACK);
                c.addRect(crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight() / 2);
                c.fill();
            }
            d.save(out);
            return out.toByteArray();
        }
    }

    private static int dark(BufferedImage img, double fx, double fy) {
        int rgb = img.getRGB((int) (fx * (img.getWidth() - 1)), (int) (fy * (img.getHeight() - 1)));
        return (rgb & 0xFF) < 128 ? 1 : 0;
    }

    @Test
    void aRotatedPreviewPageKeepsItsProportionsAndAnOffsetCropBoxIsShownOnce() throws IOException {
        PDRectangle crop = new PDRectangle(100, 100, 300, 400);
        byte[] pages = zip(Map.of("index.xml", "<sl:document/>".getBytes(), "QuickLook/Preview.pdf",
                blackPage(new PDRectangle(600, 800), crop, 90)));
        Path in = Files.write(dir.resolve("turned.pages"), pages);
        Path out = dir.resolve("turned.pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(400, d.getPage(0).getMediaBox().getWidth(), 0.5);
            assertEquals(300, d.getPage(0).getMediaBox().getHeight(), 0.5);
            BufferedImage img = new PDFRenderer(d).renderImage(0, 0.25f);
            assertEquals(1, dark(img, 0.1, 0.5));
            assertEquals(1, dark(img, 0.4, 0.5));
            assertEquals(0, dark(img, 0.9, 0.5));
        }
        Path flat = Files.write(dir.resolve("offset.pages"), zip(Map.of("index.xml", "<sl:document/>".getBytes(),
                "QuickLook/Preview.pdf", blackPage(new PDRectangle(600, 800), crop, 0))));
        OfficeToPdf.convert(flat, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0, 0.25f);
            assertEquals(1, dark(img, 0.5, 0.9));
            assertEquals(0, dark(img, 0.5, 0.1));
        }
    }

    @Test
    void aDamagedOrEmptyPreviewPdfFallsBackToThePicture() throws IOException {
        byte[] empty;
        try (PDDocument d = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.save(out);
            empty = out.toByteArray();
        }
        for (byte[] pdf : new byte[][] {"%PDF-1.7 broken".getBytes(), empty}) {
            Path in = Files.write(dir.resolve("deck.key"), zip(Map.of("Index/Document.iwa", new byte[] {1},
                    "QuickLook/Preview.pdf", pdf, "preview.jpg", Fixtures.png(40, 30, Color.BLUE))));
            OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("deck.pdf"));
            assertEquals(1, r.pages());
            assertTrue(r.warnings().toString().contains("first page only"), r.warnings().toString());
        }
    }

    @Test
    void aPreviewPdfLongerThanItsDeclaredSizeFallsBackToThePicture() throws IOException {
        byte[] pdf = preview("One", "Two", "Three");
        byte[] key = ZipBytes.declareSize(new ZipBytes().add("Index/Document.iwa", new byte[] {1})
                .add("QuickLook/Preview.pdf", pdf).add("preview.jpg", Fixtures.png(40, 30, Color.BLUE)).bytes(),
                "QuickLook/Preview.pdf", pdf.length * 7 / 10);
        Path in = Files.write(dir.resolve("lie.key"), key);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("lie.pdf"));
        assertEquals(1, r.pages());
        assertTrue(r.warnings().toString().contains("first page only"), r.warnings().toString());
    }

    @Test
    void aZipWithAnUnrelatedIndexIsNotIWork() throws IOException {
        Path in = Files.write(dir.resolve("site.zip"), zip(Map.of("index.xml", "<html/>".getBytes())));
        assertFalse(IWorkPreview.is(in));
        Path pages = Files.write(dir.resolve("old.zip"), zip(Map.of("index.xml",
                "<sl:document xmlns:sl=\"http://developer.apple.com/namespaces/sl\"/>".getBytes())));
        assertTrue(IWorkPreview.is(pages));
    }

    @Test
    void aPreviewThatInflatesFarBeyondItsSizeIsNotRead() throws IOException {
        Path in = Files.write(dir.resolve("bomb.pages"), zip(Map.of("index.xml", "<sl:document/>".getBytes(),
                "QuickLook/Preview.pdf", new byte[200 << 20])));
        assertTrue(IWorkPreview.memoryBound(in) < 64L << 20, "" + IWorkPreview.memoryBound(in));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("bomb.pdf")));
        assertTrue(e.getMessage().contains("holds no preview"), e.getMessage());
    }
}
