package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnicodeGuessTest {

    @TempDir
    Path dir;

    @Test
    void type3ShapesWithoutUnicodeCluesGetPrivateUseValuesNotLetters() throws Exception {
        Path in = Hostile.write(dir, "type3", Hostile.type3Shapes());
        Path out = dir.resolve("type3-2u.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2U));
        VeraPdf.assertCompliant(out, PdfALevel.A2U);
        String text = Converted.text(out).strip();
        assertEquals(4, text.length(), text);
        text.chars().forEach(c -> assertEquals(Character.PRIVATE_USE, Character.getType(c), text));
    }
}
