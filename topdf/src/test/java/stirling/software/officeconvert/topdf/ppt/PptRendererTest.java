package stirling.software.officeconvert.topdf.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFObjectShape;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.ShapeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.testing.Emf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.HostileImagePlugin;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class PptRendererTest {

    @TempDir
    Path dir;

    private record Converted(Path pdf, OfficeToPdf.Result result) {

        PDDocument open() throws IOException {
            return Loader.loadPDF(pdf.toFile());
        }

        String text() throws IOException {
            try (PDDocument d = open()) {
                return new PDFTextStripper().getText(d);
            }
        }

        BufferedImage render(int page) throws IOException {
            try (PDDocument d = open()) {
                return new PDFRenderer(d).renderImageWithDPI(page, 72);
            }
        }

        List<TextPosition> positions(int page) throws IOException {
            List<TextPosition> out = new ArrayList<>();
            try (PDDocument d = open()) {
                PDFTextStripper s = new PDFTextStripper() {
                    @Override
                    protected void processTextPosition(TextPosition text) {
                        out.add(text);
                    }
                };
                s.setStartPage(page + 1);
                s.setEndPage(page + 1);
                s.getText(d);
            }
            return out;
        }
    }

    private static byte[] deck(Consumer<HSLFSlideShow> build) {
        try (HSLFSlideShow ppt = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(ppt);
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HSLFTextBox text(HSLFSlide slide, String text, double x, double y, double w, double h) {
        HSLFTextBox box = slide.createTextBox();
        box.setText(text);
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        return box;
    }

    private static void rect(HSLFSlide slide, Color fill, double x, double y, double w, double h) {
        HSLFAutoShape shape = new HSLFAutoShape(ShapeType.RECT);
        shape.setFillColor(fill);
        shape.setLineColor(null);
        shape.setAnchor(new Rectangle2D.Double(x, y, w, h));
        slide.addShape(shape);
    }

    private static HSLFPictureData picture(HSLFSlideShow ppt, byte[] data, PictureType type) {
        try {
            return ppt.addPicture(data, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Converted convert(String name, byte[] data) throws IOException {
        return convert(name, data, Options.defaults().timeout(Duration.ofMinutes(2)));
    }

    private Converted convert(String name, byte[] data, Options options) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        return new Converted(out, OfficeToPdf.convert(in, out, options));
    }

    @Test
    void extensionsMapToTheLegacyFormat() {
        for (String name : List.of("a.ppt", "b.PPS", "c.pot")) {
            assertEquals(Format.PPT, Format.of(Path.of(name)), name);
        }
        assertEquals(Format.DOCX, Format.of(Path.of("x.doc")));
    }

    @Test
    void slidesDrawTextShapesAndPictures() throws IOException {
        byte[] ppt = deck(p -> {
            HSLFSlide one = p.createSlide();
            rect(one, Color.RED, 400, 300, 200, 150);
            text(one, "Hello legacy slides", 60, 60, 500, 60);
            HSLFSlide two = p.createSlide();
            HSLFPictureShape pic = two.createPicture(picture(p, Fixtures.png(20, 20, Color.BLUE), PictureType.PNG));
            pic.setAnchor(new Rectangle2D.Double(100, 100, 200, 200));
            text(two, "Second slide", 60, 400, 400, 60);
        });
        Converted c = convert("deck.ppt", ppt);
        assertEquals(2, c.result().pages());
        assertFalse(c.result().truncated(), c.result().warnings().toString());
        String text = c.text();
        assertTrue(text.contains("Hello legacy slides") && text.contains("Second slide"), text);
        try (PDDocument d = c.open()) {
            PDPage page = d.getPage(0);
            assertEquals(720, page.getMediaBox().getWidth(), 0.5);
            assertEquals(540, page.getMediaBox().getHeight(), 0.5);
        }
        assertEquals(Color.RED.getRGB(), c.render(0).getRGB(500, 375));
        assertEquals(Color.BLUE.getRGB(), c.render(1).getRGB(200, 200));
        List<TextPosition> glyphs = c.positions(0);
        StringBuilder dump = new StringBuilder();
        for (TextPosition t : glyphs) {
            dump.append(t.getUnicode()).append('@').append(t.getX()).append(',').append(t.getY()).append(' ');
        }
        assertTrue(glyphs.get(0).getX() > 60 && glyphs.get(0).getX() < 80, dump.toString());
        assertTrue(glyphs.get(0).getY() > 60 && glyphs.get(0).getY() < 100, "y " + glyphs.get(0).getY());
        for (int i = 1; i < glyphs.size(); i++) {
            assertTrue(glyphs.get(i).getX() > glyphs.get(i - 1).getX(), "glyphs run left to right");
        }
    }

    @Test
    void textIsWrittenWithEmbeddedFontsNotOutlines() throws IOException {
        Converted c = convert("fonts.ppt", deck(p -> text(p.createSlide(), "Embedded font text", 60, 60, 400, 60)
                .getTextParagraphs().get(0).getTextRuns().get(0).setFontSize(18d)));
        try (PDDocument d = c.open()) {
            assertTrue(new PDFTextStripper().getText(d).contains("Embedded font text"));
        }
        List<TextPosition> glyphs = c.positions(0);
        assertTrue(glyphs.get(0).getFont().isEmbedded(), glyphs.get(0).getFont().getName());
        assertEquals(18, glyphs.get(0).getFontSizeInPt(), 0.6);
    }

    @Test
    void wideTextWrapsInsideItsBox() throws IOException {
        String words = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen";
        Converted c = convert("wrap.ppt", deck(p -> text(p.createSlide(), words, 100, 100, 200, 300)));
        float right = 0;
        float top = Float.MAX_VALUE;
        float bottom = 0;
        for (TextPosition t : c.positions(0)) {
            right = Math.max(right, t.getX() + t.getWidth());
            top = Math.min(top, t.getY());
            bottom = Math.max(bottom, t.getY());
        }
        assertTrue(right <= 302, "right edge " + right);
        assertTrue(bottom - top > 60, "wrapped over several lines: " + top + ".." + bottom);
    }

    @Test
    void hiddenSlidesAreLeftOutAndThePageLimitHolds() throws IOException {
        byte[] ppt = deck(p -> {
            text(p.createSlide(), "Shown one", 60, 60, 400, 60);
            HSLFSlide hidden = p.createSlide();
            text(hidden, "Hidden slide", 60, 60, 400, 60);
            hidden.setHidden(true);
            text(p.createSlide(), "Shown two", 60, 60, 400, 60);
            text(p.createSlide(), "Shown three", 60, 60, 400, 60);
        });
        Converted all = convert("hidden.ppt", ppt);
        assertEquals(3, all.result().pages());
        assertFalse(all.text().contains("Hidden slide"));
        Converted cut = convert("cut.ppt", ppt, Options.defaults().maxPages(2));
        assertEquals(2, cut.result().pages());
        assertTrue(cut.result().pageLimitReached() && cut.result().truncated());
    }

    @Test
    void aShapePoiCannotDrawLosesOnlyItself() throws IOException {
        Converted c = convert("broken.ppt", deck(p -> {
            HSLFSlide slide = p.createSlide();
            rect(slide, Color.RED, 400, 300, 200, 150);
            HSLFAutoShape broken = new HSLFAutoShape(ShapeType.RECT);
            broken.setAnchor(new Rectangle2D.Double(50, 300, 200, 150));
            for (EscherPropertyTypes t : List.of(EscherPropertyTypes.FILL__FILLTYPE,
                    EscherPropertyTypes.LINESTYLE__LINEWIDTH, EscherPropertyTypes.LINESTYLE__NOLINEDRAWDASH)) {
                broken.getEscherOptRecord().addEscherProperty(new EscherComplexProperty(t, false, 8));
            }
            slide.addShape(broken);
            text(slide, "Survivor", 60, 60, 400, 60);
        }));
        assertEquals(Color.RED.getRGB(), c.render(0).getRGB(500, 375));
        assertTrue(c.text().contains("Survivor"), c.text());
        assertTrue(c.result().truncated());
        assertTrue(c.result().warnings().stream().anyMatch(w -> w.startsWith("Left out 1 part of slide 1")),
                c.result().warnings().toString());
    }

    @Test
    void wordArtKeepsItsTextInsteadOfAFilledOutline() throws IOException {
        Converted c = convert("wordart.ppt", deck(p -> {
            HSLFAutoShape art = new HSLFAutoShape(ShapeType.TEXT_DEFLATE);
            art.setAnchor(new Rectangle2D.Double(60, 60, 280, 110));
            art.setFillColor(Color.BLACK);
            byte[] data = "First line\nThird line\0".getBytes(StandardCharsets.UTF_16LE);
            EscherComplexProperty text = new EscherComplexProperty(EscherPropertyTypes.GEOTEXT__UNICODE, false,
                    data.length);
            text.setComplexData(data);
            art.getEscherOptRecord().addEscherProperty(text);
            p.createSlide().addShape(art);
        }));
        assertTrue(c.text().contains("First line"), c.text());
        assertTrue(c.text().contains("Third line"), c.text());
        BufferedImage page = c.render(0);
        int dark = 0;
        for (int x = 60; x < 340; x += 2) {
            for (int y = 60; y < 170; y += 2) {
                dark += (page.getRGB(x, y) & 0xff) < 128 ? 1 : 0;
            }
        }
        assertTrue(dark < 0.5 * 140 * 55, "the outline was filled: " + dark);
    }

    @Test
    void wideSlidesKeepTheirSize() throws IOException {
        Converted c = convert("wide.ppt", deck(p -> {
            p.setPageSize(new Dimension(960, 540));
            text(p.createSlide(), "Wide", 60, 60, 400, 60);
        }));
        try (PDDocument d = c.open()) {
            assertEquals(960, d.getPage(0).getMediaBox().getWidth(), 0.5);
        }
    }

    @Test
    void activeContentIsSkippedWithoutTouchingTheNetwork() throws IOException {
        try (NoNetwork net = NoNetwork.start(); HostileImagePlugin plugin = HostileImagePlugin.register()) {
            byte[] ppt = deck(p -> {
                HSLFSlide slide = p.createSlide();
                HSLFTextBox box = text(slide, "Linked words", 60, 60, 400, 60);
                HSLFTextRun run = box.getTextParagraphs().get(0).getTextRuns().get(0);
                run.createHyperlink().linkToUrl(net.url("clicked"));
                HSLFPictureData preview = picture(p, Fixtures.png(8, 8, Color.RED), PictureType.PNG);
                try (POIFSFileSystem embedded = new POIFSFileSystem()) {
                    embedded.createDocument(new ByteArrayInputStream(new byte[] {1, 2, 3}), "Workbook");
                    HSLFObjectShape ole = new HSLFObjectShape(preview);
                    ole.setObjectID(p.addEmbed(embedded));
                    ole.setAnchor(new Rectangle2D.Double(400, 250, 200, 150));
                    slide.addShape(ole);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                for (String target : net.hostileTargets("pic")) {
                    text(slide, target, 60, 460, 600, 30).getTextParagraphs().get(0).getTextRuns().get(0)
                            .createHyperlink().linkToUrl(target);
                }
            });
            Converted c = convert("hostile.ppt", ppt);
            net.assertNothingConnected();
            plugin.assertNeverUsed();
            assertTrue(c.text().contains("Linked words"));
            assertEquals(Color.RED.getRGB(), c.render(0).getRGB(500, 325), "the OLE preview is drawn");
            assertTrue(c.result().warnings().stream().anyMatch(w -> w.contains("embedded OLE objects")),
                    c.result().warnings().toString());
        }
    }

    @Test
    void metafilePicturesThatUnpackTooFarAreLeftOut() throws IOException {
        byte[] emf = Emf.of(100, 100).bytes();
        byte[] bomb = Arrays.copyOf(emf, 40 << 20);
        byte[] ppt = deck(p -> {
            HSLFSlide slide = p.createSlide();
            slide.createPicture(picture(p, bomb, PictureType.EMF)).setAnchor(new Rectangle2D.Double(10, 10, 100, 100));
            slide.createPicture(picture(p, new byte[40 << 20], PictureType.PICT))
                    .setAnchor(new Rectangle2D.Double(200, 10, 100, 100));
            text(slide, "Still here", 60, 300, 400, 60);
        });
        assertTrue(ppt.length < 1 << 20, "the bomb compresses: " + ppt.length);
        Converted c = convert("bomb.ppt", ppt);
        assertTrue(c.text().contains("Still here"));
        assertTrue(c.result().warnings().stream().anyMatch(w -> w.contains("Left out 2 pictures")),
                c.result().warnings().toString());
    }

    @Test
    void macPictPicturesAreDrawn() throws IOException {
        ByteBuffer pict = ByteBuffer.allocate(512 + 60);
        pict.position(512);
        pict.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 20).putShort((short) 20);
        pict.putShort((short) 0x0011).putShort((short) 0x02FF).putShort((short) 0x0C00).putShort((short) -1);
        pict.put(new byte[22]);
        pict.putShort((short) 0x001A).putShort((short) -1).putShort((short) 0).putShort((short) 0);
        pict.putShort((short) 0x0031).putShort((short) 0).putShort((short) 0).putShort((short) 20).putShort((short) 20);
        pict.putShort((short) 0x00FF);
        byte[] ppt = deck(p -> p.createSlide().createPicture(picture(p, pict.array(), PictureType.PICT))
                .setAnchor(new Rectangle2D.Double(100, 100, 200, 100)));
        Converted c = convert("pict.ppt", ppt);
        assertEquals(Color.RED.getRGB(), c.render(0).getRGB(200, 150), c.result().warnings().toString());
    }

    @Test
    void passwordProtectedPresentationsSayWhy() throws IOException {
        byte[] plain = deck(p -> text(p.createSlide(), "Secret", 60, 60, 400, 60));
        byte[] locked;
        Biff8EncryptionKey.setCurrentUserPassword("secret");
        try (HSLFSlideShow ppt = new HSLFSlideShow(new ByteArrayInputStream(plain));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.write(out);
            locked = out.toByteArray();
        } finally {
            Biff8EncryptionKey.setCurrentUserPassword(null);
        }
        Path in = Fixtures.write(dir, "locked.ppt", locked);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("locked.pdf")));
        assertTrue(e.getMessage().contains("password"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("locked.pdf")));
    }

    @Test
    void theContentDecidesNotTheExtension() throws IOException {
        byte[] ppt = deck(p -> text(p.createSlide(), "Binary inside", 60, 60, 400, 60));
        assertTrue(convert("renamed.pptx", ppt).text().contains("Binary inside"));
        assertTrue(convert("really.ppt", Fixtures.pptx("Zip inside")).text().contains("Zip inside"));
        IOException e = assertThrows(IOException.class, () -> convert("junk.ppt", "not a deck".getBytes()));
        assertTrue(e.getMessage().contains("not a zip"), e.getMessage());
    }

    @Test
    void theStreamVariantAndTheMemoryEstimateTakeLegacyFiles() throws IOException {
        byte[] ppt = deck(p -> text(p.createSlide(), "Streamed", 60, 60, 400, 60));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.convert(new ByteArrayInputStream(ppt), Format.PPT, out, Options.defaults());
        assertEquals(1, r.pages());
        try (PDDocument d = Loader.loadPDF(out.toByteArray())) {
            assertTrue(new PDFTextStripper().getText(d).contains("Streamed"));
        }
        long estimate = OfficeToPdf.memoryEstimate(Fixtures.write(dir, "size.ppt", ppt));
        assertTrue(estimate > ppt.length, "estimate " + estimate);
        OfficeToPdf.warmUp(Format.PPT);
    }

    @Test
    void worldScriptsAreDrawnAsTextInReadingOrder() throws IOException {
        String arabic = "\u0645\u0631\u062D\u0628\u0627";
        String hebrew = "\u05E9\u05DC\u05D5\u05DD";
        String hindi = "\u0928\u092E\u0938\u094D\u0924\u0947";
        String cjk = "\u4F60\u597D\u4E16\u754C";
        Converted c = convert("world.ppt", deck(p -> {
            HSLFSlide slide = p.createSlide();
            text(slide, arabic, 60, 60, 400, 60);
            text(slide, hebrew, 60, 140, 400, 60);
            text(slide, hindi, 60, 220, 400, 60);
            text(slide, cjk, 60, 300, 400, 60);
            text(slide, "Greek \u0391\u03B8\u03AE\u03BD\u03B1 Cyrillic \u041C\u043E\u0441\u043A\u0432\u0430", 60, 380, 600,
                    60);
        }));
        String text = c.text().replaceAll("\\s+", "");
        for (String s : List.of(cjk, "\u0391\u03B8\u03AE\u03BD\u03B1", "\u041C\u043E\u0441\u043A\u0432\u0430")) {
            assertTrue(text.contains(s), s + " in " + text);
        }
        float first = 0;
        float last = 0;
        for (TextPosition t : c.positions(0)) {
            first = t.getUnicode().equals("\u05E9") ? t.getX() : first;
            last = t.getUnicode().equals("\u05DD") ? t.getX() : last;
        }
        assertTrue(first > last && last > 0, "the first Hebrew letter is on the right: " + first + " " + last);
        assertTrue(text.contains(arabic) || text.contains(new StringBuilder(arabic).reverse().toString()), text);
        assertTrue(text.contains("\u0928\u092E"), text);
    }
}
