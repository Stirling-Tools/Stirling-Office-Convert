package stirling.software.officeconvert.topdf.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hslf.record.SlideAtomLayout;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class PptSlideFixesTest {

    @TempDir
    Path dir;

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

    private Path convert(String name, byte[] data) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return out;
    }

    private static List<TextPosition> positions(Path pdf) throws IOException {
        List<TextPosition> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    out.add(text);
                }
            };
            s.setStartPage(1);
            s.setEndPage(1);
            s.getText(d);
        }
        return out;
    }

    private static String pageText(Path pdf, int page) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(d);
        }
    }

    @Test
    void rightToLeftParagraphsAlignToTheRight() throws IOException {
        Path pdf = convert("rtl.ppt", deck(p -> {
            HSLFSlide slide = p.createSlide();
            HSLFTextBox box = text(slide, "Right side", 60, 100, 600, 60);
            box.getTextParagraphs().get(0).setParagraphTextPropVal("textDirection", 1);
            text(slide, "Left side", 60, 300, 600, 60);
        }));
        float rtlEnd = 0;
        float ltrStart = Float.MAX_VALUE;
        for (TextPosition t : positions(pdf)) {
            if (t.getY() < 200) {
                rtlEnd = Math.max(rtlEnd, t.getX() + t.getWidth());
            } else {
                ltrStart = Math.min(ltrStart, t.getX());
            }
        }
        assertTrue(rtlEnd > 620, "the right-to-left line ends at " + rtlEnd);
        assertTrue(ltrStart < 80, "the left-to-right line starts at " + ltrStart);
    }

    @Test
    void hyperlinksBecomeLinkAnnotationsAndUnsafeOnesAreDropped() throws IOException {
        try (NoNetwork net = NoNetwork.start()) {
            Path pdf = convert("links.ppt", deck(p -> {
                HSLFSlide one = p.createSlide();
                HSLFSlide two = p.createSlide();
                text(two, "Second", 60, 60, 300, 60);
                HSLFTextBox box = text(one, "Web", 60, 60, 300, 60);
                HSLFTextParagraph para = box.getTextParagraphs().get(0);
                para.getTextRuns().get(0).createHyperlink().linkToUrl("https://example.invalid/page");
                HSLFTextRun jump = text(one, "Jump", 60, 200, 300, 60).getTextParagraphs().get(0).getTextRuns()
                        .get(0);
                jump.createHyperlink().linkToSlide(two);
                text(one, "File", 60, 300, 300, 60).getTextParagraphs().get(0).getTextRuns().get(0)
                        .createHyperlink().linkToUrl(net.uncPath("share.txt"));
                HSLFAutoShape shape = new HSLFAutoShape(ShapeType.RECT);
                shape.setFillColor(Color.GREEN);
                shape.setAnchor(new Rectangle2D.Double(400, 300, 100, 80));
                one.addShape(shape);
                shape.createHyperlink().linkToNextSlide();
            }));
            List<String> uris = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            PDRectangle web = null;
            try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
                for (PDAnnotation a : d.getPage(0).getAnnotations()) {
                    if (a instanceof PDAnnotationLink link) {
                        if (link.getAction() instanceof PDActionURI uri) {
                            uris.add(uri.getURI());
                            web = link.getRectangle();
                        } else if (link.getAction() instanceof PDActionGoTo go
                                && go.getDestination() instanceof PDPageDestination dest) {
                            pages.add(d.getPages().indexOf(dest.getPage()));
                        } else if (link.getDestination() instanceof PDPageDestination dest) {
                            pages.add(d.getPages().indexOf(dest.getPage()));
                        }
                    }
                }
            }
            assertEquals(List.of("https://example.invalid/page"), uris);
            assertTrue(web.getLowerLeftX() > 55 && web.getLowerLeftX() < 90, "x " + web.getLowerLeftX());
            assertTrue(web.getUpperRightY() > 440 && web.getUpperRightY() < 485, "top " + web.getUpperRightY());
            assertTrue(web.getWidth() > 10 && web.getWidth() < 80, "width " + web.getWidth());
            assertEquals(List.of(1, 1), pages);
            net.assertNothingConnected();
        }
    }

    @Test
    void titleSlidesShowFootersUnlessTheDeckOmitsThem() throws IOException {
        byte[] shown = deck(p -> {
            HSLFSlide slide = p.createSlide();
            slide.getSlideRecord().getSlideAtom().getSSlideLayoutAtom()
                    .setGeometryType(SlideAtomLayout.SlideLayoutType.TITLE_SLIDE);
            text(slide, "Title", 60, 60, 400, 60);
            p.getSlideHeadersFooters().setFootersText("Deck footer");
            p.getSlideHeadersFooters().setFooterVisible(true);
            for (HSLFShape shape : slide.getMasterSheet().getShapes()) {
                if (shape instanceof HSLFTextShape t && t.getPlaceholder() == Placeholder.FOOTER) {
                    t.setText("Deck footer");
                }
            }
        });
        assertTrue(pageText(convert("footer.ppt", shown), 1).contains("Deck footer"));
        assertFalse(pageText(convert("omitted.ppt", omitTitlePlaceholders(shown)), 1).contains("Deck footer"));
    }

    @Test
    void footerPlaceholdersShowTheFooterTextAndSlideNumber() throws IOException {
        byte[] ppt = deck(p -> {
            for (int i = 0; i < 2; i++) {
                text(p.createSlide(), "Body " + i, 60, 60, 400, 60);
            }
            p.getSlideHeadersFooters().setFootersText("Deck footer");
            p.getSlideHeadersFooters().setFooterVisible(true);
            p.getSlideHeadersFooters().setSlideNumberVisible(true);
            for (HSLFShape shape : p.getSlides().get(0).getMasterSheet().getShapes()) {
                if (shape instanceof HSLFTextShape t && (t.getPlaceholder() == Placeholder.FOOTER
                        || t.getPlaceholder() == Placeholder.SLIDE_NUMBER)) {
                    t.setText("*");
                }
            }
        });
        Path pdf = convert("footers.ppt", ppt);
        for (int page = 1; page <= 2; page++) {
            String text = pageText(pdf, page);
            assertTrue(text.contains("Deck footer"), text);
            assertTrue(text.contains(String.valueOf(page)), text);
            assertFalse(text.contains("*"), text);
        }
    }

    private static byte[] omitTitlePlaceholders(byte[] ppt) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(ppt))) {
            byte[] stream;
            try (InputStream in = fs.createDocumentInputStream("PowerPoint Document")) {
                stream = in.readAllBytes();
            }
            for (int i = 0; i + 46 < stream.length; i++) {
                if ((stream[i + 2] & 0xFF) == 0xE9 && (stream[i + 3] & 0xFF) == 0x03 && stream[i + 4] == 40) {
                    stream[i + 45] = 1;
                    break;
                }
            }
            fs.createOrUpdateDocument(new ByteArrayInputStream(stream), "PowerPoint Document");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }
}
