package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EdgeLimitsTest {

    private static final String LONG = "F" + "x".repeat(140);

    @TempDir
    Path dir;

    private Path pdf(String name, PDRectangle size, String content, boolean longFontName) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(size);
            d.addPage(p);
            PDResources res = new PDResources();
            PDFont f = Samples.std(Standard14Fonts.FontName.HELVETICA);
            res.put(COSName.getPDFName(longFontName ? LONG : "F1"), f);
            p.setResources(res);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    private Path convert(Path in, PdfALevel level) throws Exception {
        Path out = dir.resolve(in.getFileName().toString().replace(".pdf", "-" + level + ".pdf"));
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        return out;
    }

    private static int dark(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0, 0.25f);
            int n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) & 0xFF) < 128) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void deepGraphicsStateNestingMovesIntoForms(PdfALevel level) throws Exception {
        String content = "q ".repeat(40) + "0 0 1 rg 100 100 300 300 re f " + "Q ".repeat(40)
                + "q ".repeat(35) + "1 0 0 rg 150 450 100 100 re f " + "Q ".repeat(35);
        Path in = pdf("nesting", PDRectangle.A4, content, false);
        Path out = convert(in, level);
        assertEquals(dark(in), dark(out));
        assertTrue(Converted.text(out).isBlank());
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void longNamesAndStringsAreShortenedWithoutLosingText(PdfALevel level) throws Exception {
        String text = "abcdefghij".repeat(7000);
        String content = "BT /" + LONG + " 1 Tf 0.001 Tz 20 700 Td (" + text + ") Tj ET BT /" + LONG
                + " 10 Tf 20 600 Td [" + "(a) -10 ".repeat(5000) + "] TJ ET";
        Path in = pdf("long", PDRectangle.A4, content, true);
        Path out = convert(in, level);
        String extracted = Converted.text(out).replaceAll("\\s", "");
        assertTrue(extracted.contains(text), "the long string is all still there");
        assertTrue(extracted.contains("a".repeat(5000)));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A3B"})
    void hugePagesGetAUserUnit(PdfALevel level) throws Exception {
        Path in = dir.resolve("huge.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(new PDRectangle(20_000, 30_000));
            p.setCropBox(new PDRectangle(0, 0, 20_000, 30_000));
            p.setBleedBox(new PDRectangle(0, 0, 1, 1));
            d.addPage(p);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream()) {
                o.write("0 0 1 rg 1000 1000 10000 20000 re f".getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(1000, 1000, 5000, 5000));
            PDPageXYZDestination dest = new PDPageXYZDestination();
            dest.setPage(p);
            dest.setLeft(1000);
            dest.setTop(29_000);
            link.setDestination(dest);
            p.getAnnotations().add(link);
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDPage p = d.getPage(0);
            COSDictionary page = p.getCOSObject();
            float unit = ((COSNumber) page.getDictionaryObject(COSName.getPDFName("UserUnit"))).floatValue();
            assertEquals(30_000f / PageSize.MAX, unit, 0.01);
            assertEquals(30_000, p.getMediaBox().getHeight() * unit, 30);
            assertTrue(p.getMediaBox().getHeight() <= PageSize.MAX);
            assertNull(page.getDictionaryObject(COSName.BLEED_BOX));
            COSDictionary link = (COSDictionary) ((COSArray) page.getDictionaryObject(COSName.ANNOTS)).getObject(0);
            assertEquals(5000, new PDRectangle((COSArray) link.getDictionaryObject(COSName.RECT)).getWidth() * unit, 5);
            COSArray dest = (COSArray) link.getDictionaryObject(COSName.DEST);
            assertEquals(29_000, ((COSNumber) dest.getObject(3)).floatValue() * unit, 5);
        }
        assertEquals(dark(in), dark(out), dark(in) / 100.0);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B"})
    void partOnePagesKeepTheirBoxes(PdfALevel level) throws Exception {
        Path in = pdf("big", new PDRectangle(20_000, 300), "0 0 1 rg 10 10 100 100 re f", false);
        Path out = convert(in, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(20_000, d.getPage(0).getMediaBox().getWidth(), 0.5);
            assertFalse(d.getPage(0).getCOSObject().containsKey(COSName.getPDFName("UserUnit")));
        }
    }
}
