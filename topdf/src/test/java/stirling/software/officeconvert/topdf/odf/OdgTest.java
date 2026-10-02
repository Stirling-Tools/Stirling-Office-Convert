package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class OdgTest {

    private static final String GRAPHICS = "application/vnd.oasis.opendocument.graphics";

    @TempDir
    Path dir;

    private static String styles(String layers) {
        return OdfFixtures.styles("", "<style:page-layout style:name=\"PM1\"><style:page-layout-properties"
                + " fo:page-width=\"21cm\" fo:page-height=\"29.7cm\"/></style:page-layout>",
                layers + "<style:master-page style:name=\"Default\" style:page-layout-name=\"PM1\"/>");
    }

    private static String page(String shapes) {
        return OdfFixtures.content("", "<office:drawing><draw:page draw:name=\"page1\" draw:master-page-name=\"Default\">"
                + shapes + "</draw:page></office:drawing>");
    }

    private static String box(String text, String layer) {
        return "<draw:frame draw:layer=\"" + layer + "\" svg:x=\"2cm\" svg:y=\"" + (layer.equals("layout") ? 2 : 8)
                + "cm\" svg:width=\"10cm\" svg:height=\"2cm\"><draw:text-box><text:p>" + text
                + "</text:p></draw:text-box></draw:frame>";
    }

    private String convert(String name, byte[] data) throws IOException {
        Path in = OdfFixtures.write(dir, name, data);
        Path pdf = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertTrue(d.getPage(0).getMediaBox().getHeight() > d.getPage(0).getMediaBox().getWidth());
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void aDrawingPrintsItsPageAtItsPaperSize() throws IOException {
        String t = convert("plan.odg", OdfFixtures.odf(GRAPHICS, page(box("Floor plan", "layout")), styles("")));
        assertTrue(t.contains("Floor plan"), t);
    }

    @Test
    void shapesOnLayersShownOnScreenOnlyAreNotPrinted() throws IOException {
        String layers = "<draw:layer-set><draw:layer draw:name=\"layout\"/><draw:layer draw:name=\"notes\""
                + " draw:display=\"screen\"/></draw:layer-set>";
        String t = convert("layers.odg", OdfFixtures.odf(GRAPHICS, page(box("Printed", "layout")
                + box("Screen only", "notes")), styles(layers)));
        assertTrue(t.contains("Printed"), t);
        assertFalse(t.contains("Screen only"), t);
    }
}
