package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.TextStyle;

class PptxStandinsTest {

    @TempDir
    Path empty;

    private Standins standins() {
        FontLibrary fonts = FontLibrary.of(List.of(empty));
        return new Standins(new CloudFonts(fonts));
    }

    @Test
    void missingArialNarrowKeepsItsOwnWidthsAndLines() {
        Standins.Emulation e = standins().emulate("Arial Narrow", false, false);
        FontFace face = e.face();
        float scale = face.emulated() ? 1 : e.scale() / 100f;
        assertTrue(face.emulated() || e.scale() < 90);
        assertNotNull(e.vertical());
        assertEquals(0.9219f, e.vertical()[0], 1e-4);
        float m = e.metrics() != null ? e.metrics().advance('m') : face.advance('m') * scale / face.unitsPerEm();
        assertEquals(0.683f, m, 0.004);
    }

    @Test
    void missingWideFacesAreStretchedAndUnknownOnesLeftAlone() {
        Standins s = standins();
        assertTrue(width(s.emulate("Century Gothic", false, false)) > 1.02 * width(s.emulate("Arial", false, false)));
        assertTrue(width(s.emulate("Tw Cen MT Condensed", true, false)) < 0.8 * width(s.emulate("Arial", true, false)));
        Standins.Emulation unknown = s.emulate("Some Unknown Face", false, false);
        assertEquals(100, unknown.scale());
        assertFalse(unknown.face().emulated());
        assertNull(unknown.vertical());
        assertNull(unknown.metrics());
        Standins.Emulation garamond = s.emulate("Garamond", true, true);
        assertTrue(garamond.metrics() != null || garamond.face().emulated());
        Standins.Emulation cambria = s.emulate("Cambria", false, false);
        float one = cambria.metrics() != null ? cambria.metrics().advance('1')
                : cambria.face().advance('1') / (float) cambria.face().unitsPerEm();
        assertEquals(0.554f, one, 0.002);
    }

    // Average advance of a sample in ems as drawn, from the face's own widths and any stand-in scale
    private static float width(Standins.Emulation e) {
        FontFace f = e.face();
        String sample = CloudFonts.SAMPLE;
        float units = 0;
        for (int i = 0; i < sample.length(); i++) {
            units += f.advance(sample.charAt(i));
        }
        return units / f.unitsPerEm() / sample.length() * e.scale() / 100f;
    }

    @Test
    void installedFacesAreNeverScaled() {
        FontLibrary system = FontLibrary.system();
        Standins s = new Standins(new CloudFonts(system));
        for (String f : new String[] {"Arial Narrow", "Century Gothic", "Garamond"}) {
            if (!system.find(f, false, false).substituted()) {
                assertEquals(100, s.emulate(f, false, false).scale(), f);
            }
        }
    }

    @Test
    void heavyFacesAreDrawnWithABoldSubstitute() {
        Standins s = standins();
        FontFace black = s.emulate("Arial Black", false, false).face();
        assertTrue(black.bold() || black.syntheticBold());
        FontFace impact = s.emulate("Impact", true, false).face();
        assertTrue(impact.syntheticBold(), "Office emboldens Impact, which has no bold");
        assertFalse(s.emulate("Century Gothic", false, false).face().syntheticBold());
    }

    @Test
    void emulatedGlyphsFillTheAdvancesOfTheFaceTheyStandInFor() {
        Standins.Emulation e = standins().emulate("Century Gothic", false, false);
        TextStyle style = TextStyle.of(e.face(), 20).horizontalScale(e.scale());
        Piece p = new Piece("go on", style, 20, false, false, 0, null, null, e.vertical(), e.metrics(), null);
        Chars ch = new Chars(List.of(p));
        TextPainter.Placement at = TextPainter.place(ch, 0, ch.length, 10, style, 0);
        float pen = 10;
        for (int i = 0; i < ch.length; i++) {
            assertEquals(pen, at.origin()[i], 1e-3, "each glyph starts at its own pen");
            assertEquals(ch.advances[i], at.natural()[i], 1e-3, "and fills the emulated advance");
            pen += ch.advances[i];
        }
        assertTrue(at.scale()[0] != at.scale()[1], "every glyph has its own width");
    }
}
