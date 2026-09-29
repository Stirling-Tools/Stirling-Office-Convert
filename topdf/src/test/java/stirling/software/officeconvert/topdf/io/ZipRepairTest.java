package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class ZipRepairTest {

    @TempDir
    Path dir;

    @Test
    void aPackageCutBeforeItsDirectoryIsRebuiltFromItsLocalHeaders() throws Exception {
        byte[] docx = Fixtures.docx("Hello repaired world");
        Path cut = Fixtures.write(dir, "cut.docx", Arrays.copyOf(docx, directoryOffset(docx)));
        try (OfficeZip zip = OfficeZip.open(cut)) {
            assertTrue(zip.repaired());
            assertEquals("/word/document.xml", zip.mainPart());
            assertTrue(new String(zip.read("/word/document.xml"), StandardCharsets.UTF_8).contains("Hello repaired"));
            assertTrue(zip.notes().stream().anyMatch(n -> n.contains("was repaired from")), zip.notes().toString());
        }
        try (OfficeZip intact = OfficeZip.open(Fixtures.write(dir, "intact.docx", docx))) {
            assertFalse(intact.repaired());
            assertTrue(intact.notes().isEmpty());
        }
    }

    @Test
    void aPartCutShortIsLeftOutAndTheCompleteOnesKept() throws Exception {
        byte[] noise = new byte[40_000];
        new java.util.Random(7).nextBytes(noise);
        byte[] docx = Fixtures.edit(Fixtures.docx("Body")).put("word/media/zz.bin", noise).bytes();
        Path cut = Fixtures.write(dir, "short.docx", Arrays.copyOf(docx, directoryOffset(docx) - 200));
        try (OfficeZip zip = OfficeZip.open(cut)) {
            assertTrue(zip.exists("/word/document.xml"));
            assertFalse(zip.exists("/word/media/zz.bin"));
            assertTrue(zip.notes().stream().anyMatch(n -> n.contains("1 damaged or incomplete parts were left out")),
                    zip.notes().toString());
        }
    }

    @Test
    void anXmlPartCutShortKeepsItsWellFormedStart() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            body.append("<w:p><w:r><w:t>Paragraph ").append(i).append(" of a long document</w:t></w:r></w:p>");
        }
        Map<String, byte[]> parts = parts(Fixtures.docx("x"));
        String main = new String(parts.remove("word/document.xml"), StandardCharsets.UTF_8);
        parts.put("word/document.xml", main.replace("<w:body>", "<w:body>" + body).getBytes(StandardCharsets.UTF_8));
        byte[] zip = sizedZip(parts, null);
        Path cut = Fixtures.write(dir, "main-cut.docx", Arrays.copyOf(zip, zip.length - 3000));
        try (OfficeZip z = OfficeZip.open(cut)) {
            String kept = new String(z.read("/word/document.xml"), StandardCharsets.UTF_8);
            assertTrue(kept.contains("Paragraph 0 of") && !kept.contains("Paragraph 2999 of"), kept.length() + "");
            assertTrue(kept.endsWith("</w:body></w:document>"), kept.substring(kept.length() - 60));
            assertEquals("document", z.xml("/word/document.xml").getDocumentElement().getLocalName());
            assertTrue(z.notes().stream().anyMatch(n -> n.contains("1 parts cut short were kept up to the damage")),
                    z.notes().toString());
        }
    }

    @Test
    void storedPartsAndPartsWithSizesInTheirHeadersAreRecovered() throws Exception {
        Map<String, byte[]> parts = parts(Fixtures.docx("Stored body"));
        Path stored = Fixtures.write(dir, "stored.docx", storedZip(parts));
        try (OfficeZip zip = OfficeZip.open(stored)) {
            assertTrue(zip.repaired());
            assertTrue(new String(zip.read("/word/document.xml"), StandardCharsets.UTF_8).contains("Stored body"));
        }
        Path sized = Fixtures.write(dir, "sized.docx", sizedZip(parts, null));
        try (OfficeZip zip = OfficeZip.open(sized)) {
            assertTrue(zip.repaired());
            assertEquals(parts.size(), zip.partNames().size());
        }
    }

    @Test
    void aPartWhoseDataIsDamagedIsSkippedWhenItsSizeIsKnown() throws Exception {
        Map<String, byte[]> parts = parts(Fixtures.docx("Body"));
        parts.put("customXml/item1.xml", ("<a>" + "x".repeat(3000) + "</a>").getBytes(StandardCharsets.UTF_8));
        parts.put("docProps/app.xml", "<Properties/>".getBytes(StandardCharsets.UTF_8));
        Path damaged = Fixtures.write(dir, "damaged.docx", sizedZip(parts, "customXml/item1.xml"));
        try (OfficeZip zip = OfficeZip.open(damaged)) {
            assertFalse(zip.exists("/customXml/item1.xml"));
            assertTrue(zip.exists("/docProps/app.xml"));
            assertTrue(zip.exists("/word/document.xml"));
        }
    }

    @Test
    void guessedContentTypesSkipNamesThatCannotBeWrittenAsXml() throws Exception {
        Map<String, byte[]> parts = parts(Fixtures.docx("Odd names"));
        parts.remove("[Content_Types].xml");
        parts.put("word/header\"<&.xml", "<w:hdr/>".getBytes(StandardCharsets.UTF_8));
        try (OfficeZip zip = OfficeZip.open(Fixtures.write(dir, "odd.docx", sizedZip(parts, null)))) {
            assertTrue(zip.mainContentType().contains("wordprocessingml.document.main"));
            assertTrue(zip.notes().stream().anyMatch(n -> n.contains("guessed from the part names")), zip.notes().toString());
        }
    }

    @Test
    void aRepeatedNameKeepsTheFirstCopy() throws Exception {
        Map<String, byte[]> parts = parts(Fixtures.docx("First copy"));
        byte[] zip = sizedZip(parts, null);
        byte[] again = sizedZip(Map.of("word/document.xml", "<second/>".getBytes(StandardCharsets.UTF_8)), null);
        byte[] both = new byte[zip.length + again.length];
        System.arraycopy(zip, 0, both, 0, zip.length);
        System.arraycopy(again, 0, both, zip.length, again.length);
        try (OfficeZip z = OfficeZip.open(Fixtures.write(dir, "twice.docx", both))) {
            assertTrue(new String(z.read("/word/document.xml"), StandardCharsets.UTF_8).contains("First copy"));
        }
    }

    @Test
    void aBombWithoutADirectoryIsStillRefusedQuickly() throws Exception {
        byte[] bomb = Fixtures.zipBomb(Fixtures.docx("x"), "word/media/zeros.bin", 400L << 20);
        Path cut = Fixtures.write(dir, "bomb.docx", Arrays.copyOf(bomb, directoryOffset(bomb)));
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> OfficeZip.open(cut));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
        assertTrue(System.nanoTime() - start < 20_000_000_000L);
        OfficeZip.Limits small = new OfficeZip.Limits(3, 512L << 20, 1L << 30, 0.01, 100L << 10, 48L << 20);
        byte[] docx = Fixtures.docx("x");
        Path many = Fixtures.write(dir, "many.docx", Arrays.copyOf(docx, directoryOffset(docx)));
        IOException tooMany = assertThrows(IOException.class, () -> OfficeZip.open(many, small));
        assertTrue(tooMany.getMessage().contains("more than 3 parts"), tooMany.getMessage());
    }

    @Test
    void somethingThatOnlyStartsLikeAZipIsStillNotAPackage() throws Exception {
        byte[] junk = new byte[4096];
        Arrays.fill(junk, (byte) 7);
        junk[0] = 'P';
        junk[1] = 'K';
        junk[2] = 3;
        junk[3] = 4;
        IOException e = assertThrows(IOException.class, () -> OfficeZip.open(Fixtures.write(dir, "junk.docx", junk)));
        assertTrue(e.getMessage().contains("not a valid zip package"), e.getMessage());
        byte[] docx = Fixtures.docx("x");
        Path noTypes = Fixtures.write(dir, "notypes.docx", Arrays.copyOf(docx, 10));
        assertThrows(IOException.class, () -> OfficeZip.open(noTypes));
    }

    @Test
    void theRebuiltCopyIsDeletedWhenThePackageCloses() throws Exception {
        byte[] docx = Fixtures.docx("x");
        Path cut = Fixtures.write(dir, "cut.docx", Arrays.copyOf(docx, directoryOffset(docx)));
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = repairs(tmp);
        try (OfficeZip zip = OfficeZip.open(cut)) {
            assertTrue(zip.repaired());
        }
        assertEquals(before, repairs(tmp));
    }

    private static long repairs(Path tmp) throws IOException {
        try (var files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("office-repair-")).count();
        }
    }

    static int directoryOffset(byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 5 && zip[i + 3] == 6) {
                return ByteBuffer.wrap(zip, i + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
        }
        throw new IllegalArgumentException("no end record");
    }

    private static Map<String, byte[]> parts(byte[] zip) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                parts.put(e.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return parts;
    }

    private static byte[] storedZip(Map<String, byte[]> parts) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> p : parts.entrySet()) {
                ZipEntry e = new ZipEntry(p.getKey());
                e.setMethod(ZipEntry.STORED);
                e.setSize(p.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(p.getValue());
                e.setCrc(crc.getValue());
                out.putNextEntry(e);
                out.write(p.getValue());
                out.closeEntry();
            }
            out.finish();
            byte[] zip = bytes.toByteArray();
            return Arrays.copyOf(zip, directoryOffset(zip));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Local headers that carry their sizes, no directory; the named part's deflate data is scrambled
    private static byte[] sizedZip(Map<String, byte[]> parts, String scramble) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> p : parts.entrySet()) {
            Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            d.setInput(p.getValue());
            d.finish();
            byte[] buf = new byte[p.getValue().length + 1024];
            int n = d.deflate(buf);
            d.end();
            byte[] data = Arrays.copyOf(buf, n);
            if (p.getKey().equals(scramble)) {
                Arrays.fill(data, 0, Math.min(8, data.length), (byte) 0xFF);
            }
            CRC32 crc = new CRC32();
            crc.update(p.getValue());
            byte[] name = p.getKey().getBytes(StandardCharsets.UTF_8);
            ByteBuffer h = ByteBuffer.allocate(30 + name.length).order(ByteOrder.LITTLE_ENDIAN);
            h.putInt(0x04034b50).putShort((short) 20).putShort((short) 0x0800).putShort((short) 8).putShort((short) 0)
                    .putShort((short) 0x21).putInt((int) crc.getValue()).putInt(data.length)
                    .putInt(p.getValue().length).putShort((short) name.length).putShort((short) 0).put(name);
            out.writeBytes(h.array());
            out.writeBytes(data);
        }
        return out.toByteArray();
    }
}
