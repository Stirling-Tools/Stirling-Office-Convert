package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class FontSetTest {

    @TempDir
    Path dir;

    private Path fonts(String name, String... families) throws IOException {
        Path d = Files.createDirectories(dir.resolve(name));
        for (String f : families) {
            Files.write(d.resolve(f.replace(" ", "") + ".ttf"), TestFonts.renamed(f));
        }
        return d;
    }

    static byte[] withFsType(byte[] ttf, int fsType) {
        byte[] out = ttf.clone();
        ByteBuffer b = ByteBuffer.wrap(out);
        int tables = b.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            if (new String(out, at, 4, StandardCharsets.ISO_8859_1).equals("OS/2")) {
                b.putShort(b.getInt(at + 8) + 8, (short) fsType);
                return out;
            }
        }
        throw new IllegalArgumentException("no OS/2 table");
    }

    @Test
    void findsFontsInAFolderWithoutTheSystemFonts() throws Exception {
        FontSet set = FontSet.builder().directory(fonts("a", "Brand Sans")).systemFonts(false).build();
        FontLibrary lib = set.library();
        assertEquals("Brand Sans", lib.find("Brand Sans", false, false).family());
        assertFalse(lib.find("Brand Sans", false, false).substituted());
        for (String family : lib.families()) {
            assertTrue(family.equals("Brand Sans") || family.equals("Liberation Sans"), family);
        }
        assertEquals("Liberation Sans", lib.find("Arial", false, false).family());
        assertTrue(set.problems().isEmpty(), set.problems().toString());
        assertFalse(set.systemFonts());
    }

    @Test
    void keepsTheSystemFontsUnlessToldNot() throws Exception {
        assumeTrue(FontLibrary.system().size() > 1, "no system fonts here");
        FontSet set = FontSet.builder().directory(fonts("b", "Brand Sans")).build();
        assertEquals("Brand Sans", set.library().find("Brand Sans", false, false).family());
        assertTrue(set.library().size() > FontLibrary.system().size());
    }

    @Test
    void equalSetsShareOneLibraryAndOneScanOfEachFile() throws Exception {
        Path d = fonts("c", "Brand Sans");
        FontSet a = FontSet.builder().directory(d).systemFonts(false).build();
        FontSet b = FontSet.builder().directory(d).systemFonts(false).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertSame(a.library(), b.library());
        FontSet mapped = a.toBuilder().substitute("Calibri", "Brand Sans").build();
        assertNotEquals(a, mapped);
        assertTrue(mapped.library() != a.library());
        assertSame(a.library().entries().get(0), mapped.library().entries().get(0));
    }

    @Test
    void aNewSetSeesFontsAddedToTheFolderAndAChangedFileIsScannedAgain() throws Exception {
        Path d = fonts("d", "Brand Sans");
        FontSet before = FontSet.builder().directory(d).systemFonts(false).build();
        assertNull(before.library().exact("Second Sans", false, false));
        Files.write(d.resolve("second.ttf"), TestFonts.renamed("Second Sans"));
        assertNull(before.library().exact("Second Sans", false, false));
        FontSet after = FontSet.builder().directory(d).systemFonts(false).build();
        assertNotNull(after.library().exact("Second Sans", false, false));
        Files.write(d.resolve("second.ttf"), TestFonts.renamed("Third Sans"));
        Files.setLastModifiedTime(d.resolve("second.ttf"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        FontSet changed = FontSet.builder().directory(d).systemFonts(false).build();
        assertNull(changed.library().exact("Second Sans", false, false));
        assertNotNull(changed.library().exact("Third Sans", false, false));
    }

    @Test
    void takesFontsAsBytes() {
        byte[] data = TestFonts.renamed("Data Sans");
        FontSet set = FontSet.builder().font(data).systemFonts(false).build();
        data[0] = 0;
        assertEquals("Data Sans", set.library().find("Data Sans", false, false).family());
        assertEquals(1, set.fontDataCount());
        assertEquals(set, FontSet.builder().font(TestFonts.renamed("Data Sans")).systemFonts(false).build());
        assertThrows(IllegalArgumentException.class,
                () -> FontSet.builder().font(new byte[(int) FontSet.MAX_FONT_BYTES + 1]));
    }

    @Test
    void mapsARequestedFamilyToAnInstalledOneWithoutAWarning() throws Exception {
        FontSet set = FontSet.builder().directory(fonts("e", "Brand Sans")).systemFonts(false)
                .substitute("Aptos", "Brand Sans").build();
        FontFace aptos = set.library().find("Aptos", false, false);
        assertEquals("Brand Sans", aptos.family());
        assertEquals("Aptos", aptos.requestedFamily());
        assertNull(aptos.note());
        FontFace bold = set.library().find("Aptos", true, false);
        assertEquals("Brand Sans", bold.family());
        assertTrue(bold.boldStyle());
        FontSet missing = FontSet.builder().directory(fonts("e2", "Brand Sans")).systemFonts(false)
                .substitute("Aptos", "Not Installed").build();
        assertNotNull(missing.library().find("Aptos", false, false).note());
    }

    @Test
    void scalesTheWidthsOfASubstitutedFamily() throws Exception {
        Path d = fonts("f", "Brand Sans");
        FontLibrary plain = FontSet.builder().directory(d).systemFonts(false).substitute("Odd Font", "Brand Sans")
                .build().library();
        FontLibrary scaled = FontSet.builder().directory(d).systemFonts(false).substitute("Odd Font", "Brand Sans")
                .widthScale("Odd Font", 0.8f).build().library();
        int own = plain.find("Odd Font", false, false).advance('M');
        int narrow = scaled.find("Odd Font", false, false).advance('M');
        assertEquals(Math.round(own * 0.8f), narrow, 1);
        assertEquals(0.8f, scaled.widthScale("odd  font"), 1e-6);
        assertEquals(0f, plain.widthScale("Odd Font"));
        assertEquals(own, scaled.find("Brand Sans", false, false).advance('M'));
        assertThrows(IllegalArgumentException.class, () -> FontSet.builder().widthScale("A", 3f));
        assertThrows(IllegalArgumentException.class, () -> FontSet.builder().widthScale("A", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> FontSet.builder().substitute(" ", "B"));
    }

    @Test
    void neverUsesAFontWhoseLicenceForbidsEmbedding() throws Exception {
        Path d = Files.createDirectories(dir.resolve("g"));
        Files.write(d.resolve("restricted.ttf"), withFsType(TestFonts.renamed("Locked Sans"), 0x0002));
        Files.write(d.resolve("bitmap.ttf"), withFsType(TestFonts.renamed("Bitmap Sans"), 0x0200));
        Files.write(d.resolve("print.ttf"), withFsType(TestFonts.renamed("Print Sans"), 0x0004));
        Files.write(d.resolve("editable.ttf"), withFsType(TestFonts.renamed("Edit Sans"), 0x0008));
        Files.write(d.resolve("mixed.ttf"), withFsType(TestFonts.renamed("Mixed Sans"), 0x0006));
        FontSet set = FontSet.builder().directory(d).systemFonts(false).build();
        FontFace locked = set.library().find("Locked Sans", false, false);
        assertEquals("Liberation Sans", locked.family());
        assertTrue(locked.note().contains("licence does not permit embedding"), locked.note());
        assertEquals("Liberation Sans", set.library().find("Bitmap Sans", false, false).family());
        assertEquals("Print Sans", set.library().find("Print Sans", false, false).family());
        assertEquals("Edit Sans", set.library().find("Edit Sans", false, false).family());
        assertEquals("Mixed Sans", set.library().find("Mixed Sans", false, false).family());
        assertEquals(2, set.problems().size(), set.problems().toString());
        assertTrue(set.problems().stream().anyMatch(p -> p.contains("restricted.ttf") && p.contains("Locked Sans")));
        FontSet mapped = set.toBuilder().substitute("Aptos", "Locked Sans").build();
        assertEquals("Liberation Sans", mapped.library().find("Aptos", false, false).family());
    }

    @Test
    void skipsBrokenOversizedAndLinkedFilesWithAProblemAndNoException() throws Exception {
        Path d = fonts("h", "Good Sans");
        Files.write(d.resolve("garbage.ttf"), "not a font at all".getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("empty.otf"), new byte[0]);
        byte[] good = TestFonts.renamed("Cut Sans");
        Files.write(d.resolve("truncated.ttf"), java.util.Arrays.copyOf(good, good.length / 3));
        Files.write(d.resolve("readme.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("cff.otf"), TestFonts.renamedCff("Cff Sans"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.write(outside.resolve("secret.ttf"), TestFonts.renamed("Linked Sans"));
        boolean linked;
        try {
            Files.createSymbolicLink(d.resolve("link.ttf"), outside.resolve("secret.ttf"));
            Files.createSymbolicLink(d.resolve("linkdir"), outside);
            linked = true;
        } catch (IOException | UnsupportedOperationException e) {
            linked = false;
        }
        FontSet set = FontSet.builder().directory(d).directory(dir.resolve("missing")).systemFonts(false).build();
        FontLibrary lib = assertDoesNotThrow(set::library);
        assertEquals("Good Sans", lib.find("Good Sans", false, false).family());
        assertEquals("Liberation Sans", lib.find("Cff Sans", false, false).family());
        if (linked) {
            assertNull(lib.exact("Linked Sans", false, false));
            assertTrue(set.problems().stream().anyMatch(p -> p.contains("link.ttf")), set.problems().toString());
        }
        String all = String.join("\n", set.problems());
        assertTrue(all.contains("garbage.ttf"), all);
        assertTrue(all.contains("cff.otf") && all.contains("CFF"), all);
        assertTrue(all.contains("missing is not a folder"), all);
        assertFalse(all.contains("readme.txt"), all);
    }

    @Test
    void damagedFontsNeverBreakTextLayout() throws Exception {
        byte[] good = TestFonts.renamed("Fuzz Sans");
        Random random = new Random(42);
        for (int round = 0; round < 60; round++) {
            byte[] bad = good.clone();
            int flips = 1 + random.nextInt(200);
            for (int i = 0; i < flips; i++) {
                bad[random.nextInt(bad.length)] = (byte) random.nextInt(256);
            }
            FontSet set = FontSet.builder().font(bad).systemFonts(false).build();
            boolean bold = round % 2 == 0;
            assertDoesNotThrow(() -> {
                FontLibrary lib = set.library();
                FontFace f = lib.find("Fuzz Sans", bold, false);
                for (FontRun r : lib.runs("Hello, world 123", f)) {
                    for (int i = 0; i < r.text().length(); i++) {
                        r.face().advance(r.text().charAt(i));
                    }
                }
            }, "round " + round);
        }
    }

    @Test
    void isSafeToShareAcrossThreads() throws Exception {
        Path d = fonts("t", "Shared Sans");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<FontLibrary>> work = new ArrayList<>();
            FontSet set = FontSet.builder().directory(d).systemFonts(false).build();
            for (int i = 0; i < 32; i++) {
                work.add(() -> {
                    FontLibrary lib = set.library();
                    lib.find("Shared Sans", false, false).advance('x');
                    return lib;
                });
            }
            FontLibrary first = null;
            for (Future<FontLibrary> f : pool.invokeAll(work)) {
                if (first == null) {
                    first = f.get();
                }
                assertSame(first, f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSystemSetIsTheSystemLibrary() {
        assertSame(FontLibrary.system(), FontSet.system().library());
        assertSame(FontSet.system(), FontSet.system().withDirectories(List.of()));
        assertTrue(FontSet.system().problems().isEmpty());
    }
}
