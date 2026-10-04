package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.CloudMetrics;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class ScreenAdvancesTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    static Path dir;

    private static FontLibrary library(String family) throws Exception {
        Path fonts = Files.createDirectories(dir.resolve(family.replace(' ', '_')));
        Path file = fonts.resolve(family + ".ttf");
        if (!Files.exists(file)) {
            Files.write(file, TestFonts.renamed(family));
        }
        return FontLibrary.of(List.of(fonts));
    }

    private static FontSpec calibri(double size, boolean bold) {
        return new FontSpec("Calibri", size, bold, false, null, false, Color.BLACK, null);
    }

    @Test
    void calibrisBitmapWidthsAreKeptWhenCarlitoStandsIn() throws Exception {
        FontLibrary fonts = library("Carlito");
        Typesetter t = new Typesetter(fonts);
        FontFace face = t.face(calibri(11, false));
        assertEquals("Carlito", face.family());
        assertTrue(ScreenAdvances.emulates("Calibri", face));
        String text = "Operational budget 2026";
        int sum = 0;
        for (char c : text.toCharArray()) {
            sum += ScreenAdvances.calibri(false, false, c, 15);
        }
        assertEquals(sum * Typesetter.SCREEN_REGULAR, t.screenWidth(text, calibri(11, false), 15), 1e-9);
        assertEquals(8, ScreenAdvances.calibri(false, false, 'e', 15));
        assertEquals(7, ScreenAdvances.calibri(false, false, '0', 15));
        assertEquals(7, ScreenAdvances.calibri(true, false, '0', 15));
        assertEquals(4, ScreenAdvances.calibri(false, false, 'i', 12));
        assertEquals(ScreenAdvances.calibri(false, false, ' ', 15), ScreenAdvances.calibri(false, false, 0xA0, 15));
        assertEquals(-1, ScreenAdvances.calibri(false, false, 'e', 14));
        assertEquals(-1, ScreenAdvances.calibri(true, false, 'e', 12));
        assertEquals(-1, ScreenAdvances.calibri(false, false, 0x4E2D, 15));
        assertEquals(-1, ScreenAdvances.calibri(false, false, 0xAD, 15));
    }

    @Test
    void sizesWithoutABitmapStrikeUseTheSubstitutesOwnHinting() throws Exception {
        FontLibrary fonts = library("Carlito");
        Typesetter t = new Typesetter(fonts);
        FontFace face = t.face(calibri(10.5, false));
        String text = "Wrap 1234";
        int sum = 0;
        for (char c : text.toCharArray()) {
            sum += face.hintedAdvance(c, 14);
        }
        assertEquals(sum * Typesetter.SCREEN_REGULAR, t.screenWidth(text, calibri(10.5, false), 14), 1e-9);
    }

    @Test
    void anInstalledCalibriIsMeasuredItself() throws Exception {
        FontLibrary fonts = library("Calibri");
        Typesetter t = new Typesetter(fonts);
        FontFace face = t.face(calibri(11, false));
        assertEquals("Calibri", face.family());
        assertFalse(ScreenAdvances.emulates("Calibri", face));
        assertFalse(ScreenAdvances.emulates("Arial", face));
        int sum = 0;
        for (char c : "eee".toCharArray()) {
            sum += face.hintedAdvance(c, 15);
        }
        assertEquals(sum * Typesetter.SCREEN_REGULAR, t.screenWidth("eee", calibri(11, false), 15), 1e-9);
    }

    @Test
    void aMissingCloudFontIsMeasuredWithItsOwnWidths() throws Exception {
        FontLibrary fonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        Typesetter t = new Typesetter(fonts);
        FontSpec aptos = new FontSpec("Aptos Narrow", 11, false, false, null, false, Color.BLACK, null);
        Typesetter.Look look = t.look(aptos);
        assertTrue(look.face().substituted());
        assertTrue(look.emulated());
        CloudMetrics metrics = CloudMetrics.of("Aptos Narrow", false, false);
        String digits = "1234567.89";
        long px = 0;
        long screen = 0;
        for (char c : digits.toCharArray()) {
            px += Math.round(metrics.advance(c) * 92);
            screen += Math.round(metrics.advance(c) * 15);
        }
        assertEquals(px * PrintMetrics.PX, t.width(digits, aptos, 11), 1e-9);
        assertEquals(screen * Typesetter.SCREEN_REGULAR, t.screenWidth(digits, aptos, 15), 1e-9);
        FontSpec arial = new FontSpec("Arial", 11, false, false, null, false, Color.BLACK, null);
        assertFalse(t.look(arial).emulated());
        assertTrue(t.width(digits, aptos, 11) < 0.95 * t.width(digits, arial, 11));
    }

    @Test
    void tinyScreenSizesUseTheSmallSizeRules() {
        assertEquals(Typesetter.screenFactor(true, 9), Typesetter.screenFactor(true, 8), 1e-12);
        assertTrue(Typesetter.screenFactor(true, 8) > Typesetter.screenFactor(true, 10));
        FontMeasure calibri = FontMeasure.of(FontLibrary.of(List.of()), "Calibri", false, false);
        assertEquals(11, calibri.screenLinePx(6));
        assertEquals(12, calibri.screenLinePx(7));
        assertEquals(15, calibri.screenLinePx(8));
    }

    @Test
    void aNarrowerMissingFontIsDrawnAtItsOwnWidth() throws Exception {
        FontLibrary fonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        Typesetter t = new Typesetter(fonts);
        FontSpec narrow = new FontSpec("Arial Narrow", 10, false, false, null, false, Color.BLACK, null);
        FontSpec arial = new FontSpec("Arial", 10, false, false, null, false, Color.BLACK, null);
        Typesetter.Look look = t.look(narrow);
        assertTrue(look.emulated() || look.face().emulated(), look.toString());
        assertNull(look.metrics());
        String text = "Quarterly revenue 2026";
        assertEquals(0.83, t.width(text, narrow, 10) / t.width(text, arial, 10), 0.02);
        assertFalse(t.look(new FontSpec("Tahoma", 10, false, false, null, false, Color.BLACK, null)).emulated());
    }

    @Test
    void installedFontsAreNeverEmulated() throws Exception {
        Typesetter t = new Typesetter(library("Aptos Narrow"));
        FontSpec aptos = new FontSpec("Aptos Narrow", 11, false, false, null, false, Color.BLACK, null);
        assertFalse(t.look(aptos).emulated());
        assertEquals("Aptos Narrow", t.face(aptos).family());
    }

    @Test
    void noBreakSpacesStayInTheCellsFontWhenItHasThem() throws Exception {
        FontFace face = library("Carlito").find("Carlito", false, false);
        String text = "1\u00A0234";
        assertSame(text, Typesetter.spaces(text, face));
        assertSame("1 234", Typesetter.spaces("1 234", face));
    }
}
