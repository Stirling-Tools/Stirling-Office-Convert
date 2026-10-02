package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoftMaskRecursionTest {

    @TempDir
    Path dir;

    @Test
    void selfReferencingSoftMaskFailsWithoutRecursiveRendering() throws Exception {
        RawPdf pdf = RawPdf.page("/ExtGState<</G 5 0 R>>", "/G gs 0 0 50 50 re f");
        pdf.add("<</SMask<</S/Luminosity/G 6 0 R>>>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 50 50]"
                + "/Group<</S/Transparency/CS/DeviceRGB>>/Resources<</ExtGState<</G 5 0 R>>>>",
                "/G gs 1 g 0 0 50 50 re f"));
        Path input = Hostile.write(dir, "mask", pdf);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                () -> PdfToPdfA.convert(input, dir.resolve("out.pdf"),
                        PdfToPdfA.Options.defaults().level(PdfALevel.A1B).timeout(Duration.ofSeconds(2)))));
    }
}
