package stirling.software.officeconvert.topdf.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.rototor.pdfbox.graphics2d.PdfBoxGraphics2D;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.testing.NoNetwork;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class PdfCanvasTest {

    static FontLibrary fonts;

    @TempDir
    static Path dir;

    @BeforeAll
    static void bundledFontsOnly() throws IOException {
        fonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("no-fonts"))));
    }

    private static byte[] save(PdfOutput out) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        out.save(bytes);
        return bytes.toByteArray();
    }

    @Test
    void textRoundTripsAtTopLeftCoordinates() throws Exception {
        FontFace face = fonts.find("Arial", false, false);
        TextStyle style = TextStyle.of(face, 12);
        byte[] pdf;
        float width;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                width = c.text("Hello World", 72, 100, style);
            }
            pdf = save(out);
        }
        assertEquals(style.width("Hello World"), width, 0.001f);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals("HelloWorld", text(glyphs));
        TextPosition h = glyphs.get(0);
        assertEquals(72, h.getXDirAdj(), 0.2);
        assertEquals(100, h.getYDirAdj(), 0.2);
        TextPosition d = glyphs.get(glyphs.size() - 1);
        assertEquals(72 + width, d.getXDirAdj() + d.getWidthDirAdj(), 0.3);
    }

    @Test
    void spacingScaleAndSyntheticStylesKeepPositionsAndText() throws Exception {
        FontFace bi = fonts.find("Arial", true, true);
        assertTrue(bi.syntheticBold() && bi.syntheticItalic());
        TextStyle spaced = TextStyle.of(bi, 10).charSpacing(1.5f).horizontalScale(80).wordSpacing(6).kerning(true)
                .color(new Color(200, 0, 0, 128));
        byte[] pdf;
        float width;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.LETTER)) {
                width = c.text("AV a b", 50, 200, spaced);
            }
            pdf = save(out);
        }
        assertEquals(spaced.width("AV a b"), width, 0.001f);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals("AVab", text(glyphs));
        TextPosition b = glyphs.get(glyphs.size() - 1);
        assertEquals(50 + spaced.width("AV a "), b.getXDirAdj(), 0.3);
        assertEquals(200, b.getYDirAdj(), 0.2);
    }

    @Test
    void drawsTheMissingGlyphBoxForUncoveredCharactersAndKeepsTheirSpace() throws Exception {
        TextStyle style = TextStyle.of(fonts.find("Arial", false, false), 12);
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                c.text("A\u4E2DB\u00AD\u0007C\u3000D", 72, 72, style);
            }
            pdf = save(out);
        }
        List<TextPosition> glyphs = letters(positions(pdf));
        assertEquals("ABCD", text(glyphs));
        assertEquals(12, style.width("\u4E2D"), 1e-3);
        assertEquals(72 + style.width("A\u4E2D"), glyphs.get(1).getXDirAdj(), 0.3);
        assertEquals(72 + style.width("A\u4E2DB\u00AD\u0007C\u3000"), glyphs.get(3).getXDirAdj(), 0.3);
        List<TextPosition> all = positions(pdf);
        assertEquals("A\u4E2DBCD", text(all));
        assertEquals(72 + style.width("A"), all.get(1).getXDirAdj(), 0.3);
        assertEquals(0, missingGlyphs(content(pdf)));
    }

    @Test
    void charactersNoFontHasExtractAsTextInOrderAndShareOneBoxGlyph() throws Exception {
        TextStyle style = TextStyle.of(fonts.find("MS Mincho", false, false), 10);
        String text = "\u7B2C1\u7AE0 \u4E2D\u6587\u4E2D";
        byte[] pdf;
        float advance;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                advance = c.text(text, 72, 72, style);
            }
            pdf = save(out);
        }
        assertEquals(style.width(text), advance, 1e-3);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals(text.replace(" ", ""), text(glyphs));
        assertEquals(72 + style.width("\u7B2C1"), glyphs.get(2).getXDirAdj(), 0.3);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int boxFonts = 0;
            for (COSName name : doc.getPage(0).getResources().getFontNames()) {
                PDFont f = doc.getPage(0).getResources().getFont(name);
                if (f instanceof PDType0Font t0 && t0.getDescendantFont().getCOSObject()
                        .getCOSStream(COSName.CID_TO_GID_MAP) != null) {
                    byte[] map;
                    try (InputStream in = t0.getDescendantFont().getCOSObject()
                            .getCOSStream(COSName.CID_TO_GID_MAP).createInputStream()) {
                        map = in.readAllBytes();
                    }
                    boolean zeros = true;
                    for (byte b : map) {
                        zeros &= b == 0;
                    }
                    if (zeros) {
                        assertEquals(2 * 5, map.length);
                        boxFonts++;
                    }
                }
            }
            assertEquals(1, boxFonts);
        }
    }

    @Test
    void aSymbolBulletDrawnWithALookAlikeCoversTheSymbolFontsBullet() throws Exception {
        TextStyle style = TextStyle.of(fonts.find("Symbol", false, false), 100).color(Color.BLACK);
        TextStyle after = TextStyle.of(fonts.find("Arial", false, false), 100);
        byte[] pdf;
        float advance;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(300, 200)) {
                advance = c.text("", 20, 150, style);
                c.text("I", 20 + advance, 150, after);
            }
            pdf = save(out);
        }
        assertEquals(46, advance, 0.1);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals("•I", text(glyphs));
        assertEquals(20 + advance, glyphs.get(1).getXDirAdj(), 0.3);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1);
            int x0 = Integer.MAX_VALUE;
            int x1 = -1;
            int y0 = Integer.MAX_VALUE;
            int y1 = -1;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < 20 + advance; x++) {
                    if ((img.getRGB(x, y) & 0xFF) < 128) {
                        x0 = Math.min(x0, x);
                        x1 = Math.max(x1, x);
                        y0 = Math.min(y0, y);
                        y1 = Math.max(y1, y);
                    }
                }
            }
            assertEquals(20 + 5.2, x0, 1.5);
            assertEquals(20 + 40.9, x1, 1.5);
            assertEquals(150 - 46, y0, 1.5);
            assertEquals(150 - 10.3, y1, 1.5);
        }
    }

    @Test
    void aStandInForAMissingOfficeFontSitsWhereTheOfficeFontsGlyphsWould() throws Exception {
        Path dejavu = Files.createDirectories(dir.resolve("dejavu"));
        Files.write(dejavu.resolve("DejaVuSans.ttf"), TestFonts.renamed("DejaVu Sans"));
        FontLibrary lib = FontLibrary.of(List.of(dejavu));
        FontFace verdana = lib.find("Verdana", false, false);
        assertTrue(verdana.glyphStretch() != 1);
        TextStyle style = TextStyle.of(verdana, 10).charSpacing(0.5f);
        byte[] pdf;
        float advance;
        try (PdfOutput out = new PdfOutput(lib)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                advance = c.text("Wide Words", 72, 72, style);
            }
            pdf = save(out);
        }
        assertEquals(style.width("Wide Words"), advance, 1e-3);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals("WideWords", text(glyphs));
        assertEquals(72 + style.width("Wide "), glyphs.get(4).getXDirAdj(), 0.2);
        assertEquals(72 + style.width("Wide Word"), glyphs.get(8).getXDirAdj(), 0.2);
        for (int i : new int[] {0, 1, 4, 8}) {
            int cp = "WideWords".charAt(i);
            assertEquals(verdana.advance(cp) * 10f / verdana.unitsPerEm(), glyphs.get(i).getWidthDirAdj(), 0.05,
                    "glyph " + (char) cp + " fills the Office font's advance");
        }
    }

    @Test
    void aStretchedFallbackDrawsItsShapedGlyphsWhereTheRunPlacesThem() throws Exception {
        FontFace hebrew = fonts.fallback(0x05D0, fonts.find("Times New Roman", false, false));
        assertTrue(hebrew.glyphStretch() < 0.95f, "" + hebrew.glyphStretch());
        GlyphRun run = hebrew.shape("שלום", true);
        TextStyle style = TextStyle.of(hebrew, 20);
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(300, 100)) {
                c.drawGlyphs(run, 50, 60, style);
            }
            pdf = save(out);
        }
        List<TextPosition> glyphs = positions(pdf);
        assertEquals(4, glyphs.size());
        float left = Float.MAX_VALUE;
        float right = 0;
        for (TextPosition t : glyphs) {
            left = Math.min(left, t.getXDirAdj());
            right = Math.max(right, t.getXDirAdj() + t.getWidthDirAdj());
        }
        assertEquals(50, left, 0.3);
        assertEquals(50 + run.width(20), right, 0.3);
    }

    @Test
    void aFaceWithAnEmptyMissingGlyphStillShowsTheBox() throws Exception {
        Path hollow = Files.createDirectories(dir.resolve("hollow"));
        Files.write(hollow.resolve("Hollow.ttf"), emptyNotdef(TestFonts.renamed("Hollow Serif")));
        FontLibrary lib = FontLibrary.of(List.of(hollow));
        FontFace face = lib.find("Hollow Serif", false, false);
        assertFalse(face.notdefVisible());
        assertTrue(fonts.find("MS Mincho", false, false).notdefVisible());
        TextStyle style = TextStyle.of(face, 40);
        byte[] pdf;
        float advance;
        try (PdfOutput out = new PdfOutput(lib)) {
            try (PdfCanvas c = out.newPage(200, 100)) {
                advance = c.text("A\u4E2D", 20, 70, style);
            }
            pdf = save(out);
        }
        assertEquals(style.width("A\u4E2D"), advance, 1e-3);
        float box = 20 + style.width("A");
        assertTrue(ink(pdf, Math.round(box), Math.round(box + 30)) > 40);
    }

    private static byte[] emptyNotdef(byte[] ttf) throws IOException {
        try (TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(ttf))) {
            long loca = font.getTableMap().get("loca").getOffset();
            ByteBuffer b = ByteBuffer.wrap(ttf);
            if (font.getHeader().getIndexToLocFormat() == 0) {
                b.putShort((int) loca + 2, b.getShort((int) loca));
            } else {
                b.putInt((int) loca + 4, b.getInt((int) loca));
            }
        }
        return ttf;
    }

    private static int ink(byte[] pdf, int x0, int x1) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1);
            int ink = 0;
            for (int y = 20; y < 80; y++) {
                for (int x = x0; x < x1; x++) {
                    ink += (img.getRGB(x, y) & 0xFF) < 128 ? 1 : 0;
                }
            }
            return ink;
        }
    }

    @Test
    void theMissingGlyphBoxIsInkOnThePage() throws Exception {
        TextStyle style = TextStyle.of(fonts.find("MS Mincho", false, false), 40);
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(200, 100)) {
                c.text("\u4E2D", 20, 70, style);
            }
            pdf = save(out);
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1);
            int ink = 0;
            for (int y = 20; y < 80; y++) {
                for (int x = 15; x < 65; x++) {
                    ink += (img.getRGB(x, y) & 0xFF) < 128 ? 1 : 0;
                }
            }
            assertTrue(ink > 40, "ink pixels: " + ink);
        }
    }

    @Test
    void aStandInLighterThanItsFamilyIsOutlinedWithTheMissingWeight() throws Exception {
        FontFace black = fonts.find("Arial Black", false, false);
        assertTrue(black.syntheticBold() && black.embolden() > 0.02f);
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(300, 100)) {
                c.text("Heavy", 20, 60, TextStyle.of(black, 20));
                c.drawGlyphs(black.shape("Heavy", false), 20, 90, TextStyle.of(black, 20));
                c.text("Plain", 150, 60, TextStyle.of(fonts.find("Arial", false, false), 20));
            }
            pdf = save(out);
        }
        String content = content(pdf);
        Matcher widths = Pattern.compile("([0-9.]+) w\\s").matcher(content);
        List<Float> seen = new ArrayList<>();
        while (widths.find()) {
            seen.add(Float.parseFloat(widths.group(1)));
        }
        float expected = 20 * (PdfCanvas.BOLD_STROKE + black.embolden());
        assertEquals(2, seen.size(), content);
        for (float w : seen) {
            assertEquals(expected, w, 1e-3);
        }
        assertEquals("HeavyHeavyPlain", text(positions(pdf)));
    }

    private static List<TextPosition> letters(List<TextPosition> all) {
        List<TextPosition> out = new ArrayList<>();
        for (TextPosition t : all) {
            int cp = t.getUnicode().codePointAt(0);
            if (Character.isLetter(cp) && cp < 0x3000) {
                out.add(t);
            }
        }
        return out;
    }

    private static int missingGlyphs(String content) {
        int n = 0;
        Matcher m = Pattern.compile("<([0-9A-Fa-f]+)>").matcher(content);
        while (m.find()) {
            String hex = m.group(1);
            for (int i = 0; i + 4 <= hex.length(); i += 4) {
                n += hex.startsWith("0000", i) ? 1 : 0;
            }
        }
        return n;
    }

    private static String content(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void shapesImagesAndFormsLandTheRightWayUp() throws Exception {
        BufferedImage half = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = half.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 20, 10);
        g.setColor(Color.BLUE);
        g.fillRect(0, 10, 20, 10);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(half, "png", png);
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(200, 400)) {
                c.rect(0, 0, 20, 20, Fill.solid(Color.GREEN), null);
                DecodedPicture pic = PictureDecoder.decode(out.document(), png.toByteArray());
                c.image(pic, 100, 100, 50, 50);
                c.image(pic, 100, 200, 50, 50, Crop.fractions(0, 0.5f, 0, 0), 0, false, false, 1);
                c.image(pic, 100, 300, 50, 50, Crop.NONE, 180, false, false, 1);
                PdfBoxGraphics2D g2 = new PdfBoxGraphics2D(out.document(), 40, 40);
                g2.setColor(Color.RED);
                g2.fillRect(0, 0, 40, 20);
                g2.dispose();
                c.form(g2.getXFormObject(), 20, 100, 40, 40);
            }
            pdf = save(out);
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            assertColor(Color.GREEN, img, 10, 10);
            assertColor(Color.WHITE, img, 10, 390);
            assertColor(Color.RED, img, 125, 110);
            assertColor(Color.BLUE, img, 125, 140);
            assertColor(Color.BLUE, img, 125, 210);
            assertColor(Color.BLUE, img, 125, 240);
            assertColor(Color.BLUE, img, 125, 310);
            assertColor(Color.RED, img, 125, 340);
            assertColor(Color.RED, img, 40, 105);
            assertColor(Color.WHITE, img, 40, 135);
        }
    }

    @Test
    void transformsGradientsPathsAndClipping() throws Exception {
        Gradient linear = Gradient.linear(0, 0, 100, 0, List.of(new Gradient.Stop(0, Color.RED),
                new Gradient.Stop(0.5f, Color.GREEN), new Gradient.Stop(1, Color.BLUE)));
        Gradient radial = Gradient.radial(50, 50, 40, List.of(new Gradient.Stop(0, Color.WHITE),
                new Gradient.Stop(1, Color.BLACK)));
        Path2D star = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        star.moveTo(10, 0);
        star.lineTo(20, 30);
        star.quadTo(0, 10, 20, 10);
        star.curveTo(0, 30, 10, 0, 10, 0);
        star.closePath();
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(300, 300)) {
                c.rect(0, 0, 100, 20, Fill.of(linear), Stroke.solid(1, Color.BLACK).dash(0, 3, 2));
                c.save();
                c.translate(100, 100);
                c.rotate(45, 50, 50);
                c.clipRect(0, 0, 100, 100);
                c.ellipse(10, 10, 80, 80, Fill.of(radial), null);
                c.draw(star, Fill.solid(new Color(0, 0, 255, 100)), Stroke.solid(2, Color.RED).cap(Stroke.Cap.ROUND)
                        .join(Stroke.Join.BEVEL));
                c.restore();
                c.roundRect(10, 200, 100, 50, 8, 8, null, Stroke.solid(0.5f, Color.GRAY));
                c.line(0, 299, 300, 299, Stroke.solid(1, Color.BLACK));
                c.underline(10, 150, 50, TextStyle.of(fonts.find("Arial", false, false), 12));
                c.strikeout(10, 150, 50, TextStyle.of(fonts.find("Arial", false, false), 12));
                assertThrows(IllegalStateException.class, c::restore);
            }
            pdf = save(out);
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage page = doc.getPage(0);
            assertTrue(page.getResources().getShadingNames().iterator().hasNext()
                    || page.getResources().getPatternNames().iterator().hasNext()
                    || String.valueOf(page.getResources().getCOSObject()).contains("Shading"));
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f);
            Color left = new Color(img.getRGB(3, 10));
            Color right = new Color(img.getRGB(96, 10));
            assertTrue(left.getRed() > 200 && left.getBlue() < 60, left.toString());
            assertTrue(right.getBlue() > 200 && right.getRed() < 60, right.toString());
        }
    }

    @Test
    void linksAreSafeAndAnnotationsUseThePageSpace() throws Exception {
        byte[] pdf;
        try (NoNetwork net = NoNetwork.start(); PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                assertTrue(c.link(72, 90, 100, 14, "https://example.com/a b"));
                assertTrue(c.link(72, 120, 100, 14, net.url("never-fetched")));
                assertFalse(c.link(72, 150, 100, 14, "javascript:alert(1)"));
                assertFalse(c.link(72, 150, 100, 14, "file:///C:/Windows/win.ini"));
                assertFalse(c.link(72, 150, 100, 14, "\\\\server\\share\\x"));
                assertFalse(c.link(72, 150, 100, 14, "launch:calc.exe"));
                assertFalse(c.link(72, 150, 100, 14, null));
                c.linkTo(72, 200, 50, 10, "target");
                c.linkToPage(72, 220, 50, 10, 1, 300);
                c.linkTo(72, 240, 50, 10, "missing");
            }
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                c.destination("target", 0, 400);
            }
            out.outline("Chapter", 1, "target");
            out.outline("Section", 2, 0, 100);
            out.info(DocumentInfo.EMPTY.title("Title\u0000here").author("Me"));
            pdf = save(out);
            net.assertNothingConnected();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            List<PDAnnotation> annots = doc.getPage(0).getAnnotations();
            assertEquals(4, annots.size());
            PDAnnotationLink first = (PDAnnotationLink) annots.get(0);
            assertEquals("https://example.com/a%20b", ((PDActionURI) first.getAction()).getURI());
            assertEquals(72, first.getRectangle().getLowerLeftX(), 0.01);
            assertEquals(doc.getPage(0).getMediaBox().getHeight() - 104, first.getRectangle().getLowerLeftY(), 0.01);
            PDAnnotationLink internal = (PDAnnotationLink) annots.get(2);
            PDPageXYZDestination dest = assertInstanceOf(PDPageXYZDestination.class, internal.getDestination());
            assertEquals(1, doc.getPages().indexOf(dest.getPage()));
            assertEquals(Math.round(doc.getPage(1).getMediaBox().getHeight() - 400), dest.getTop());
            assertEquals("Stirling Office Convert", doc.getDocumentInformation().getCreator());
            assertEquals("Title here", doc.getDocumentInformation().getTitle());
            assertNotNull(doc.getDocumentCatalog().getDocumentOutline());
            assertEquals("Chapter", doc.getDocumentCatalog().getDocumentOutline().getFirstChild().getTitle());
        }
    }

    @Test
    void refusesUseAfterClose() throws Exception {
        try (PdfOutput out = new PdfOutput(fonts)) {
            PdfCanvas c = out.newPage(PageSize.A5);
            c.close();
            assertThrows(IllegalStateException.class, () -> c.rect(0, 0, 1, 1, Fill.solid(Color.BLACK), null));
            assertThrows(IllegalArgumentException.class, () -> out.newPage(0, 10));
            save(out);
            assertThrows(IllegalStateException.class, () -> out.newPage(PageSize.A4));
        }
    }

    private static void assertColor(Color expected, BufferedImage img, int x, int y) {
        Color got = new Color(img.getRGB(x, y));
        int diff = Math.abs(got.getRed() - expected.getRed()) + Math.abs(got.getGreen() - expected.getGreen())
                + Math.abs(got.getBlue() - expected.getBlue());
        assertTrue(diff < 60, "at " + x + "," + y + " expected " + expected + " but was " + got);
    }

    private static String text(List<TextPosition> glyphs) {
        StringBuilder sb = new StringBuilder();
        for (TextPosition t : glyphs) {
            if (!t.getUnicode().isBlank()) {
                sb.append(t.getUnicode());
            }
        }
        return sb.toString();
    }

    @Test
    void rightToLeftWordsDrawnLeftToRightReachThePageInReadingOrder() throws Exception {
        FontFace face = fonts.find("Arial", false, false);
        TextStyle style = TextStyle.of(face, 12);
        byte[] pdf;
        float drawn;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                float x = 72;
                x += c.drawGlyphs(face.shape("עולם", true), x, 100, style);
                x += c.text(" ", x, 100, style);
                x += c.drawGlyphs(face.shape("שלום", true), x, 100, style);
                drawn = x;
                c.rect(72, 110, 10, 1, Fill.solid(Color.BLACK), null);
                c.text("Latin", 72, 130, style);
                c.text("line", 72 + style.width("Latin "), 130, style);
            }
            pdf = save(out);
        }
        String order = streamOrder(pdf);
        assertTrue(order.indexOf('ש') < order.indexOf('ע'), "the right word comes first: " + order);
        assertTrue(order.endsWith("Latinline"), order);
        List<TextPosition> glyphs = positions(pdf);
        assertEquals(72 + style.width("עולם "), glyphs.stream()
                .filter(t -> t.getUnicode().equals("ם") && t.getYDirAdj() < 105)
                .mapToDouble(TextPosition::getXDirAdj).max().orElse(0), 0.5, "positions stay where they were drawn");
        assertTrue(drawn > 100);
    }

    @Test
    void aCharacterTheFontLacksIsDrawnFromAnInstalledFontThatHasIt() throws Exception {
        FontLibrary system = FontLibrary.system();
        FontFace face = system.find("Liberation Sans", false, false);
        assumeTrue(face != null && !face.covers(0x4E2D), "Liberation Sans is not installed here");
        FontFace cover = system.fallback(0x4E2D, face);
        assumeTrue(cover != null && cover.covers(0x4E2D), "no installed font has U+4E2D");
        TextStyle style = TextStyle.of(face, 12);
        byte[] pdf;
        float width;
        try (PdfOutput out = new PdfOutput(system)) {
            try (PdfCanvas c = out.newPage(PageSize.A4)) {
                width = c.text("A中B", 72, 100, style);
            }
            pdf = save(out);
        }
        assertEquals(style.width("A中B"), width, 0.001f, "the space measured for the character is kept");
        List<TextPosition> glyphs = positions(pdf);
        assertEquals("A中B", text(glyphs));
        assertEquals(72 + style.width("A中"), glyphs.get(2).getXDirAdj(), 0.3);
        assertFalse(glyphs.get(1).getFont().getName().contains("Liberation"), glyphs.get(1).getFont().getName());
        assertEquals(0, missingGlyphs(content(pdf)), "no missing-glyph box");
    }

    private static String streamOrder(byte[] pdf) throws IOException {
        StringBuilder out = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    if (!text.getUnicode().isBlank()) {
                        out.append(text.getUnicode());
                    }
                }
            };
            stripper.getText(doc);
        }
        return out.toString();
    }

    private static List<TextPosition> positions(byte[] pdf) throws IOException {
        List<TextPosition> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> textPositions) {
                    for (TextPosition t : textPositions) {
                        if (!t.getUnicode().isBlank()) {
                            out.add(t);
                        }
                    }
                }
            };
            stripper.getText(doc);
        }
        return out;
    }

    @Test
    void subsetsAndEmbedsEveryFontItDraws() throws Exception {
        byte[] pdf;
        try (PdfOutput out = new PdfOutput(fonts)) {
            try (PdfCanvas page = out.newPage(PageSize.A4)) {
                page.text("Subset me", 72, 100, TextStyle.of(fonts.find("Liberation Sans", false, false), 12));
            }
            pdf = save(out);
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            var font = doc.getPage(0).getResources().getFont(org.apache.pdfbox.cos.COSName.getPDFName("F1"));
            assertTrue(font.isEmbedded(), font.getName());
            assertTrue(font.getName().matches("[A-Z]{6}[+].*"), font.getName());
            assertEquals("Subset me", new PDFTextStripper().getText(doc).strip());
        }
    }

    @Test
    void aDamagedFontFallsBackInsteadOfFailingTheSave() throws Exception {
        FontLibrary lib = fonts.withFonts(List.of(TestFonts.damagedGlyph("Broken Glyphs", 'Q')));
        FontFace broken = lib.find("Broken Glyphs", false, false);
        assertEquals("Broken Glyphs", broken.family());
        byte[] pdf;
        List<String> warnings;
        try (PdfOutput out = new PdfOutput(lib)) {
            try (PdfCanvas page = out.newPage(PageSize.A4)) {
                page.text("Quick Q test", 72, 100, TextStyle.of(broken, 12));
                page.text("Fine text", 72, 130, TextStyle.of(lib.find("Liberation Sans", false, false), 12));
            }
            pdf = save(out);
            warnings = out.fonts().substitutions();
        }
        assertTrue(warnings.stream().anyMatch(w -> w.contains("could not be subset") && w.contains("embedded whole")),
                warnings.toString());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Quick Q test") && text.contains("Fine text"), text);
            assertEquals(2, doc.getPage(0).getResources().getFontNames().spliterator().getExactSizeIfKnown());
        }
    }
}
