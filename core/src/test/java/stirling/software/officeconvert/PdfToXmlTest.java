package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class PdfToXmlTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

    @TempDir static Path dir;
    static Path pdf;

    @BeforeAll
    static void makePdf() throws IOException {
        pdf = dir.resolve("report.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, BOLD, 20, 72, 700, "Quarterly Report");
                text(cs, REGULAR, 11, 72, 660, "Revenue grew in every region & margins held <steady>.");
                String[][] cells = {{"Region", "Sales"}, {"North", "1,200"}, {"South", "950"}};
                float[] cols = {72, 222, 372};
                float top = 620;
                for (int r = 0; r < cells.length; r++) {
                    for (int c = 0; c < 2; c++) {
                        text(cs, r == 0 ? BOLD : REGULAR, 11, cols[c] + 5, top - 20 * r - 14, cells[r][c]);
                    }
                }
                cs.setLineWidth(0.75f);
                for (int r = 0; r <= cells.length; r++) {
                    cs.moveTo(cols[0], top - 20 * r);
                    cs.lineTo(cols[2], top - 20 * r);
                }
                for (float x : cols) {
                    cs.moveTo(x, top);
                    cs.lineTo(x, top - 20 * cells.length);
                }
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    @Test
    void xmlExtensionWritesFlatOpenDocumentText() throws Exception {
        Path out = dir.resolve("report.xml");
        OfficeConvert.convert(pdf, out);
        assertEquals(OfficeConvert.Format.XML, OfficeConvert.Format.of(out));
        Document xml = parse(Files.readAllBytes(out));
        Element root = xml.getDocumentElement();
        assertEquals(OFFICE, root.getNamespaceURI());
        assertEquals("document", root.getLocalName());
        assertEquals("application/vnd.oasis.opendocument.text", root.getAttributeNS(OFFICE, "mimetype"));
        assertEquals(1, xml.getElementsByTagNameNS(OFFICE, "text").getLength());
        assertEquals(1, xml.getElementsByTagNameNS(OFFICE, "styles").getLength());
        assertEquals(1, xml.getElementsByTagNameNS(OFFICE, "master-styles").getLength());

        String plain = xml.getElementsByTagNameNS(OFFICE, "text").item(0).getTextContent();
        assertTrue(plain.contains("Quarterly Report"), plain);
        assertTrue(plain.replace(" ", "").contains("Revenuegrewineveryregion&marginsheld<steady>."), plain);
        assertEquals(1, xml.getElementsByTagNameNS(TABLE, "table").getLength());
        Element table = (Element) xml.getElementsByTagNameNS(TABLE, "table").item(0);
        assertEquals(3, table.getElementsByTagNameNS(TABLE, "table-row").getLength());
        for (String cell : new String[] {"Region", "North", "1,200", "950"}) {
            assertTrue(table.getTextContent().contains(cell), cell);
        }
    }

    @Test
    void streamFormMatchesTheFlatOdtWriterAndSkipsAZip() throws Exception {
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        ByteArrayOutputStream fodt = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            OfficeConvert.convert(doc, xml, OfficeConvert.Format.XML, OfficeConvert.Settings.defaults());
            OfficeConvert.convert(doc, fodt, OfficeConvert.Format.FODT, OfficeConvert.Settings.defaults());
        }
        assertEquals('<', xml.toByteArray()[0]);
        assertEquals(strip(fodt.toString(StandardCharsets.UTF_8)), strip(xml.toString(StandardCharsets.UTF_8)));
        assertFalse(xml.toString(StandardCharsets.UTF_8).contains("<!DOCTYPE"));
        parse(xml.toByteArray());
    }

    private static String strip(String xml) {
        return xml.replaceAll("<meta:creation-date>[^<]*</meta:creation-date><dc:date>[^<]*</dc:date>", "");
    }
}
