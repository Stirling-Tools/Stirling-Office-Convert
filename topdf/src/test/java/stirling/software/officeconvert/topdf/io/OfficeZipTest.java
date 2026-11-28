package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

class OfficeZipTest {

    @TempDir
    Path dir;

    @Test
    void readsPartsContentTypesAndRelationships() throws Exception {
        Path docx = Fixtures.write(dir, "a.docx", Fixtures.docx("Hello"));
        try (OfficeZip zip = OfficeZip.open(docx)) {
            assertEquals("/word/document.xml", zip.mainPart());
            assertTrue(zip.mainContentType().contains("wordprocessingml.document.main"));
            assertTrue(zip.exists("word/document.xml"));
            assertTrue(zip.exists("/WORD/Document.xml"));
            assertEquals("application/xml", zip.contentType("/x/unknown.xml"));
            Relationship main = zip.packageRelationships().first("officeDocument");
            assertNotNull(main);
            assertFalse(main.external());
            assertTrue(new String(zip.read(main)).contains("Hello"));
        }
    }

    @Test
    void aPartIsReadWhateverSizeItsDirectoryEntryClaims() throws Exception {
        byte[] body = ("<a>" + "x".repeat(2_000) + "</a>").getBytes(StandardCharsets.US_ASCII);
        for (int declared : new int[] {body.length, body.length + 500, 40}) {
            Path file = Fixtures.write(dir, "sized" + declared + ".docx", declaring(body, declared));
            try (OfficeZip zip = OfficeZip.open(file)) {
                if (declared >= body.length) {
                    assertArrayEquals(body, zip.read("word/part.xml"), "declared " + declared);
                } else {
                    IOException e = assertThrows(IOException.class, () -> zip.read("word/part.xml"));
                    assertTrue(e.getMessage().contains("larger than its zip entry says"), e.getMessage());
                }
            }
        }
    }

    private static byte[] declaring(byte[] body, int declared) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Fixtures.docx("Hello")));
                ZipOutputStream z = new ZipOutputStream(out)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                z.putNextEntry(new ZipEntry(e.getName()));
                in.transferTo(z);
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("word/part.xml"));
            z.write(body);
            z.closeEntry();
        }
        byte[] zip = out.toByteArray();
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 1 && zip[i + 3] == 2) {
                zip[i + 24] = (byte) declared;
                zip[i + 25] = (byte) (declared >> 8);
                zip[i + 26] = 0;
                zip[i + 27] = 0;
                break;
            }
        }
        return zip;
    }

    @Test
    void resolvesInternalTargetsRelativeToTheSourcePart() {
        assertEquals("/word/media/image1.png", OfficeZip.resolve("/word/document.xml", "media/image1.png"));
        assertEquals("/xl/queryTables/q.xml", OfficeZip.resolve("/xl/worksheets/sheet1.xml", "../queryTables/q.xml"));
        assertEquals("/customUI/ui.xml", OfficeZip.resolve("/", "customUI/ui.xml"));
        assertEquals("/abs.xml", OfficeZip.resolve("/word/document.xml", "/abs.xml"));
        assertNull(OfficeZip.resolve("/word/document.xml", "../../../etc/passwd"));
        assertNull(OfficeZip.resolve("/word/document.xml", "http://example.com/x.png"));
        assertNull(OfficeZip.resolve("/word/document.xml", "file:///C:/Windows/win.ini"));
        assertNull(OfficeZip.resolve("/word/document.xml", "//server/share/x"));
        assertEquals("/word/_rels/document.xml.rels", OfficeZip.relsPartFor("/word/document.xml"));
        assertEquals("/_rels/.rels", OfficeZip.relsPartFor("/"));
    }

    @Test
    void exposesExternalTargetsOnlyAsStrings() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            byte[] data = Fixtures.edit(Fixtures.docx("x"))
                    .relationship("/word/document.xml", "rIdA", Fixtures.REL + "image", net.url("a.png"), true)
                    .relationship("/word/document.xml", "rIdB", Fixtures.REL + "image", net.canaryUrl("b.png"), false)
                    .relationship("/word/document.xml", "rIdC", Fixtures.REL + "image", net.uncPath("c.png"), true)
                    .relationship("/word/document.xml", "rIdD", Fixtures.REL + "image", "file:///etc/passwd", false)
                    .bytes();
            Path docx = Fixtures.write(dir, "ext.docx", data);
            try (OfficeZip zip = OfficeZip.open(docx)) {
                Relationships rels = zip.relationships("/word/document.xml");
                for (String id : new String[] {"rIdA", "rIdB", "rIdC", "rIdD"}) {
                    Relationship r = rels.get(id);
                    assertTrue(r.external(), id);
                    assertNull(r.part(), id);
                    assertEquals(ActiveContent.Kind.EXTERNAL_TARGET, ActiveContent.of(r), id);
                    assertThrows(IOException.class, () -> zip.open(r), id);
                    assertThrows(IOException.class, () -> zip.read(r), id);
                }
                assertEquals(net.url("a.png"), rels.get("rIdA").target());
                for (String part : zip.partNames()) {
                    zip.read(part);
                }
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void readsTheHostileCorpusWithoutTouchingTheNetwork() throws Exception {
        try (NoNetwork net = NoNetwork.start()) {
            byte[][] docs = {Fixtures.hostileDocx(net), Fixtures.hostilePptx(net), Fixtures.hostileXlsx(net)};
            int i = 0;
            for (byte[] d : docs) {
                Path file = Fixtures.write(dir, "hostile" + i++ + ".zip", d);
                try (OfficeZip zip = OfficeZip.open(file)) {
                    for (String part : zip.partNames()) {
                        Relationships rels = zip.relationships(part);
                        for (Relationship r : rels.all()) {
                            if (ActiveContent.mayFollow(r) && zip.exists(r.part())) {
                                zip.read(r);
                            }
                        }
                        if (part.endsWith(".xml") || part.endsWith(".rels")) {
                            zip.xml(part);
                        }
                    }
                }
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void refusesZipBombs() throws Exception {
        Path bomb = Fixtures.write(dir, "bomb.docx", Fixtures.zipBomb(Fixtures.docx("x"), "word/media/zeros.bin", 64L << 20));
        IOException e = assertThrows(OfficeZip.Oversized.class, () -> OfficeZip.open(bomb));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
    }

    @Test
    void acceptsADenseMetafileWithinTheDenseLimit() throws Exception {
        byte[] metafile = dense(6 << 20);
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/media/image1.emf", metafile)
                .put("docProps/thumbnail.emf", dense(2 << 20)).bytes();
        Path docx = Fixtures.write(dir, "dense.docx", data);
        try (OfficeZip zip = OfficeZip.open(docx)) {
            assertTrue(zip.size("/word/media/image1.emf") / (double) docx.toFile().length() > 100);
            assertArrayEquals(metafile, zip.read("/word/media/image1.emf"));
        }
    }

    @Test
    void refusesAPackageThatInflatesTooMuchAsAWhole() throws Exception {
        byte[] data = Fixtures.docx("x");
        for (int i = 0; i < 6; i++) {
            data = Fixtures.zipBomb(data, "word/media/zeros" + i + ".bin", 10L << 20);
        }
        Path many = Fixtures.write(dir, "many.docx", data);
        IOException e = assertThrows(IOException.class, () -> OfficeZip.open(many));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
        Fixtures.Zip z = Fixtures.edit(Fixtures.docx("x"));
        byte[] zeros = new byte[90 << 10];
        for (int i = 0; i < 700; i++) {
            z.put("word/media/z" + i + ".bin", zeros);
        }
        Path small = Fixtures.write(dir, "small.docx", z.bytes());
        IOException parts = assertThrows(IOException.class, () -> OfficeZip.open(small));
        assertTrue(parts.getMessage().contains("zip bomb"), parts.getMessage());
    }

    @Test
    void aDensePartNobodyReadsCostsNothingAndReadingItIsRefused() throws Exception {
        byte[] padding = new byte[3 << 20];
        new java.util.Random(1).nextBytes(padding);
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/media/photo.bin", padding).bytes();
        data = Fixtures.zipBomb(data, "docProps/thumbnail.emf", 200L << 20);
        Path docx = Fixtures.write(dir, "thumb.docx", data);
        try (OfficeZip zip = OfficeZip.open(docx)) {
            assertTrue(zip.read("/word/document.xml").length > 0);
            OfficeZip.DamagedPart e = assertThrows(OfficeZip.DamagedPart.class,
                    () -> zip.open("/docProps/thumbnail.emf"));
            assertEquals("/docProps/thumbnail.emf", e.part());
            assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
            assertTrue(e.getCause() instanceof OfficeZip.Oversized, String.valueOf(e.getCause()));
        }
    }

    @Test
    void denseReadsShareOneBudget() throws Exception {
        byte[] padding = new byte[2 << 20];
        new java.util.Random(2).nextBytes(padding);
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/media/photo.bin", padding).bytes();
        for (int i = 0; i < 3; i++) {
            data = Fixtures.zipBomb(data, "word/media/image" + i + ".emf", 20L << 20);
        }
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "budget.docx", data))) {
            assertEquals(20 << 20, zip.read("/word/media/image0.emf").length);
            assertEquals(20 << 20, zip.read("/word/media/image1.emf").length);
            assertEquals(20 << 20, zip.read("/word/media/image0.emf").length);
            assertThrows(OfficeZip.DamagedPart.class, () -> zip.read("/word/media/image2.emf"));
        }
    }

    @Test
    void leavesOutPartsTheCallerNames() throws Exception {
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/header1.xml", "<broken>").bytes();
        Path docx = Fixtures.write(dir, "left.docx", data);
        try (OfficeZip zip = OfficeZip.open(docx)) {
            OfficeZip.DamagedPart e = assertThrows(OfficeZip.DamagedPart.class, () -> zip.xml("/word/header1.xml"));
            assertEquals("/word/header1.xml", e.part());
            assertTrue(e.getMessage().contains("not well-formed"), e.getMessage());
        }
        try (OfficeZip zip = OfficeZip.open(docx, OfficeZip.Limits.DEFAULT,
                java.util.Set.of("word/HEADER1.xml", "/[Content_Types].xml", "/no/such.xml"))) {
            assertFalse(zip.exists("/word/header1.xml"));
            assertFalse(zip.partNames().contains("/word/header1.xml"));
            assertEquals(java.util.Set.of("/word/HEADER1.xml"), zip.leftOut());
            assertThrows(java.io.FileNotFoundException.class, () -> zip.xml("/word/header1.xml"));
            assertTrue(zip.exists(OfficeZip.CONTENT_TYPES));
        }
    }

    @Test
    void aRefusedDoctypeIsNotADamagedPart() throws Exception {
        byte[] data = Fixtures.edit(Fixtures.docx("x"))
                .put("word/header1.xml", "<!DOCTYPE x [<!ENTITY e \"y\">]><x>&e;</x>").bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "dt.docx", data))) {
            IOException e = assertThrows(IOException.class, () -> zip.xml("/word/header1.xml"));
            assertFalse(e instanceof OfficeZip.DamagedPart, e.toString());
        }
    }

    // Repeated records, the way a metafile of hatching or a flat bitmap looks: deflates past 100 times
    private static byte[] dense(int size) {
        byte[] out = new byte[size];
        byte[] record = {0x0A, 0, 0, 0, 0x10, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8};
        for (int i = 0; i < size; i++) {
            out[i] = record[i % record.length];
        }
        return out;
    }

    @Test
    void enforcesEntryCountAndSizeLimits() throws Exception {
        Path docx = Fixtures.write(dir, "a.docx", Fixtures.docx("a", "b"));
        IOException count = assertThrows(IOException.class,
                () -> OfficeZip.open(docx, new OfficeZip.Limits(3, 1L << 30, 1L << 30, 0.001, 0)));
        assertTrue(count.getMessage().contains("too large"), count.getMessage());
        IOException entry = assertThrows(IOException.class,
                () -> OfficeZip.open(docx, new OfficeZip.Limits(1000, 100, 1L << 30, 0.0, 0)));
        assertTrue(entry.getMessage().contains("too large"), entry.getMessage());
        IOException total = assertThrows(IOException.class,
                () -> OfficeZip.open(docx, new OfficeZip.Limits(1000, 1L << 20, 2000, 0.0, 0)));
        assertTrue(total.getMessage().contains("too large"), total.getMessage());
    }

    @Test
    void refusesPartsLargerThanTheirDeclaredSize() throws Exception {
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/big.xml", "<a>" + "x".repeat(50_000) + "</a>").bytes();
        Path docx = Fixtures.write(dir, "lie.docx", Fixtures.lyingSize(data, "word/big.xml", 100));
        try (OfficeZip zip = OfficeZip.open(docx, new OfficeZip.Limits(1000, 1L << 30, 1L << 30, 0.0, 0))) {
            IOException e = assertThrows(IOException.class, () -> {
                try (InputStream in = zip.open("/word/big.xml")) {
                    in.readAllBytes();
                }
            });
            assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        }
    }

    @Test
    void explainsWhatOtherFilesAre() throws Exception {
        Path empty = Fixtures.write(dir, "empty.docx", new byte[0]);
        assertMessage(empty, "empty");
        Path pdf = Fixtures.write(dir, "doc.docx", "%PDF-1.7\n".getBytes());
        assertMessage(pdf, "PDF");
        Path text = Fixtures.write(dir, "text.docx", "hello world, not a zip".getBytes());
        assertMessage(text, "not a zip");
        Path encrypted = Fixtures.write(dir, "locked.docx", Fixtures.encryptedOle2());
        assertMessage(encrypted, "password");
        Path noTypes = Fixtures.write(dir, "plain.docx", Fixtures.edit(Fixtures.docx("x")).remove("[Content_Types].xml")
                .remove("word/document.xml").put("notes.txt", "x").bytes());
        assertMessage(noTypes, "not an Office document");
        Path odt = Fixtures.write(dir, "doc.odt", Fixtures.edit(Fixtures.docx("x")).remove("[Content_Types].xml")
                .put("mimetype", "application/vnd.oasis.opendocument.text").put("META-INF/manifest.xml", "<m/>").bytes());
        assertMessage(odt, "OpenDocument");
    }

    @Test
    void decodesPercentEscapedPartNames() throws Exception {
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/media/a b.png", Fixtures.png(2, 2, java.awt.Color.BLUE))
                .bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "p.docx", data))) {
            assertArrayEquals(zip.read("/word/media/a b.png"), zip.read("/word/media/a%20b.png"));
        }
        assertEquals("a b\u00e9", Percent.decode("a%20b%C3%A9"));
        assertThrows(IllegalArgumentException.class, () -> Percent.decode("bad%2"));
    }

    private static void assertMessage(Path file, String fragment) {
        IOException e = assertThrows(IOException.class, () -> OfficeZip.open(file));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void aDomPartWithTooManyXmlElementsIsADamagedPart() throws Exception {
        StringBuilder many = new StringBuilder("<n>");
        for (long i = 0; i <= XmlElements.domElements(); i++) {
            many.append("<x a=\"").append(Long.toHexString(i * 7919)).append("\"/>");
        }
        byte[] data = Fixtures.edit(Fixtures.docx("x")).put("word/numbering.xml", many + "</n>").bytes();
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "dense.docx", data))) {
            OfficeZip.DamagedPart e = assertThrows(OfficeZip.DamagedPart.class, () -> zip.xml("/word/numbering.xml"));
            assertEquals("/word/numbering.xml", e.part());
            assertTrue(e.getCause() instanceof OfficeZip.Oversized, String.valueOf(e.getCause()));
            assertNotNull(zip.xml("/word/document.xml"));
        }
    }

    @Test
    void largeDomPartsGetMoreRoomWhenTheMemoryBudgetAllows() {
        long most = XmlElements.domElements();
        assertTrue(most >= XmlElements.MAX_PART && most <= XmlElements.MAX_DOM_PART, String.valueOf(most));
        long share = XmlElements.share(384, 10, Long.MAX_VALUE);
        assertTrue(share <= Admission.jvm().budget() / 2 / 384, String.valueOf(share));
    }
}
