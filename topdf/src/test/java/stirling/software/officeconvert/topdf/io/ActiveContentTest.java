package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.io.ActiveContent.Kind;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class ActiveContentTest {

    private static final String REL = Fixtures.REL;

    private static final String STRICT = Fixtures.STRICT_REL;

    private static final String MS = Fixtures.MS_REL;

    private static final String MS7 = Fixtures.MS_REL_2007;

    private static final String MS11 = Fixtures.MS_REL_2011;

    @Test
    void classifiesEveryActiveRelationshipType() {
        Map<String, Kind> expected = new LinkedHashMap<>();
        expected.put(MS + "vbaProject", Kind.MACRO);
        expected.put(MS + "vbaProjectSignature", Kind.MACRO);
        expected.put(MS + "wordVbaData", Kind.MACRO);
        expected.put(MS + "keyMapCustomizations", Kind.MACRO);
        expected.put(MS + "attachedToolbars", Kind.MACRO);
        expected.put(MS + "xlMacrosheet", Kind.MACRO);
        expected.put(MS + "xlIntlMacrosheet", Kind.MACRO);
        expected.put(REL + "control", Kind.ACTIVE_X);
        expected.put(MS + "activeXControlBinary", Kind.ACTIVE_X);
        expected.put(REL + "oleObject", Kind.OLE_OBJECT);
        expected.put(STRICT + "oleObject", Kind.OLE_OBJECT);
        expected.put(REL + "package", Kind.EMBEDDED_PACKAGE);
        expected.put(REL + "connections", Kind.DATA_CONNECTION);
        expected.put(REL + "volatileDependencies", Kind.DATA_CONNECTION);
        expected.put(REL + "xmlMaps", Kind.DATA_CONNECTION);
        expected.put(REL + "slideUpdateInfo", Kind.DATA_CONNECTION);
        expected.put(REL + "slideUpdateUrl", Kind.DATA_CONNECTION);
        expected.put(REL + "externalLinkPath", Kind.DATA_CONNECTION);
        expected.put(MS + "xlExternalLinkPath/xlPathMissing", Kind.DATA_CONNECTION);
        expected.put(REL + "queryTable", Kind.QUERY_TABLE);
        expected.put(STRICT + "queryTable", Kind.QUERY_TABLE);
        expected.put(REL + "externalLink", Kind.EXTERNAL_WORKBOOK);
        expected.put(MS11 + "webextension", Kind.WEB_EXTENSION);
        expected.put(MS11 + "webextensiontaskpanes", Kind.WEB_EXTENSION);
        expected.put(REL + "aFChunk", Kind.ALT_CHUNK);
        expected.put(STRICT + "aFChunk", Kind.ALT_CHUNK);
        expected.put(REL + "attachedTemplate", Kind.ATTACHED_TEMPLATE);
        expected.put(REL + "frame", Kind.FRAME);
        expected.put(REL + "subDocument", Kind.SUBDOCUMENT);
        expected.put(MS + "ui/extensibility", Kind.CUSTOM_UI);
        expected.put(MS7 + "ui/extensibility", Kind.CUSTOM_UI);
        expected.put(MS + "ui/userCustomization", Kind.CUSTOM_UI);
        expected.put(MS7 + "media", Kind.MEDIA);
        expected.put(REL + "video", Kind.MEDIA);
        expected.put(REL + "audio", Kind.MEDIA);
        expected.put(REL + "mailMergeSource", Kind.MAIL_MERGE);
        expected.put(REL + "mailMergeHeaderSource", Kind.MAIL_MERGE);
        expected.put(REL + "recipientData", Kind.MAIL_MERGE);
        for (Map.Entry<String, Kind> e : expected.entrySet()) {
            assertEquals(e.getValue(), ActiveContent.ofType(e.getKey()), e.getKey());
            Relationship internal = new Relationship("rId1", e.getKey(), "x.bin", false, "/word/x.bin");
            assertEquals(e.getValue(), ActiveContent.of(internal), e.getKey());
            assertFalse(ActiveContent.mayFollow(internal), e.getKey());
        }
    }

    @Test
    void leavesOrdinaryPartsFollowable() {
        for (String type : new String[] {REL + "image", REL + "styles", REL + "numbering", REL + "theme",
            REL + "slide", REL + "slideLayout", REL + "worksheet", REL + "sharedStrings", REL + "chart",
            REL + "drawing", REL + "vmlDrawing", REL + "header", REL + "footer", REL + "fontTable", REL + "font",
            STRICT + "image", MS7 + "hdphoto", REL + "diagramData", REL + "comments", REL + "customXml"}) {
            Relationship r = new Relationship("rId1", type, "x.xml", false, "/word/x.xml");
            assertNull(ActiveContent.of(r), type);
            assertTrue(ActiveContent.mayFollow(r), type);
        }
    }

    @Test
    void externalTargetsAreNeverFollowedButHyperlinksAreKeptAsStrings() {
        Relationship image = new Relationship("rId1", REL + "image", "http://example.com/a.png", true, null);
        assertEquals(Kind.EXTERNAL_TARGET, ActiveContent.of(image));
        assertFalse(ActiveContent.mayFollow(image));
        Relationship link = new Relationship("rId2", REL + "hyperlink", "https://example.com/a b", true, null);
        assertNull(ActiveContent.of(link));
        assertFalse(ActiveContent.mayFollow(link));
        assertEquals("https://example.com/a%20b", ActiveContent.hyperlink(link));
        Relationship js = new Relationship("rId3", REL + "hyperlink", "javascript:alert(1)", true, null);
        assertNull(ActiveContent.hyperlink(js));
        Relationship file = new Relationship("rId4", REL + "hyperlink", "file:///C:/Windows/win.ini", true, null);
        assertNull(ActiveContent.hyperlink(file));
        Relationship unc = new Relationship("rId5", REL + "hyperlink", "\\\\server\\share\\x", true, null);
        assertNull(ActiveContent.hyperlink(unc));
        assertEquals("mailto:a@example.com?subject=hi",
                ActiveContent.hyperlink(new Relationship("rId6", REL + "hyperlink", "mailto:a@example.com?subject=hi&attach=x",
                        true, null)));
    }

    @Test
    void classifiesActiveParts() {
        assertEquals(Kind.MACRO, ActiveContent.ofPart("/word/vbaProject.bin", "application/vnd.ms-office.vbaProject"));
        assertEquals(Kind.MACRO, ActiveContent.ofPart("/xl/vbaProject.bin", null));
        assertEquals(Kind.MACRO, ActiveContent.ofPart("/word/vbaData.xml", "application/vnd.ms-word.vbaData+xml"));
        assertEquals(Kind.MACRO, ActiveContent.ofPart("/xl/macrosheets/sheet1.xml",
                "application/vnd.ms-excel.macrosheet+xml"));
        assertEquals(Kind.ACTIVE_X, ActiveContent.ofPart("/word/activeX/activeX1.xml",
                "application/vnd.ms-office.activeX+xml"));
        assertEquals(Kind.ACTIVE_X, ActiveContent.ofPart("/word/activeX/activeX1.bin", "application/vnd.ms-office.activeX"));
        assertEquals(Kind.OLE_OBJECT, ActiveContent.ofPart("/ppt/embeddings/oleObject1.bin",
                "application/vnd.openxmlformats-officedocument.oleObject"));
        assertEquals(Kind.DATA_CONNECTION, ActiveContent.ofPart("/xl/connections.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.connections+xml"));
        assertEquals(Kind.QUERY_TABLE, ActiveContent.ofPart("/xl/queryTables/queryTable1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.queryTable+xml"));
        assertEquals(Kind.EXTERNAL_WORKBOOK, ActiveContent.ofPart("/xl/externalLinks/externalLink1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml"));
        assertEquals(Kind.WEB_EXTENSION, ActiveContent.ofPart("/word/webextensions/webextension1.xml",
                "application/vnd.ms-office.webextension+xml"));
        assertEquals(Kind.WEB_EXTENSION, ActiveContent.ofPart("/word/webextensions/taskpanes.xml",
                "application/vnd.ms-office.webextensiontaskpanes+xml"));
        assertEquals(Kind.ALT_CHUNK, ActiveContent.ofPart("/word/afchunk.htm", "text/html"));
        assertEquals(Kind.ALT_CHUNK, ActiveContent.ofPart("/word/afchunk.mht", "message/rfc822"));
        assertEquals(Kind.CUSTOM_UI, ActiveContent.ofPart("/customUI/customUI14.xml", "application/xml"));
        assertEquals(Kind.MEDIA, ActiveContent.ofPart("/ppt/media/media1.mp4", "video/mp4"));
        assertEquals(Kind.MEDIA, ActiveContent.ofPart("/ppt/media/media2.wav", "audio/wav"));
        assertNull(ActiveContent.ofPart("/word/media/image1.png", "image/png"));
        assertNull(ActiveContent.ofPart("/word/document.xml",
                "application/vnd.ms-word.document.macroEnabled.main+xml"));
    }

    @Test
    void onlyPageFieldsAreComputed() {
        assertEquals("PAGE", ActiveContent.fieldName(" PAGE \\* MERGEFORMAT "));
        assertEquals("NUMPAGES", ActiveContent.fieldName("numpages"));
        assertEquals("INCLUDEPICTURE", ActiveContent.fieldName("INCLUDEPICTURE \"http://x\" \\d"));
        assertTrue(ActiveContent.computedField("PAGE"));
        assertTrue(ActiveContent.computedField(" NUMPAGES \\* Arabic "));
        assertTrue(ActiveContent.computedField("SECTIONPAGES"));
        for (String f : new String[] {"DDEAUTO cmd", "DDE x", "INCLUDETEXT \"a\"", "INCLUDEPICTURE \"b\"", "HYPERLINK \"c\"",
            "DATE", "TIME", "MACROBUTTON x", "LINK Excel.Sheet", "EMBED x", "IMPORT y", "=1+1", "AUTOTEXT z", ""}) {
            assertFalse(ActiveContent.computedField(f), f);
        }
    }

    @Test
    void inventoriesEverythingItWillNotRunOrLoad(@TempDir Path dir) throws Exception {
        try (NoNetwork net = NoNetwork.start();
                OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "hostile.docx", Fixtures.hostileDocx(net)))) {
            Map<Kind, Set<String>> found = ActiveContent.scan(zip);
            for (Kind k : new Kind[] {Kind.MACRO, Kind.ACTIVE_X, Kind.OLE_OBJECT, Kind.ALT_CHUNK, Kind.ATTACHED_TEMPLATE,
                    Kind.FRAME, Kind.SUBDOCUMENT, Kind.MEDIA, Kind.MAIL_MERGE, Kind.EXTERNAL_TARGET, Kind.WEB_EXTENSION,
                    Kind.CUSTOM_UI}) {
                assertTrue(found.containsKey(k), k + " missing from " + found);
            }
            assertTrue(found.get(Kind.MACRO).contains("/word/vbaProject.bin"), found.toString());
            assertFalse(found.values().stream().anyMatch(s -> s.stream().anyMatch(x -> x.contains("hyperlink"))),
                    "hyperlinks are not active content");
            List<String> lines = ActiveContent.describe(found);
            assertEquals(found.size(), lines.size());
            assertTrue(lines.stream().allMatch(l -> l.startsWith("Skipped active content: ")), lines.toString());
            assertFalse(lines.stream().anyMatch(l -> l.contains("/") || l.contains("\\")), lines.toString());
            net.assertNothingConnected();
        }
        assertEquals("/", ActiveContent.sourceOf("/_rels/.rels"));
        assertEquals("/word/document.xml", ActiveContent.sourceOf("/word/_rels/document.xml.rels"));
        assertNull(ActiveContent.sourceOf("/word/document.xml"));
    }

    @Test
    void describesKindsAndCountsButNeverTheDocumentsPathsOrAddresses() {
        Map<Kind, Set<String>> found = new LinkedHashMap<>();
        found.put(Kind.ATTACHED_TEMPLATE, Set.of("file:///C:\\Users\\someone\\Templates\\Standard.dotm"));
        found.put(Kind.EXTERNAL_TARGET, Set.of("http://intranet.example/a.png", "\\\\server\\share\\b.png", "c.png"));
        found.put(Kind.MACRO, Set.of("/word/vbaProject.bin"));
        assertEquals(List.of("Skipped active content: attached template (not loaded)",
                "Skipped active content: linked files and pictures (3, not fetched)",
                "Skipped active content: macros (not run)"), ActiveContent.describe(found));
    }
}
