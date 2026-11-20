package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlZoomTest {

    private static final String BODY = "<a href='#end'>Contents</a>"
            + "<p>Some paragraph text that wraps over the line.</p>".repeat(40) + "<h2 id=end>Target</h2>";

    private static PDDocument convert(String html, float zoom) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), OfficeToPdf.Format.HTML,
                out, OfficeToPdf.Options.defaults().zoom(zoom));
        return Loader.loadPDF(out.toByteArray());
    }

    private static float firstGlyphHeight(PDDocument document) throws Exception {
        List<Float> heights = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (heights.isEmpty() && !positions.isEmpty()) {
                    heights.add(positions.getFirst().getXScale());
                }
            }
        };
        stripper.setEndPage(1);
        stripper.getText(document);
        return heights.getFirst();
    }

    @Test
    void zoomScalesTheContentOnTheSamePaper() throws Exception {
        try (PDDocument normal = convert(BODY, 1f); PDDocument doubled = convert(BODY, 2f);
                PDDocument halved = convert(BODY, 0.5f)) {
            for (PDDocument d : List.of(doubled, halved)) {
                PDRectangle box = d.getPage(0).getMediaBox();
                assertEquals(normal.getPage(0).getMediaBox().getWidth(), box.getWidth(), 0.5f);
                assertEquals(normal.getPage(0).getMediaBox().getHeight(), box.getHeight(), 0.5f);
            }
            float size = firstGlyphHeight(normal);
            assertEquals(size * 2, firstGlyphHeight(doubled), size * 0.05f);
            assertEquals(size / 2, firstGlyphHeight(halved), size * 0.05f);
            assertTrue(doubled.getNumberOfPages() > normal.getNumberOfPages());
            assertTrue(halved.getNumberOfPages() < normal.getNumberOfPages());
        }
    }

    @Test
    void zoomKeepsAPageSizeTheDocumentDeclares() throws Exception {
        String html = "<style>@page{size:letter landscape}</style>" + BODY;
        try (PDDocument doubled = convert(html, 2f)) {
            assertEquals(792, doubled.getPage(0).getMediaBox().getWidth(), 0.5f);
            assertEquals(612, doubled.getPage(0).getMediaBox().getHeight(), 0.5f);
        }
    }

    @Test
    void linksStillReachTheirTargetWhenZoomed() throws Exception {
        try (PDDocument doubled = convert(BODY, 2f)) {
            PDAnnotationLink link = doubled.getPage(0).getAnnotations().stream()
                    .filter(PDAnnotationLink.class::isInstance).map(PDAnnotationLink.class::cast).findFirst()
                    .orElseThrow();
            PDPageXYZDestination destination = (PDPageXYZDestination) link.getDestination();
            int page = doubled.getPages().indexOf(destination.getPage());
            assertEquals(doubled.getNumberOfPages() - 1, page);
            PDFTextStripper text = new PDFTextStripper();
            text.setStartPage(page + 1);
            text.setEndPage(page + 1);
            assertTrue(text.getText(doubled).contains("Target"));
        }
    }

    @Test
    void largePaperKeepsItsSizeAtTheSmallestZoom() throws Exception {
        String html = "<style>@page{size:841mm 1189mm}</style>" + BODY;
        try (PDDocument small = convert(html, 0.1f)) {
            assertEquals(2383.9f, small.getPage(0).getMediaBox().getWidth(), 2f);
            assertEquals(3370.4f, small.getPage(0).getMediaBox().getHeight(), 2f);
        }
    }

    @Test
    void marginBoxesKeepClearOfTheBodyWhenZoomedIn() throws Exception {
        String html = "<style>@page{size:A5;margin:2cm;@top-center{content:'Running header'}"
                + "@bottom-right{content:'Page ' counter(page)}}</style>" + BODY;
        try (PDDocument zoomed = convert(html, 3f)) {
            PDFTextStripper text = new PDFTextStripper();
            text.setSortByPosition(true);
            text.setEndPage(1);
            String page = text.getText(zoomed).replaceAll("\\s+", " ").strip();
            assertTrue(page.startsWith("Running header"), page);
            assertTrue(page.matches(".*Page 1$"), page);
        }
    }

    @Test
    void formFieldFontsScaleWithTheZoom() throws Exception {
        String html = "<form><input name=a value=text style='font-size:10pt'></form>";
        try (PDDocument normal = convert(html, 1f); PDDocument doubled = convert(html, 2f)) {
            assertEquals(2 * fieldFontSize(normal), fieldFontSize(doubled), 0.01f);
        }
    }

    private static float fieldFontSize(PDDocument document) throws Exception {
        var form = document.getDocumentCatalog().getAcroForm();
        String da = form.getFields().getFirst().getCOSObject().getString(org.apache.pdfbox.cos.COSName.DA);
        if (da == null) {
            da = document.getPage(0).getAnnotations().getFirst().getCOSObject()
                    .getString(org.apache.pdfbox.cos.COSName.DA);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\d.]+)\\s+Tf").matcher(da);
        assertTrue(m.find(), da);
        return Float.parseFloat(m.group(1));
    }

    @Test
    void zoomOutsideTheRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OfficeToPdf.Options.defaults().zoom(0.05f));
        assertThrows(IllegalArgumentException.class, () -> OfficeToPdf.Options.defaults().zoom(9f));
        assertThrows(IllegalArgumentException.class, () -> OfficeToPdf.Options.defaults().zoom(Float.NaN));
    }
}
