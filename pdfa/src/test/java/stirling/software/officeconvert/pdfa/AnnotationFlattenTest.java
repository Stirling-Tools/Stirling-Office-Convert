package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnnotationFlattenTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"Polygon", "PolyLine", "Caret"})
    void missingAppearancesAreConstructedBeforeDrawingIntoThePage(String type) throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add("<</Type/Annot/Subtype/" + type + "/Rect[20 20 80 80]/F 4/C[1 0 0]/IC[1 0 0]"
                + "/Vertices[20 20 80 20 50 80]>>");
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<<>>/Contents 4 0 R/Annots[5 0 R]>>");
        Path output = dir.resolve(type + "-generated.pdf");
        PdfToPdfA.convert(Hostile.write(dir, type + "-generated", pdf), output,
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(output, PdfALevel.A1B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var image = new PDFRenderer(doc).renderImage(0);
            int ink = 0;
            for (int y = 20; y < 80; y++) {
                for (int x = 20; x < 80; x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                        ink++;
                    }
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(ink > 5, "The annotation must stay visible");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Polygon", "PolyLine", "Caret"})
    void nonPartOneAnnotationTypesKeepTheirVisibleAppearance(String type) throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add("<</Type/Annot/Subtype/" + type + "/Rect[20 20 80 80]/F 4/AP<</N 6 0 R>>>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 60 60]/Resources<<>>",
                "1 0 0 rg 0 0 60 60 re f"));
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<<>>/Contents 4 0 R/Annots[5 0 R]>>");
        Path output = dir.resolve(type + ".pdf");
        PdfToPdfA.convert(Hostile.write(dir, type, pdf), output,
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(output, PdfALevel.A1B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertFalse(doc.getPage(0).getAnnotations().stream().anyMatch(a -> type.equals(a.getSubtype())));
            assertNotEquals(0xFFFFFFFF, new PDFRenderer(doc).renderImage(0).getRGB(50, 50));
        }
    }

    @ParameterizedTest
    @CsvSource({"Screen,A1B", "Screen,A2B", "Screen,A2U", "Screen,A3B", "Screen,A4", "Movie,A2B", "Sound,A1B",
            "RichMedia,A4", "3D,A2B", "Watermark,A1B"})
    void multimediaAnnotationsLeaveTheirPosterOnThePage(String type, PdfALevel level) throws Exception {
        Path output = poster(type, level, 4);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertFalse(doc.getPage(0).getAnnotations().stream().anyMatch(a -> type.equals(a.getSubtype())));
            assertEquals(0xFFFF0000, new PDFRenderer(doc).renderImage(0).getRGB(50, 50));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 32})
    void hiddenMultimediaAnnotationsAreNotDrawn(int flags) throws Exception {
        Path output = poster("Screen", PdfALevel.A2B, flags);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertEquals(0, doc.getPage(0).getAnnotations().size());
            assertEquals(0xFFFFFFFF, new PDFRenderer(doc).renderImage(0).getRGB(50, 50));
        }
    }

    private Path poster(String type, PdfALevel level, int flags) throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add("<</Type/Annot/Subtype/" + type + "/Rect[20 20 80 80]/F " + flags
                + "/AP<</N 6 0 R>>/A<</S/Rendition/OP 0>>>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 60 60]/Resources<<>>",
                "1 0 0 rg 0 0 60 60 re f"));
        pdf.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]/Resources<<>>/Contents 4 0 R/Annots[5 0 R]>>");
        String name = type + "-" + level + "-" + flags;
        Path output = dir.resolve(name + "-out.pdf");
        PdfToPdfA.convert(Hostile.write(dir, name, pdf), output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        return output;
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1A", "A2A", "A3A"})
    void aPosterDrawnIntoATaggedPageIsAnArtifact(PdfALevel level) throws Exception {
        Path tagged = Samples.write(dir, "s21_tagged");
        Path input = dir.resolve("tagged-screen-" + level + ".pdf");
        try (PDDocument d = Loader.loadPDF(tagged.toFile())) {
            org.apache.pdfbox.pdmodel.PDPage page = d.getPage(0);
            org.apache.pdfbox.cos.COSStream appearance = d.getDocument().createCOSStream();
            appearance.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.XOBJECT);
            appearance.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.FORM);
            appearance.setItem(org.apache.pdfbox.cos.COSName.BBOX, ColourSamples.floats(0, 0, 60, 60));
            try (java.io.OutputStream out = appearance.createOutputStream()) {
                out.write("q 60 0 0 60 0 0 cm BI /W 1 /H 1 /CS /RGB /BPC 8 ID \u00ff\u0000\u0000 EI Q"
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            org.apache.pdfbox.cos.COSDictionary ap = new org.apache.pdfbox.cos.COSDictionary();
            ap.setItem(org.apache.pdfbox.cos.COSName.N, appearance);
            org.apache.pdfbox.cos.COSDictionary screen = new org.apache.pdfbox.cos.COSDictionary();
            screen.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.ANNOT);
            screen.setName(org.apache.pdfbox.cos.COSName.SUBTYPE, "Screen");
            screen.setInt(org.apache.pdfbox.cos.COSName.F, 4);
            screen.setItem(org.apache.pdfbox.cos.COSName.RECT, ColourSamples.floats(20, 20, 80, 80));
            screen.setItem(org.apache.pdfbox.cos.COSName.AP, ap);
            org.apache.pdfbox.cos.COSArray annots = new org.apache.pdfbox.cos.COSArray();
            annots.add(screen);
            page.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.ANNOTS, annots);
            d.save(input.toFile());
        }
        Path output = dir.resolve("tagged-screen-" + level + "-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var image = new PDFRenderer(doc).renderImage(0);
            assertEquals(0xFFFF0000, image.getRGB(50, image.getHeight() - 50));
        }
    }
}
