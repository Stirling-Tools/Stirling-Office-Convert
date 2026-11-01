package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlatteningTest {

    @TempDir
    Path dir;

    @Test
    void aCheckboxWhoseOnAppearanceIsTranslucentIsFlattenedNotMadeOpaque() throws Exception {
        Path in = Hostile.write(dir, "checkbox", Hostile.translucentCheckbox());
        Path out = dir.resolve("checkbox-1b.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertEquals(List.of(1), r.flattenedPages());
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            int rgb = new PDFRenderer(d).renderImage(0).getRGB(250, 842 - 550);
            assertEquals(0xFF, (rgb >> 16) & 0xFF);
            assertTrue(Math.abs(((rgb >> 8) & 0xFF) - 128) < 8, Integer.toHexString(rgb));
        }
    }

    @Test
    void aPathTooComplexToDrawFailsWithAClearMessage() throws Exception {
        Path in = Hostile.write(dir, "complex", RawPdf.page("/ExtGState<</G<</CA 0.5>>>>",
                "50 w /G gs 0 0 m " + "600 800 l 0 800 l 600 0 l 0 0 l ".repeat(30_000) + "S"));
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(in, dir.resolve("complex-1b.pdf"),
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B)));
        assertTrue(e.getMessage().contains("could not be flattened") && e.getMessage().contains("segments"),
                e.getMessage());
    }

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

    @Test
    void aSoftMaskTransferAppliesOutsideTheMaskGroupBox() throws Exception {
        RawPdf pdf = RawPdf.page("/ExtGState<</GS1 5 0 R>>",
                "q /GS1 gs 0.2 0.6 0.9 rg 0 0 595 842 re f Q");
        pdf.add("<</Type/ExtGState/SMask<</Type/Mask/S/Alpha/G 6 0 R"
                + "/TR<</FunctionType 2/Domain[0 1]/C0[0.5]/C1[1]/N 1>>>>>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[50 50 150 150]"
                + "/Group<</S/Transparency/CS/DeviceGray>>", "1 g 50 50 100 100 re f"));
        Path in = Hostile.write(dir, "mask-transfer", pdf);
        Path out = dir.resolve("mask-transfer-1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            java.awt.image.BufferedImage img = new PDFRenderer(d).renderImage(0);
            int outside = img.getRGB(400, 842 - 600);
            int inside = img.getRGB(100, 842 - 100);
            assertTrue(Math.abs((outside & 0xFF) - 242) < 10 && Math.abs((outside >> 16 & 0xFF) - 153) < 10,
                    Integer.toHexString(outside));
            assertTrue(Math.abs((inside & 0xFF) - 230) < 10 && Math.abs((inside >> 16 & 0xFF) - 51) < 10,
                    Integer.toHexString(inside));
        }
    }
}
