package stirling.software.officeconvert.odt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.sink.SampleDocument;

class OdtWriterTest {

    private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";

    private static Map<String, byte[]> entries(byte[] zip, List<String> order, List<Integer> methods) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                order.add(e.getName());
                methods.add(e.getMethod());
                parts.put(e.getName(), in.readAllBytes());
            }
        }
        return parts;
    }

    private static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static byte[] convert(boolean flat) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OdtWriter w = new OdtWriter(out, flat)) {
            SampleDocument.write(w);
        }
        return out.toByteArray();
    }

    @Test
    void packageStartsWithAStoredMimetypeAndListsItsParts() throws Exception {
        List<String> order = new ArrayList<>();
        List<Integer> methods = new ArrayList<>();
        Map<String, byte[]> parts = entries(convert(false), order, methods);
        assertEquals("mimetype", order.getFirst());
        assertEquals(ZipEntry.STORED, methods.getFirst());
        assertEquals("application/vnd.oasis.opendocument.text", new String(parts.get("mimetype"), StandardCharsets.US_ASCII));
        for (String name : List.of("content.xml", "styles.xml", "meta.xml", "settings.xml", "META-INF/manifest.xml")) {
            assertNotNull(parts.get(name), name);
            xml(parts.get(name));
        }
        String manifest = new String(parts.get("META-INF/manifest.xml"), StandardCharsets.UTF_8);
        List<String> pictures = order.stream().filter(n -> n.startsWith("Pictures/")).toList();
        assertEquals(2, pictures.size(), "the picture, and its cropped copy");
        for (String p : pictures) {
            assertTrue(manifest.contains("full-path=\"" + p + "\""), p);
        }
    }

    @Test
    void writesStructureNotPositionedText() throws Exception {
        Map<String, byte[]> parts = entries(convert(false), new ArrayList<>(), new ArrayList<>());
        Document content = xml(parts.get("content.xml"));
        String raw = new String(parts.get("content.xml"), StandardCharsets.UTF_8);

        Element h = (Element) content.getElementsByTagNameNS(TEXT, "h").item(0);
        assertEquals("1", h.getAttributeNS(TEXT, "outline-level"));
        assertTrue(raw.contains("Annual <text:s/>Results"), "a second space is kept");
        assertTrue(raw.contains("<text:bookmark text:name=\"_Pg1\"/>"));
        assertTrue(raw.contains("xlink:href=\"https://example.com/a?b=1&amp;c=2\""));

        NodeList lists = content.getElementsByTagNameNS(TEXT, "list");
        assertTrue(lists.getLength() >= 3, "nested list plus the continued one");
        assertTrue(raw.contains("text:continue-list=\"list1\"") || raw.contains("xml:id=\"list2\""));

        Element note = (Element) content.getElementsByTagNameNS(TEXT, "note").item(0);
        assertNotNull(note, "the footnote arrived after its reference and is written there");
        assertTrue(note.getTextContent().contains("The note text."));

        Element table = (Element) content.getElementsByTagNameNS(TABLE, "table").item(0);
        assertEquals(3, table.getElementsByTagNameNS(TABLE, "table-column").getLength());
        assertEquals(1, table.getElementsByTagNameNS(TABLE, "table-header-rows").getLength());
        assertTrue(raw.contains("table:number-columns-spanned=\"2\""));
        assertTrue(raw.contains("table:number-rows-spanned=\"2\""));
        assertEquals(2, table.getElementsByTagNameNS(TABLE, "covered-table-cell").getLength());

        assertEquals(2, content.getElementsByTagNameNS(DRAW, "text-box").getLength());
        assertTrue(raw.contains("rotate(-1.5707963267949)"), "text read downwards: a frame turned a quarter right");
        assertEquals(2, content.getElementsByTagNameNS(DRAW, "rect").getLength());
        assertTrue(raw.contains("style:vertical-rel=\"paragraph\""), "the riding shape");

        Element section = (Element) content.getElementsByTagNameNS(TEXT, "section").item(0);
        assertTrue(section.getTextContent().contains("Right column text."));
        assertTrue(raw.contains("fo:column-count=\"2\""));
        assertTrue(raw.contains("fo:break-after=\"column\""));
        assertTrue(raw.contains("שלום"));
    }

    @Test
    void runningHeaderFooterAndListStylesLiveInTheStyles() throws Exception {
        Map<String, byte[]> parts = entries(convert(false), new ArrayList<>(), new ArrayList<>());
        String styles = new String(parts.get("styles.xml"), StandardCharsets.UTF_8);
        Document doc = xml(parts.get("styles.xml"));
        Element master = (Element) doc.getElementsByTagNameNS(STYLE, "master-page").item(0);
        assertEquals("Standard", master.getAttributeNS(STYLE, "name"));
        assertTrue(styles.contains("<text:page-number") && styles.contains("<text:page-count"));
        assertTrue(styles.contains("style:display-name=\"Heading 1\""));
        String content = new String(parts.get("content.xml"), StandardCharsets.UTF_8);
        assertTrue(content.contains("<text:list-style style:name=\"L2\">"));
        assertTrue(content.contains("text:start-value=\"3\""), "the list keeps its start number");
        assertTrue(content.contains("style:master-page-name=\"Standard\""));
    }

    @Test
    void textBoxesAreWriterFramesThatShowWhatLiesUnder() throws Exception {
        String s = new String(convert(true), StandardCharsets.UTF_8);
        assertTrue(s.contains("<style:style style:name=\"Frame\" style:family=\"graphic\">"));
        assertTrue(s.contains("style:family=\"graphic\" style:parent-style-name=\"Frame\""));
        assertTrue(s.contains("fo:background-color=\"transparent\" style:background-transparency=\"100%\""));
    }

    @Test
    void unevenColumnsKeepTheirWidthsInLibreOffice() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OdtWriter w = new OdtWriter(out, true)) {
            StyleSheet styles = new StyleSheet(SampleDocument.BODY);
            w.begin(styles, null);
            Paragraph p = SampleDocument.paragraph("Two columns of different widths.");
            Section uneven = SampleDocument.section(2);
            uneven.columns.set(0, new float[] {150, 18});
            uneven.columns.set(1, new float[] {300, 0});
            uneven.continuous = true;
            p.endsSection = uneven;
            w.block(p);
            w.block(SampleDocument.paragraph("After."));
            Section even = SampleDocument.section(2);
            even.continuous = true;
            w.finish(even, null, new Numbering(), styles, "Columns", "Tester");
        }
        String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int unevenAt = s.indexOf("style:rel-width=\"" + Math.round((150 + 9) * 20) + "*\"");
        assertTrue(unevenAt > 0);
        String unevenColumns = s.substring(s.lastIndexOf("<style:columns", unevenAt), unevenAt);
        assertFalse(unevenColumns.contains("fo:column-gap"));
        assertTrue(s.contains("fo:column-gap=\"18pt\""));
    }

    @Test
    void flatDocumentIsOneXmlFileWithPicturesInline() throws Exception {
        byte[] flat = convert(true);
        Document doc = xml(flat);
        Element root = doc.getDocumentElement();
        assertEquals("document", root.getLocalName());
        assertEquals("application/vnd.oasis.opendocument.text",
                root.getAttributeNS("urn:oasis:names:tc:opendocument:xmlns:office:1.0", "mimetype"));
        String s = new String(flat, StandardCharsets.UTF_8);
        assertTrue(s.contains("<office:binary-data>"));
        assertFalse(s.contains("Pictures/"));
        assertTrue(s.contains("<office:master-styles>") && s.contains("<office:automatic-styles>"));
    }
}
