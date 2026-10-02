package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class FontLibraryTest {

    @TempDir
    Path dir;

    @Test
    void shapedFileFontsCanBeMovedAndDeletedAfterLoading() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("movable"));
        Path file = Files.write(fonts.resolve("Movable.ttf"), TestFonts.renamed("Movable"));
        FontFace face = FontLibrary.of(List.of(fonts)).find("Movable", false, false);
        assertTrue(face.shapeable());
        assertNotNull(face.glyphOutline(face.glyph('A')));
        Path moved = Files.move(file, fonts.resolve("Moved.ttf"));
        Files.delete(moved);
        assertFalse(Files.exists(moved));
        assertNotNull(face.glyphOutline(face.glyph('B')));
    }

    @Test
    void findsAFaceOnThisMachine() {
        FontLibrary lib = FontLibrary.system();
        assertFalse(lib.isEmpty());
        FontFace arial = lib.find("Arial", false, false);
        assertNotNull(arial);
        assertTrue(arial.covers('A'));
        assertTrue(arial.advance('A') > 0);
        FontFace bold = lib.find("Arial", true, false);
        assertTrue(bold.boldStyle());
        assertSame(lib, FontLibrary.system());
    }

    @Test
    void fallsBackToTheBundledFaceWithNoFontsInstalled() throws Exception {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        FontLibrary lib = FontLibrary.of(List.of(empty));
        FontFace face = lib.find("Calibri", false, false);
        assertEquals("Liberation Sans", face.family());
        assertEquals("Calibri", face.requestedFamily());
        assertTrue(face.substituted());
        assertNotNull(face.note());
        FontFace boldItalic = lib.find("No Such Font", true, true);
        assertEquals("Liberation Sans", boldItalic.family());
        assertTrue(boldItalic.syntheticBold());
        assertTrue(boldItalic.syntheticItalic());
        assertEquals(2048, face.unitsPerEm());
        FontMetrics m = face.metrics();
        assertTrue(m.hheaAscender() > 0 && m.hheaDescender() < 0);
        assertTrue(m.winAscent() > 0 && m.winDescent() > 0);
        assertEquals(m.winLineHeight(12) + m.points(m.externalLeading(), 12), m.gdiLineHeight(12), 1e-4f);
        assertTrue(m.externalLeading() >= 0);
        assertTrue(m.typoAscender() > 0 && m.typoDescender() < 0);
        assertTrue(m.underlineThickness() > 0 && m.underlinePosition() < 0);
        assertTrue(m.strikeoutSize() > 0 && m.strikeoutPosition() > 0);
        assertFalse(face.covers(0x4E2D));
        assertTrue(face.advance(0x4E2D) > 0);
        assertEquals(0, face.advance(0x00AD));
    }

    @Test
    void mapsCalibriToCarlitoWhenCalibriIsAbsent() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("carlito"));
        Files.write(fonts.resolve("Carlito-Regular.ttf"), TestFonts.renamed("Carlito"));
        Files.write(fonts.resolve("Caladea-Regular.ttf"), TestFonts.renamed("Caladea"));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        assertNull(lib.exact("Calibri", false, false));
        FontFace calibri = lib.find("Calibri", false, false);
        assertEquals("Carlito", calibri.family());
        assertEquals("Calibri", calibri.requestedFamily());
        assertTrue(calibri.substituted());
        assertNull(calibri.note(), "Carlito has Calibri's metrics, so nothing moves and nothing is reported");
        FontFace cambria = lib.find("Cambria", false, false);
        assertEquals("Caladea", cambria.family());
        assertEquals("Cambria is not installed; using Caladea", cambria.note());
        assertTrue(lib.find("Calibri", false, true).note().contains("Calibri is not installed"));
        assertEquals("Carlito", lib.find("carlito", false, false).family());
        assertFalse(lib.find("Carlito", false, false).substituted());
        assertEquals("Carlito", lib.find("Carlito", true, false).family());
        assertTrue(lib.find("Carlito", true, false).syntheticBold());
    }

    @Test
    void usesFontsEmbeddedInTheDocument() throws Exception {
        FontLibrary base = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        FontLibrary withDoc = base.withFonts(List.of(TestFonts.renamed("Doc Font")));
        assertEquals("Doc Font", withDoc.find("Doc Font", false, false).family());
        assertEquals("Liberation Sans", base.find("Doc Font", false, false).family());
    }

    @Test
    void substitutesAndRecordsCffFonts() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("cff"));
        Files.write(fonts.resolve("Cff.otf"), TestFonts.renamedCff("Postscript Face"));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.find("Postscript Face", false, false);
        assertEquals("Liberation Sans", face.family());
        assertTrue(face.note().contains("CFF"), face.note());
    }

    @Test
    void splitsTextByCoverage() {
        FontLibrary lib = FontLibrary.system();
        FontFace primary = lib.find("Liberation Sans", false, false);
        List<FontRun> plain = lib.runs("plain text", primary);
        assertEquals(1, plain.size());
        assertEquals("plain text", plain.get(0).text());
        FontFace cjk = lib.fallback(0x4E2D, false, false);
        assumeTrue(cjk != null, "no CJK font on this machine");
        List<FontRun> mixed = lib.runs("ab中文cd", primary);
        assertEquals(3, mixed.size());
        assertEquals("中文", mixed.get(1).text());
        assertTrue(mixed.get(1).face().covers(0x4E2D));
    }

    @Test
    void isSafeToShareAcrossThreads() throws Exception {
        FontLibrary lib = FontLibrary.system();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<FontFace>> jobs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String family = i % 2 == 0 ? "Times New Roman" : "Courier New";
                jobs.add(() -> lib.find(family, false, false));
            }
            FontFace times = null;
            int i = 0;
            for (Future<FontFace> f : pool.invokeAll(jobs)) {
                FontFace face = f.get();
                if (i++ % 2 == 0) {
                    if (times == null) {
                        times = face;
                    }
                    assertSame(times, face);
                }
                assertTrue(face.width("Hello", 12) > 0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void familyNamesFromDocumentsDoNotPileUpInTheSharedLibrary() {
        FontLibrary lib = FontLibrary.system();
        for (int i = 0; i < 20_000; i++) {
            lib.find("Doc Font " + i, false, false);
        }
        assertTrue(lib.cachedFaces() <= 2 * 4096, "cached " + lib.cachedFaces());
        assertEquals("Doc Font 19999", lib.find("Doc Font 19999", false, false).requestedFamily());
    }
}
