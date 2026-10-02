package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlatteningTest {

    @TempDir
    Path dir;

    @Test
    void textInsideAFlattenedTransparencyGroupStaysAsInvisibleText() throws Exception {
        Path in = Hostile.write(dir, "group", Hostile.transparencyGroup());
        Path out = dir.resolve("group-1b.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertEquals(List.of(1), r.flattenedPages());
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        String text = Converted.text(out);
        assertTrue(text.contains("Plain text") && text.contains("Text inside a group"), text);
    }
}
