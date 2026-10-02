package stirling.software.officeconvert.topdf.wordml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class WordMlPackageTest {

    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP4z8DA8B8MDDxfBvtDmEI+AAAAAElFTkSuQmCC";

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<?mso-application progid=\"Word.Document\"?><w:wordDocument"
            + " xmlns:w=\"http://schemas.microsoft.com/office/word/2003/wordml\" xmlns:v=\"urn:schemas-microsoft-com:vml\""
            + " xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:w10=\"urn:schemas-microsoft-com:office:word\""
            + " xmlns:wx=\"http://schemas.microsoft.com/office/word/2003/auxHint\""
            + " xmlns:aml=\"http://schemas.microsoft.com/aml/2001/core\""
            + " xmlns:wsp=\"http://schemas.microsoft.com/office/word/2003/wordml/sp2\""
            + " xmlns:st1=\"urn:schemas-microsoft-com:office:smarttags\" xml:space=\"preserve\">";

    private static final String DOC = HEAD
            + "<o:DocumentProperties><o:Title>Quarterly letter</o:Title><o:Author>Ann</o:Author></o:DocumentProperties>"
            + "<w:fonts><w:defaultFonts w:ascii=\"Arial\" w:fareast=\"MS Mincho\" w:h-ansi=\"Arial\" w:cs=\"Arial\"/>"
            + "<w:font w:name=\"Arial\"><w:panose-1 w:val=\"020B0604020202020204\"/></w:font></w:fonts>"
            + "<w:lists><w:listDef w:listDefId=\"0\"><w:lsid w:val=\"1A2B3C4D\"/><w:plt w:val=\"HybridMultilevel\"/>"
            + "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:nfc w:val=\"2\"/><w:lvlText w:val=\"%1)\"/>"
            + "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:listDef>"
            + "<w:list w:ilfo=\"1\"><w:ilst w:val=\"0\"/></w:list></w:lists>"
            + "<w:styles><w:versionOfBuiltInStylenames w:val=\"4\"/><w:latentStyles w:defLockedState=\"off\">"
            + "<w:lsdException w:name=\"Normal\"/></w:latentStyles><w:style w:type=\"paragraph\" w:default=\"on\""
            + " w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><wx:uiName wx:val=\"Normal\"/><w:rPr><wx:font"
            + " wx:val=\"Arial\"/><w:sz w:val=\"22\"/><w:sz-cs w:val=\"22\"/></w:rPr></w:style>"
            + "<w:style w:type=\"list\" w:styleId=\"Outline\"><w:name w:val=\"Outline\"/></w:style></w:styles>"
            + "<w:docPr><w:view w:val=\"print\"/><w:defaultTabStop w:val=\"720\"/><w:characterSpacingControl"
            + " w:val=\"DontCompress\"/><w:attachedTemplate w:val=\"\\\\server\\share\\x.dot\"/><w:footnotePr>"
            + "<w:footnote w:type=\"separator\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
            + "<w:footnote w:type=\"continuation-separator\"><w:p><w:r><w:continuationSeparator/></w:r></w:p>"
            + "</w:footnote></w:footnotePr></w:docPr>"
            + "<w:body><wx:sect><w:p><w:pPr><w:spacing w:after=\"120\" w:line=\"240\" w:line-rule=\"at-least\"/>"
            + "</w:pPr><w:r><w:rPr><w:b/><w:b-cs/><w:u w:val=\"dotted-heavy\"/></w:rPr><w:t>Opening line</w:t></w:r>"
            + "<st1:place><w:r><w:t> in Paris</w:t></w:r></st1:place></w:p>"
            + "<w:p><w:pPr><w:listPr><w:ilvl w:val=\"0\"/><w:ilfo w:val=\"1\"/><wx:t wx:val=\"i)\"/></w:listPr></w:pPr>"
            + "<w:r><w:t>First item</w:t></w:r><aml:annotation aml:id=\"5\" w:type=\"Word.Insertion\""
            + " aml:author=\"Bob\" aml:createdate=\"2004-01-01T00:00:00Z\"><aml:content><w:r><w:t> added</w:t></w:r>"
            + "</aml:content></aml:annotation><aml:annotation aml:id=\"6\" w:type=\"Word.Deletion\" aml:author=\"Bob\">"
            + "<aml:content><w:r><w:delText> removed</w:delText></w:r></aml:content></aml:annotation></w:p>"
            + "<w:p><aml:annotation aml:id=\"1\" w:type=\"Word.Bookmark.Start\" w:name=\"intro\"/>"
            + "<w:hlink w:dest=\"https://example.com/\" w:screenTip=\"site\"><w:r><w:t>Link text</w:t></w:r></w:hlink>"
            + "<aml:annotation aml:id=\"1\" w:type=\"Word.Bookmark.End\"/><w:r><w:t>Noted</w:t></w:r><w:r><w:rPr>"
            + "<w:rStyle w:val=\"FootnoteReference\"/></w:rPr><w:footnote><w:p><w:r><w:footnoteRef/></w:r><w:r>"
            + "<w:t>Footnote body</w:t></w:r></w:p></w:footnote></w:r>"
            + "<aml:annotation aml:id=\"9\" w:type=\"Word.Comment.Start\"/><w:r><w:t>Commented</w:t></w:r>"
            + "<aml:annotation aml:id=\"9\" w:type=\"Word.Comment.End\"/><w:r><aml:annotation aml:id=\"9\""
            + " w:type=\"Word.Comment\" aml:author=\"Cy\" w:initials=\"C\"><aml:content><w:p><w:r><w:t>Comment body"
            + "</w:t></w:r></w:p></aml:content></aml:annotation></w:r></w:p>"
            + "<w:p><w:r><w:pict><w:binData w:name=\"wordml://01000001.png\" xml:space=\"preserve\">" + PNG
            + "</w:binData><v:shape id=\"_x0000_i1025\" style=\"width:20pt;height:20pt\"><v:imagedata"
            + " src=\"wordml://01000001.png\" o:title=\"\" o:href=\"file:///C:/secret.png\"/></v:shape></w:pict></w:r>"
            + "<w:r><w:pict><v:shape style=\"width:20pt;height:20pt\"><v:imagedata src=\"http://example.com/a.png\"/>"
            + "</v:shape></w:pict></w:r></w:p>"
            + "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"2000\"/>"
            + "</w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"400\" w:h-rule=\"exact\"/></w:trPr><w:tc><w:tcPr>"
            + "<w:tcW w:w=\"2000\" w:type=\"dxa\"/><w:vmerge w:val=\"restart\"/></w:tcPr><w:p><w:r><w:t>Cell one"
            + "</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
            + "<w:sectPr><w:hdr w:type=\"odd\"><w:p><w:r><w:t>Running head</w:t></w:r></w:p></w:hdr>"
            + "<w:ftr w:type=\"first\"><w:p><w:r><w:t>First foot</w:t></w:r></w:p></w:ftr>"
            + "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\""
            + " w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/><w:pgNumType w:fmt=\"lower-roman\"/>"
            + "<w:docGrid w:line-pitch=\"360\"/></w:sectPr></wx:sect></w:body></w:wordDocument>";

    @TempDir
    Path dir;

    private Path file(String name, String xml) throws IOException {
        return Files.write(dir.resolve(name), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> parts(Path source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WordMlPackage.write(source, out);
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        return parts;
    }

    @Test
    void recognisesTheRootElement() throws IOException {
        assertTrue(WordMlPackage.is(file("a.xml", DOC)));
        assertFalse(WordMlPackage.is(file("b.xml", "<?xml version=\"1.0\"?><rss/>")));
    }

    @Test
    void mapsTheBodyToWordprocessingMl() throws IOException {
        String doc = parts(file("a.xml", DOC)).get("word/document.xml");
        assertTrue(doc.contains("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""),
                doc);
        assertTrue(doc.contains("<w:bCs>"), doc);
        assertTrue(doc.contains("<w:u w:val=\"dottedHeavy\">"), doc);
        assertTrue(doc.contains("w:lineRule=\"atLeast\""), doc);
        assertTrue(doc.contains("<w:numPr><w:ilvl w:val=\"0\"></w:ilvl><w:numId w:val=\"1\"></w:numId></w:numPr>"), doc);
        assertTrue(doc.contains("<w:t xml:space=\"preserve\"> in Paris</w:t>"), doc);
        assertTrue(doc.contains("<w:vMerge w:val=\"restart\">"), doc);
        assertTrue(doc.contains("w:hRule=\"exact\""), doc);
        assertTrue(doc.contains("<w:pgNumType w:fmt=\"lowerRoman\">"), doc);
        assertTrue(doc.contains("w:linePitch=\"360\""), doc);
        assertFalse(doc.contains("wx:"), doc);
    }

    @Test
    void mapsAnnotationsToRevisionsBookmarksAndComments() throws IOException {
        Map<String, String> p = parts(file("a.xml", DOC));
        String doc = p.get("word/document.xml");
        assertTrue(doc.contains("<w:ins w:id=\"1\" w:author=\"Bob\" w:date=\"2004-01-01T00:00:00Z\"><w:r><w:t xml:space=\"preserve\"> added"),
                doc);
        assertTrue(doc.contains("<w:del w:id=\"2\" w:author=\"Bob\"><w:r><w:delText xml:space=\"preserve\"> removed"), doc);
        assertTrue(doc.contains("<w:bookmarkStart w:id=\"1\" w:name=\"intro\"/>"), doc);
        assertTrue(doc.contains("<w:bookmarkEnd w:id=\"1\"/>"), doc);
        assertTrue(doc.contains("<w:commentRangeStart w:id=\"9\"/>"), doc);
        assertTrue(doc.contains("<w:commentReference w:id=\"9\"/>"), doc);
        assertTrue(p.get("word/comments.xml").contains("<w:comment w:id=\"9\" w:author=\"Cy\" w:initials=\"C\">"));
        assertTrue(p.get("word/comments.xml").contains("Comment body"));
    }

    @Test
    void movesNotesHeadersAndFootersIntoTheirParts() throws IOException {
        Map<String, String> p = parts(file("a.xml", DOC));
        String doc = p.get("word/document.xml");
        String notes = p.get("word/footnotes.xml");
        assertTrue(notes.contains("<w:footnote w:type=\"separator\" w:id=\"1\">"), notes);
        assertTrue(notes.contains("<w:footnote w:type=\"continuationSeparator\" w:id=\"2\">"), notes);
        assertTrue(notes.contains("<w:footnote w:id=\"3\"><w:p><w:r><w:footnoteRef>"), notes);
        assertTrue(doc.contains("<w:footnoteReference w:id=\"3\"/>"), doc);
        assertTrue(doc.contains("<w:headerReference w:type=\"default\" r:id=\""), doc);
        assertTrue(doc.contains("<w:footerReference w:type=\"first\" r:id=\""), doc);
        assertTrue(p.get("word/header1.xml").contains("Running head"));
        assertTrue(p.get("word/footer2.xml").contains("First foot"));
        String rels = p.get("word/_rels/document.xml.rels");
        assertTrue(rels.contains("Target=\"header1.xml\""), rels);
        assertTrue(rels.contains("Target=\"footnotes.xml\""), rels);
        assertTrue(rels.contains("Target=\"https://example.com/\" TargetMode=\"External\""), rels);
    }

    @Test
    void mapsListsStylesAndSettings() throws IOException {
        Map<String, String> p = parts(file("a.xml", DOC));
        String numbering = p.get("word/numbering.xml");
        assertTrue(numbering.contains("<w:abstractNum w:abstractNumId=\"0\"><w:nsid w:val=\"1A2B3C4D\">"), numbering);
        assertTrue(numbering.contains("<w:multiLevelType w:val=\"hybridMultilevel\">"), numbering);
        assertTrue(numbering.contains("<w:numFmt w:val=\"lowerRoman\">"), numbering);
        assertTrue(numbering.contains("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\">"), numbering);
        String styles = p.get("word/styles.xml");
        assertTrue(styles.contains("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\""
                + " w:eastAsia=\"MS Mincho\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>"), styles);
        assertTrue(styles.contains("<w:szCs w:val=\"22\">"), styles);
        assertTrue(styles.contains("w:type=\"numbering\""), styles);
        assertFalse(styles.contains("latentStyles"), styles);
        String settings = p.get("word/settings.xml");
        assertTrue(settings.contains("<w:characterSpacingControl w:val=\"doNotCompress\">"), settings);
        assertFalse(settings.contains("attachedTemplate"), settings);
        assertTrue(p.get("word/fontTable.xml").contains("<w:panose1 w:val="));
        assertTrue(p.get("docProps/core.xml").contains("<dc:title>Quarterly letter</dc:title>"));
    }

    @Test
    void keepsEmbeddedPicturesAndDropsLinkedOnes() throws IOException {
        Map<String, String> p = parts(file("a.xml", DOC));
        assertTrue(p.containsKey("word/media/image1.png"));
        String doc = p.get("word/document.xml");
        assertTrue(doc.contains("<v:imagedata r:id=\""), doc);
        assertFalse(doc.contains("secret.png"), doc);
        assertFalse(doc.contains("example.com/a.png"), doc);
        assertFalse(p.get("word/_rels/document.xml.rels").contains("a.png"));
    }

    @Test
    void convertsToPdf() throws IOException {
        Path in = file("letter.xml", DOC);
        Path pdf = dir.resolve("letter.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, pdf);
        assertEquals(1, r.pages());
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(d);
            for (String s : new String[] {"Opening line", "First item", "Link text", "Footnote body", "Running head",
                "Cell one"}) {
                assertTrue(text.contains(s), text);
            }
            assertEquals("Quarterly letter", d.getDocumentInformation().getTitle());
        }
    }

    @Test
    void readsWordMlSavedWithADocName() throws IOException {
        Path in = file("letter.doc", DOC);
        assertEquals(1, OfficeToPdf.convert(in, dir.resolve("letter.pdf")).pages());
    }

    @Test
    void refusesMalformedXmlAndMissingBody() throws IOException {
        Path broken = file("broken.xml", HEAD + "<w:body><w:p></w:body>");
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(broken, dir.resolve("x.pdf")));
        assertTrue(e.getMessage().contains("not well-formed"), e.getMessage());
        Path empty = file("empty.xml", HEAD + "</w:wordDocument>");
        e = assertThrows(IOException.class, () -> OfficeToPdf.convert(empty, dir.resolve("y.pdf")));
        assertTrue(e.getMessage().contains("no body"), e.getMessage());
    }
}
