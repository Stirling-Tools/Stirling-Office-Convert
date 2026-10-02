package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class SecureXmlTest {

    private static InputStream in(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesNamespacesAndBuiltInEntities() throws Exception {
        Document doc = SecureXml.parse(in("<w:doc xmlns:w=\"urn:w\"><w:t>a &amp; b &#x263A;</w:t></w:doc>"));
        assertEquals("urn:w", doc.getDocumentElement().getNamespaceURI());
        assertEquals("doc", doc.getDocumentElement().getLocalName());
        assertEquals("a & b ☺", doc.getDocumentElement().getTextContent());
    }

    @Test
    void streamReaderKeepsBuiltInEntities() throws Exception {
        XMLStreamReader r = SecureXml.reader(in("<a xmlns=\"urn:x\">x &amp; &lt;y&gt; &#65;</a>"));
        StringBuilder text = new StringBuilder();
        while (r.hasNext()) {
            if (r.next() == XMLStreamConstants.CHARACTERS) {
                text.append(r.getText());
            }
        }
        assertEquals("x & <y> A", text.toString());
    }

    @Test
    void parsesPartsWithManyBuiltInEntities() throws Exception {
        int n = 60_000;
        String xml = "<w:body xmlns:w=\"urn:w\">"
                + "<w:p><w:instr v=\"&quot;x&quot;\">a &amp; b &lt;c&gt; &apos;</w:instr></w:p>".repeat(n) + "</w:body>";
        Document doc = SecureXml.parse(in(xml));
        assertEquals(n, doc.getDocumentElement().getChildNodes().getLength());
        assertEquals("\"x\"", doc.getElementsByTagNameNS("urn:w", "instr").item(n - 1).getAttributes().item(0)
                .getNodeValue());
        XMLStreamReader r = SecureXml.reader(in(xml));
        int texts = 0;
        int quoted = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.CHARACTERS && r.getText().equals("a & b <c> '")) {
                texts++;
            } else if (event == XMLStreamConstants.START_ELEMENT && "\"x\"".equals(r.getAttributeValue(null, "v"))) {
                quoted++;
            }
        }
        assertEquals(n, texts);
        assertEquals(n, quoted);
    }

    @Test
    void domRejectsDoctypeAndExternalEntities() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"" + net.url("xxe") + "\">]><a>&x;</a>";
            assertThrows(IOException.class, () -> SecureXml.parse(in(xxe)));
            String dtd = "<?xml version=\"1.0\"?><!DOCTYPE a SYSTEM \"" + net.url("a.dtd") + "\"><a/>";
            assertThrows(IOException.class, () -> SecureXml.parse(in(dtd)));
            String param = "<!DOCTYPE a [<!ENTITY % p SYSTEM \"" + net.canaryUrl("p") + "\"> %p;]><a/>";
            assertThrows(IOException.class, () -> SecureXml.parse(in(param)));
            net.assertNothingConnected();
        }
    }

    @Test
    void domRejectsEntityExpansion() {
        StringBuilder lol = new StringBuilder("<!DOCTYPE a [<!ENTITY l0 \"lol\">");
        for (int i = 1; i < 10; i++) {
            lol.append("<!ENTITY l").append(i).append(" \"");
            lol.append(("&l" + (i - 1) + ";").repeat(10)).append("\">");
        }
        lol.append("]><a>&l9;</a>");
        assertThrows(IOException.class, () -> SecureXml.parse(in(lol.toString())));
    }

    @Test
    void domIgnoresXInclude() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            Document doc = SecureXml.parse(in("<a xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\""
                    + net.url("inc.xml") + "\" parse=\"text\"/></a>"));
            assertEquals("include", doc.getDocumentElement().getFirstChild().getLocalName());
            net.assertNothingConnected();
        }
    }

    @Test
    void streamReaderRejectsDoctype() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"" + net.url("xxe") + "\">]><a>&x;</a>";
            assertThrows(IOException.class, () -> drain(SecureXml.reader(in(xxe))));
            String dtd = "<!DOCTYPE a SYSTEM \"" + net.url("a.dtd") + "\"><a/>";
            assertThrows(IOException.class, () -> drain(SecureXml.reader(in(dtd))));
            net.assertNothingConnected();
        }
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "<a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5) + "</a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5);
        assertThrows(IOException.class, () -> SecureXml.parse(in(deep)));
    }

    @Test
    void theReusedParserKeepsEveryRuleAfterManyParses() throws Exception {
        String deep = "<a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5) + "</a>".repeat(SecureXml.MAX_ELEMENT_DEPTH + 5);
        String many = "<a>" + "<t>x &amp; y</t>".repeat(20_000) + "</a>";
        try (NoNetwork net = NoNetwork.start()) {
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"" + net.url("xxe") + "\">]><a>&x;</a>";
            for (int i = 0; i < 3; i++) {
                assertEquals(20_000, SecureXml.parse(in(many)).getElementsByTagName("t").getLength());
                assertThrows(IOException.class, () -> SecureXml.parse(in(xxe)));
                assertThrows(IOException.class, () -> SecureXml.parse(in(deep)));
                assertThrows(IOException.class, () -> SecureXml.parse(in("<a><b></a>")));
                Document ns = SecureXml.parse(in("<w:doc xmlns:w=\"urn:w\"/>"));
                assertEquals("urn:w", ns.getDocumentElement().getNamespaceURI());
            }
            net.assertNothingConnected();
        }
        Document first = SecureXml.parse(in("<one/>"));
        Document second = SecureXml.parse(in("<two/>"));
        assertEquals("one", first.getDocumentElement().getLocalName());
        assertEquals("two", second.getDocumentElement().getLocalName());
    }

    @Test
    void aStreamedPartThatIsNotWellFormedSaysWhichPartItIs(@TempDir Path dir) throws Exception {
        byte[] doc = Fixtures.edit(Fixtures.docx("x")).put("word/cut.xml", "<w:ftr xmlns:w=\"urn:w\"><w:p>")
                .put("word/early.xml", "<?xml version=\"9\"?><a/>")
                .put("word/doctype.xml", "<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;</a>").bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "parts.docx", doc))) {
            for (String part : new String[] {"/word/cut.xml", "/word/early.xml"}) {
                IOException e = assertThrows(IOException.class, () -> {
                    try (InputStream in = zip.open(part)) {
                        drain(SecureXml.reader(in));
                    }
                });
                OfficeZip.DamagedPart d = damaged(e);
                assertNotNull(d, part);
                assertEquals(part, d.part());
                assertTrue(d.getMessage().startsWith("The document is damaged: " + part + " is not well-formed XML"),
                        d.getMessage());
            }
            IOException doctype = assertThrows(IOException.class, () -> {
                try (InputStream in = zip.open("/word/doctype.xml")) {
                    drain(SecureXml.reader(in));
                }
            });
            assertNull(damaged(doctype));
        }
        assertNull(damaged(assertThrows(IOException.class, () -> drain(SecureXml.reader(in("<a><b></a>"))))));
    }

    private static OfficeZip.DamagedPart damaged(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OfficeZip.DamagedPart d) {
                return d;
            }
        }
        return null;
    }

    private static void drain(XMLStreamReader r) throws IOException {
        try {
            while (r.hasNext()) {
                r.next();
            }
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }
}
