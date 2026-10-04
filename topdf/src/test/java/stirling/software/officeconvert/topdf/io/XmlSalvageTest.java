package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class XmlSalvageTest {

    private static final String DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t xml:space=\"preserve\">First &amp; one</w:t></w:r></w:p>"
            + "<!-- a comment > with a bracket --><w:p><w:r><w:t>Second é中</w:t></w:r><w:r><w:br/></w:r></w:p>"
            + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t a=\"x&gt;y\">Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
            + "<w:p/><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>";

    @Test
    void everyCutOfADocumentBecomesWellFormedAndKeepsItsStart() throws Exception {
        byte[] full = DOC.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(full, XmlSalvage.salvage(full));
        String whole = SecureXml.parse(new ByteArrayInputStream(full)).getDocumentElement().getTextContent();
        int salvaged = 0;
        for (int n = 0; n < full.length; n++) {
            byte[] out = XmlSalvage.salvage(java.util.Arrays.copyOf(full, n));
            if (out == null) {
                continue;
            }
            salvaged++;
            Document doc = SecureXml.parse(new ByteArrayInputStream(out));
            assertEquals("document", doc.getDocumentElement().getLocalName(), "cut at " + n);
            assertEquals("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                    doc.getDocumentElement().getNamespaceURI());
            String kept = doc.getDocumentElement().getTextContent();
            assertTrue(whole.startsWith(kept), "cut at " + n + ": " + kept);
        }
        assertTrue(salvaged > full.length / 2, "salvaged " + salvaged);
        byte[] afterFirst = java.util.Arrays.copyOf(full, DOC.indexOf("<!--") + 3);
        Document first = SecureXml.parse(new ByteArrayInputStream(XmlSalvage.salvage(afterFirst)));
        assertEquals("First & one", first.getDocumentElement().getTextContent());
        org.w3c.dom.Element t = (org.w3c.dom.Element) first.getElementsByTagNameNS("*", "t").item(0);
        assertEquals("preserve", t.getAttributeNS("http://www.w3.org/XML/1998/namespace", "space"));
        int at = DOC.substring(0, DOC.indexOf("Cell") + 2).getBytes(StandardCharsets.UTF_8).length;
        byte[] tail = java.util.Arrays.copyOf(full, at);
        Document cell = SecureXml.parse(new ByteArrayInputStream(XmlSalvage.salvage(tail)));
        org.w3c.dom.Element quoted = (org.w3c.dom.Element) cell.getElementsByTagNameNS("*", "t").item(2);
        assertEquals("x>y", quoted.getAttribute("a"));
    }

    @Test
    void aLongPartIsCutAtItsLastCompleteTag() throws Exception {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><root xmlns=\"urn:r\" xmlns:x=\"urn:x\">");
        for (int i = 0; i < 50_000; i++) {
            xml.append("<x:item n=\"").append(i).append("\" v=\"a&amp;b	\">text ").append(i).append(" &lt;&gt;</x:item>");
        }
        byte[] full = xml.append("</root>").toString().getBytes(StandardCharsets.UTF_8);
        for (int cut : new int[] {full.length / 3, full.length / 2 + 7, full.length - 9}) {
            Document doc = SecureXml.parse(new ByteArrayInputStream(XmlSalvage.salvage(java.util.Arrays.copyOf(full, cut))));
            org.w3c.dom.NodeList items = doc.getElementsByTagNameNS("urn:x", "item");
            assertTrue(items.getLength() > 1000, "cut " + cut);
            org.w3c.dom.Element last = (org.w3c.dom.Element) items.item(items.getLength() - 2);
            assertEquals("a&b ", last.getAttribute("v"));
            assertEquals("text " + last.getAttribute("n") + " <>", last.getTextContent());
        }
    }

    @Test
    void refusesDoctypesAndGivesUpWhenNothingIsLeft() {
        String dtd = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x \"y\">]><a><b>&x;</b><c>";
        assertNull(XmlSalvage.salvage(dtd.getBytes(StandardCharsets.UTF_8)));
        assertNull(XmlSalvage.salvage("<?xml version=\"1.0\"?><w:docu".getBytes(StandardCharsets.UTF_8)));
        assertNull(XmlSalvage.salvage(new byte[0]));
    }

    @Test
    void readsUtf16PartsAndWritesUtf8() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a><b>über</b><b>cut";
        byte[] utf16 = ("﻿" + xml).getBytes(StandardCharsets.UTF_16BE);
        byte[] out = XmlSalvage.salvage(utf16);
        Document doc = SecureXml.parse(new ByteArrayInputStream(out));
        assertEquals("über", doc.getDocumentElement().getTextContent());
    }
}
