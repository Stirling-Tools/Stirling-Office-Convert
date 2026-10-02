package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InheritedStateTest {

    @TempDir
    Path dir;

    @Test
    void textInAFormThatInheritsItsCallersFontKeepsItsGlyphs() throws Exception {
        Path in = Hostile.write(dir, "inherit", Hostile.inheritedFont());
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("inherit-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            String text = Converted.text(out);
            assertTrue(text.contains("XYZQWK") && text.contains("AB"), text);
        }
    }
}
