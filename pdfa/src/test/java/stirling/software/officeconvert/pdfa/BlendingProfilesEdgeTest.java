package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlendingProfilesEdgeTest {

    private static final String GROUP = "/Group<</S/Transparency/CS[/ICCBased 5 0 R]>>";

    private static final String FILL = "/CS0 cs 0 1 1 0 sc 100 600 200 100 re f";

    private static final String CS0 = "/ColorSpace<</CS0[/ICCBased 5 0 R]>>";

    @TempDir
    Path dir;

    @Test
    void indexedBaseRepeatingThePageGroupProfile() throws Exception {
        check(single(GROUP, "/ColorSpace<</CS0[/Indexed[/ICCBased 5 0 R]0<00FFFF00>]>>",
                "/CS0 cs 0 sc 100 600 200 100 re f"), 0);
    }

    @Test
    void separationAlternateRepeatingThePageGroupProfile() throws Exception {
        check(single(GROUP, "/ColorSpace<</CS0[/Separation/Spot[/ICCBased 5 0 R]<</FunctionType 2/Domain[0 1]"
                + "/C0[0 0 0 0]/C1[0 1 1 0]/N 1>>]>>", "/CS0 cs 1 sc 100 600 200 100 re f"), 0);
    }

    @Test
    void shadingRepeatingThePageGroupProfile() throws Exception {
        check(single(GROUP, "/Shading<</Sh0<</ShadingType 2/ColorSpace[/ICCBased 5 0 R]/Coords[100 0 300 0]"
                + "/Function<</FunctionType 2/Domain[0 1]/C0[0 1 1 0]/C1[0 1 1 0]/N 1>>>>>>",
                "q 100 600 200 100 re W n /Sh0 sh Q"), 0);
    }

    @Test
    void type3GlyphRepeatingThePageGroupProfile() throws Exception {
        RawPdf pdf = single(GROUP, "/Font<</T3 6 0 R>>", "BT /T3 100 Tf 100 600 Td (A) Tj ET");
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 1000 1000]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 7 0 R>>/Encoding<</Type/Encoding/Differences[65/a]>>/FirstChar 65/LastChar 65"
                + "/Widths[1000]/Resources<<" + CS0 + ">>>>");
        pdf.add(RawPdf.stream("", "1000 0 d0 /CS0 cs 0 1 1 0 sc 0 0 2000 1000 re f"));
        check(pdf, 0);
    }

    @Test
    void annotationAppearanceRepeatingThePageGroupProfile() throws Exception {
        RawPdf pdf = single(GROUP + "/Annots[6 0 R]", "", "");
        pdf.add("<</Type/Annot/Subtype/Square/Rect[100 600 300 700]/F 4/Contents(box)/AP<</N 7 0 R>>>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 200 100]/Resources<<" + CS0 + ">>",
                "/CS0 cs 0 1 1 0 sc 0 0 200 100 re f"));
        check(pdf, 0);
    }

    @Test
    void cmykDocumentIntentUnderAnRgbPageIntent() throws Exception {
        RawPdf pdf = single("/OutputIntents[7 0 R]", CS0, FILL);
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/OutputIntents[6 0 R]>>");
        pdf.add("<</Type/OutputIntent/S/GTS_PDFA1/OutputConditionIdentifier(CGATS TR 001)/DestOutputProfile 5 0 R>>");
        pdf.add("<</Type/OutputIntent/S/GTS_PDFA1/OutputConditionIdentifier(sRGB)/DestOutputProfile 8 0 R>>");
        pdf.add(RawPdf.stream("/N 3", new String(IccProfiles.srgb(), StandardCharsets.ISO_8859_1)));
        check(pdf, 0);
    }

    @Test
    void groupedFormRepeatingItsOwnProfileUnderAnRgbIntent() throws Exception {
        RawPdf pdf = single("", "/XObject<</Fx0 6 0 R>>", "/Fx0 Do");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 595 842]" + GROUP + "/Resources<<" + CS0 + ">>",
                FILL));
        check(pdf, 0);
    }

    @Test
    void isolatedGroupedFormRepeatingItsOwnProfileUnderAnRgbIntent() throws Exception {
        RawPdf pdf = single("", "/XObject<</Fx0 6 0 R>>", "/Fx0 Do");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 595 842]"
                + "/Group<</S/Transparency/I true/CS[/ICCBased 5 0 R]>>/Resources<<" + CS0 + ">>", FILL));
        check(pdf, 0);
    }

    @Test
    void luminositySoftMaskGroupRepeatingItsOwnProfile() throws Exception {
        RawPdf pdf = single("", "/ExtGState<</GS0<</SMask<</S/Luminosity/G 6 0 R>>>>>>",
                "/GS0 gs 1 0 0 rg 100 600 200 100 re f");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 595 842]" + GROUP + "/Resources<<" + CS0 + ">>",
                "/CS0 cs 0 0 0 0 sc 0 0 595 842 re f"));
        check(pdf, 0);
    }

    private RawPdf single(String pageExtra, String resources, String content) throws Exception {
        RawPdf pdf = new RawPdf();
        pdf.add("<</Type/Catalog/Pages 2 0 R>>");
        pdf.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        pdf.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]" + pageExtra + "/Resources<<" + resources
                + ">>/Contents 4 0 R>>");
        pdf.add(RawPdf.stream("", content));
        pdf.add(profileObject());
        return pdf;
    }

    private static String profileObject() throws Exception {
        byte[] printer = IccProfiles.cmyk();
        System.arraycopy("prtr".getBytes(StandardCharsets.US_ASCII), 0, printer, 12, 4);
        return RawPdf.stream("/N 4", new String(printer, StandardCharsets.ISO_8859_1));
    }

    private void check(RawPdf pdf, int page) throws Exception {
        Path in = dir.resolve("in.pdf");
        Files.write(in, pdf.bytes());
        Map<String, Integer> before = VeraPdf.failures(in, PdfALevel.A4);
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        Map<String, Integer> after = VeraPdf.failures(out, PdfALevel.A4);
        assertTrue(after.isEmpty(), "input failed " + before + ", output failed " + after);
        try (PDDocument a = Loader.loadPDF(in.toFile()); PDDocument b = Loader.loadPDF(out.toFile())) {
            BufferedImage x = new PDFRenderer(a).renderImage(page);
            BufferedImage y = new PDFRenderer(b).renderImage(page);
            int e = x.getRGB(200, 842 - 650);
            int g = y.getRGB(200, 842 - 650);
            for (int shift = 0; shift <= 16; shift += 8) {
                assertTrue(Math.abs((e >> shift & 0xFF) - (g >> shift & 0xFF)) <= 2,
                        String.format("expected %06X but was %06X", e & 0xFFFFFF, g & 0xFFFFFF));
            }
        }
    }
}
