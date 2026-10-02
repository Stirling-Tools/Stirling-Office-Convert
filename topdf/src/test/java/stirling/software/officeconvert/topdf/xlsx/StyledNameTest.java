package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class StyledNameTest {

    @TempDir
    Path dir;

    @Test
    void styleWordsAtTheEndOfAFamilyNameAreSplitOff() {
        assertEquals(new StyledName("Arial", true, false), StyledName.of("Arial Bold"));
        assertEquals(new StyledName("Times New Roman", true, true), StyledName.of("Times New Roman Bold Italic"));
        assertEquals(new StyledName("Verdana", false, true), StyledName.of(" Verdana italic "));
        assertNull(StyledName.of("Arial"));
        assertNull(StyledName.of("Bold"));
        assertNull(StyledName.of(null));
    }

    @Test
    void aMissingBoldNamedFamilyIsDrawnWithItsFamilysBoldFace() throws Exception {
        FontLibrary fonts = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))));
        Typesetter t = new Typesetter(fonts);
        FontSpec named = new FontSpec("Arial Bold", 26, false, false, null, false, Color.BLACK, null);
        Typesetter.Look look = t.look(named);
        assertTrue(look.bold());
        assertEquals("Arial", look.family());
        assertTrue(look.face().bold() || look.face().syntheticBold());
        FontSpec plain = new FontSpec("Arial", 26, true, false, null, false, Color.BLACK, null);
        assertEquals(t.width("Proven reoffending", plain, 26), t.width("Proven reoffending", named, 26), 1e-9);
    }
}
