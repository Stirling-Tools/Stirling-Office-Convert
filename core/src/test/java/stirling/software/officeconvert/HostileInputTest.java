package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostileInputTest {

    @TempDir Path dir;

    @Test
    void nestedFormsStopAtTheFormBudget() throws IOException {
        Path pdf = dir.resolve("forms.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream next = form(stream(doc, "0 0 1 rg 0 0 1 1 re f"));
            for (int depth = 0; depth < 8; depth++) {
                COSStream form = form(stream(doc, "q /X Do Q ".repeat(8)));
                form.setItem(COSName.RESOURCES, xobjects(next));
                next = form;
            }
            page.setResources(new PDResources(xobjects(next)));
            page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "q /X Do Q BT /F1 12 Tf 72 700 Td (Still here) Tj ET"));
            page.getResources().put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            doc.save(pdf.toFile());
        }
        long start = System.nanoTime();
        String text = documentXml(convert(pdf));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 30_000, "took " + ms + " ms");
        assertTrue(text.contains("Still here"), text);
    }

    @Test
    void inflateBombPictureIsLeftOut() throws IOException {
        Path pdf = dir.resolve("picture.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream img = bomb(doc, 300);
            img.setItem(COSName.TYPE, COSName.XOBJECT);
            img.setItem(COSName.SUBTYPE, COSName.IMAGE);
            img.setInt(COSName.WIDTH, 100);
            img.setInt(COSName.HEIGHT, 100);
            img.setInt(COSName.BITS_PER_COMPONENT, 8);
            img.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            COSDictionary res = xobjects(img);
            page.setResources(new PDResources(res));
            page.getResources().put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.getCOSObject().setItem(COSName.CONTENTS,
                    stream(doc, "q 400 0 0 400 100 200 cm /X Do Q BT /F1 12 Tf 72 700 Td (Caption) Tj ET"));
            doc.save(pdf.toFile());
        }
        Path docx = convert(pdf);
        assertTrue(documentXml(docx).contains("Caption"));
        assertEquals(0, media(docx), "the bomb must not be decoded into a picture");
        Path fromDoc = dir.resolve("from-doc.docx");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile()); OutputStream out = java.nio.file.Files.newOutputStream(fromDoc)) {
            PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        }
        assertEquals(0, media(fromDoc));
    }

    @Test
    void inflateBombFontIsEmptied() throws IOException {
        Path pdf = dir.resolve("font.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "Bomb");
            fd.setInt(COSName.FLAGS, 32);
            fd.setItem(COSName.FONT_FILE2, bomb(doc, 300));
            fd.setItem(COSName.FONT_BBOX, box());
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            font.setName(COSName.BASE_FONT, "Bomb");
            font.setItem(COSName.FONT_DESC, fd);
            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            page.setResources(new PDResources(res));
            page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "BT /F1 12 Tf 72 700 Td (Hello bomb) Tj ET"));
            doc.save(pdf.toFile());
        }
        assertTrue(documentXml(convert(pdf)).contains("Hello"));
        for (String format : new String[] {"docx", "pptx", "xlsx"}) {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile());
                    OutputStream out = java.nio.file.Files.newOutputStream(dir.resolve("guarded." + format))) {
                switch (format) {
                    case "docx" -> PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
                    case "pptx" -> PdfToPptx.convert(doc, out, PdfToPptx.Options.defaults());
                    default -> PdfToXlsx.convert(doc, out, PdfToXlsx.Options.defaults());
                }
                COSDictionary descriptor = doc.getPage(0).getResources().getFont(COSName.getPDFName("F1")).getCOSObject()
                        .getCOSDictionary(COSName.FONT_DESC);
                assertEquals(0, descriptor.getCOSStream(COSName.FONT_FILE2).getLength(), format + " left the bomb in");
            }
        }
    }

    @Test
    void jpegLargerThanItsDeclarationIsLeftOut() throws IOException {
        byte[] jpeg = jpeg(64, 64);
        int sof = sof(jpeg);
        jpeg[sof + 5] = (byte) (26000 >> 8);
        jpeg[sof + 6] = (byte) (26000 & 0xFF);
        jpeg[sof + 7] = (byte) (26000 >> 8);
        jpeg[sof + 8] = (byte) (26000 & 0xFF);
        Path pdf = jpegPdf(jpeg, 64, 64, true);
        for (String ext : new String[] {"docx", "odt", "rtf", "odp"}) {
            Path out = dir.resolve("out." + ext);
            switch (ext) {
                case "docx" -> PdfToDocx.convert(pdf, out, PdfToDocx.Options.defaults());
                case "odt" -> PdfToOdt.convert(pdf, out, PdfToDocx.Options.defaults());
                case "rtf" -> PdfToRtf.convert(pdf, out, PdfToDocx.Options.defaults());
                default -> PdfToOdp.convert(pdf, out, PdfToPptx.Options.defaults());
            }
            assertTrue(largestPart(out) < 1_000_000, ext + " output holds a decoded giant");
        }
    }

    private static long largestPart(Path out) throws IOException {
        if (out.toString().endsWith(".rtf")) {
            return java.nio.file.Files.size(out);
        }
        try (ZipFile z = new ZipFile(out.toFile())) {
            return z.stream().mapToLong(ZipEntry::getSize).max().orElse(0);
        }
    }

    @Test
    void copiedJpegIsCleaned() throws IOException {
        byte[] plain = jpeg(120, 80);
        ByteArrayOutputStream dirty = new ByteArrayOutputStream();
        dirty.write(plain, 0, 2);
        byte[] comment = "<script>alert(1)</script>".getBytes(StandardCharsets.ISO_8859_1);
        dirty.write(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        dirty.write(comment);
        dirty.write(plain, 2, plain.length - 2);
        dirty.write("PK\u0003\u0004<html>".getBytes(StandardCharsets.ISO_8859_1));
        Path pdf = jpegPdf(dirty.toByteArray(), 120, 80, false);
        Path docx = convert(pdf);
        try (ZipFile z = new ZipFile(docx.toFile())) {
            ZipEntry e = z.stream().filter(x -> x.getName().startsWith("word/media/")).findFirst().orElseThrow();
            byte[] out = z.getInputStream(e).readAllBytes();
            String latin = new String(out, StandardCharsets.ISO_8859_1);
            assertTrue(!latin.contains("<script>") && !latin.contains("<html>"), "comment or trailer survived");
            assertEquals(0xD9, out[out.length - 1] & 0xFF);
            BufferedImage a = ImageIO.read(new java.io.ByteArrayInputStream(plain));
            BufferedImage b = ImageIO.read(new java.io.ByteArrayInputStream(out));
            assertEquals(a.getRGB(10, 10), b.getRGB(10, 10));
        }
    }

    @Test
    void interruptStopsMidPage() throws Exception {
        Path pdf = dir.resolve("ops.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "q Q ".repeat(4_000_000)));
            doc.save(pdf.toFile());
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                PdfToDocx.convert(pdf, dir.resolve("ops.docx"), PdfToDocx.Options.defaults());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.start();
        Thread.sleep(300);
        long interrupted = System.nanoTime();
        t.interrupt();
        t.join(10_000);
        long ms = (System.nanoTime() - interrupted) / 1_000_000;
        assertTrue(!t.isAlive() && ms < 5_000, "still running " + ms + " ms after the interrupt");
        if (failure.get() != null) {
            assertInstanceOf(InterruptedIOException.class, failure.get());
        }
    }

    private Path convert(Path pdf) throws IOException {
        Path docx = dir.resolve(pdf.getFileName() + ".docx");
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        return docx;
    }

    private static String documentXml(Path docx) throws IOException {
        try (ZipFile z = new ZipFile(docx.toFile())) {
            return new String(z.getInputStream(z.getEntry("word/document.xml")).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long media(Path docx) throws IOException {
        try (ZipFile z = new ZipFile(docx.toFile())) {
            return z.stream().filter(e -> e.getName().startsWith("word/media/")).count();
        }
    }

    private static COSStream stream(PDDocument doc, String content) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return s;
    }

    private static COSStream bomb(PDDocument doc, int mb) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream raw = s.createRawOutputStream();
                DeflaterOutputStream z = new DeflaterOutputStream(raw, new Deflater(9), 1 << 16)) {
            byte[] chunk = new byte[1 << 20];
            Arrays.fill(chunk, (byte) 0);
            for (int i = 0; i < mb; i++) {
                z.write(chunk);
            }
        }
        s.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        return s;
    }

    private static COSStream form(COSStream s) {
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.BBOX, box());
        return s;
    }

    private static COSDictionary xobjects(COSStream x) {
        COSDictionary xo = new COSDictionary();
        xo.setItem(COSName.getPDFName("X"), x);
        COSDictionary res = new COSDictionary();
        res.setItem(COSName.XOBJECT, xo);
        return res;
    }

    private static COSArray box() {
        COSArray a = new COSArray();
        for (int v : new int[] {0, 0, 1000, 1000}) {
            a.add(COSInteger.get(v));
        }
        return a;
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x * 2) << 16 | (y * 3) << 8 | 90);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static int sof(byte[] jpeg) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xC0) {
                return i;
            }
        }
        throw new IllegalStateException("no frame header");
    }

    private Path jpegPdf(byte[] jpeg, int w, int h, boolean cropped) throws IOException {
        Path pdf = dir.resolve("jpeg.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream img = doc.getDocument().createCOSStream();
            try (OutputStream raw = img.createRawOutputStream()) {
                raw.write(jpeg);
            }
            img.setItem(COSName.FILTER, COSName.DCT_DECODE);
            img.setItem(COSName.TYPE, COSName.XOBJECT);
            img.setItem(COSName.SUBTYPE, COSName.IMAGE);
            img.setInt(COSName.WIDTH, w);
            img.setInt(COSName.HEIGHT, h);
            img.setInt(COSName.BITS_PER_COMPONENT, 8);
            img.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            page.setResources(new PDResources(xobjects(img)));
            String clip = cropped ? "100 200 100 100 re W n " : "";
            page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, "q " + clip + "300 0 0 200 100 200 cm /X Do Q"));
            doc.save(pdf.toFile());
        }
        return pdf;
    }
}
