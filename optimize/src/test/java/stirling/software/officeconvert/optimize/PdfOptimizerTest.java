package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfOptimizerTest {

    @TempDir
    Path dir;

    private static final PdfOptimizer.Options REWRITE = PdfOptimizer.Options.defaults().alwaysRewrite(true);

    private Path sample(String name, CompressParameters compression) throws IOException {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            BufferedImage picture = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 90; y++) {
                for (int x = 0; x < 120; x++) {
                    picture.setRGB(x, y, new Color(x * 2, y * 2, (x + y) % 256).getRGB());
                }
            }
            for (int p = 0; p < 3; p++) {
                PDPage page = new PDPage(PDRectangle.A6);
                d.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(d, picture);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.setNonStrokingColor(0.25f, 0.5f, 0.75f);
                    cs.addRect(10.5f, 20.25f, 100, 50.000f);
                    cs.fill();
                    cs.drawImage(image, 20, 100, 120, 90);
                    cs.beginText();
                    cs.setFont(helvetica, 11);
                    cs.newLineAtOffset(12.0f, 250.50f);
                    cs.showText("Page " + (p + 1) + " (with parentheses) and a \\ backslash");
                    cs.endText();
                }
            }
            d.save(in.toFile(), compression);
        }
        return in;
    }

    @Test
    void drawsTheSameAndShrinks() throws Exception {
        Path in = sample("plain", CompressParameters.NO_COMPRESSION);
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(r.rewritten());
        assertTrue(r.outputBytes() < Files.size(in) / 2, r.outputBytes() + " of " + Files.size(in));
        assertEquals(Files.size(out), r.outputBytes());
        Pages.assertSameRendering(in, out);
        assertEquals(1, Pages.streams(out, "Image"), "the three identical pictures are merged");
        assertTrue(r.mergedObjects() >= 2);
        String text = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("/Type/ObjStm"));
        assertTrue(text.contains("/Type/XRef"));
        assertTrue(text.startsWith("%PDF-1.5"));
    }

    @Test
    void everyEffortDrawsTheSame() throws Exception {
        Path in = sample("compressed", CompressParameters.DEFAULT_COMPRESSION);
        for (PdfOptimizer.Effort effort : PdfOptimizer.Effort.values()) {
            Path out = dir.resolve(effort + ".pdf");
            PdfOptimizer.optimize(in, out, REWRITE.effort(effort));
            Pages.assertSameRendering(in, out);
        }
    }

    @Test
    void pagesThatLookAlikeStaySeparatePages() throws Exception {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 5 0 R/Annots[6 0 R]>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 5 0 R/Annots[7 0 R]>>");
        r.add(RawPdf.stream("", "0 0 1 rg 10 10 50 50 re f"));
        r.add("<</Type/Annot/Subtype/Square/Rect[10 10 60 60]/C[1 0 0]>>");
        r.add("<</Type/Annot/Subtype/Square/Rect[10 10 60 60]/C[1 0 0]>>");
        Path in = r.write(dir, "twins");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(2, d.getNumberOfPages());
            assertNotSame(d.getPage(0).getCOSObject(), d.getPage(1).getCOSObject());
            assertNotSame(d.getPage(0).getAnnotations().get(0).getCOSObject(),
                    d.getPage(1).getAnnotations().get(0).getCOSObject());
        }
        Pages.assertSameRendering(in, out);
    }

    @Test
    void unusedResourcesAreDroppedButDefaultColourSpacesStay() throws Exception {
        Path in = RawPdf.page("/Font<<" + RawPdf.helvetica("F1") + RawPdf.helvetica("F2")
                + ">>/ColorSpace<</DefaultRGB/DeviceRGB/CS9/DeviceGray>>",
                "BT /F1 12 Tf 10 10 Td (Hi) Tj ET").write(dir, "resources");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDResources res = d.getPage(0).getResources();
            assertNotNull(res.getFont(COSName.getPDFName("F1")));
            assertFalse(res.getCOSObject().getCOSDictionary(COSName.FONT).containsKey("F2"));
            COSDictionary spaces = res.getCOSObject().getCOSDictionary(COSName.COLORSPACE);
            assertTrue(spaces.containsKey("DefaultRGB"));
            assertFalse(spaces.containsKey("CS9"));
        }
        Pages.assertSameRendering(in, out);
    }

    @Test
    void resourcesStayWhenTheContentHoldsAnInlineImage() throws Exception {
        Path in = RawPdf.page("/Font<<" + RawPdf.helvetica("F1") + RawPdf.helvetica("F2") + ">>",
                "q 10 0 0 10 50 50 cm BI /W 2 /H 2 /CS /G /BPC 8 ID \u0000ÿÿ\u0000 EI Q").write(dir,
                        "inline");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT).containsKey("F2"));
        }
        Pages.assertSameRendering(in, out);
    }

    @Test
    void privateDataGoesUnlessKept() throws Exception {
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R/PieceInfo<</Illustrator<</Private 5 0 R>>>>>>");
        r.add(RawPdf.stream("", "x".repeat(5000)));
        Path in = r.write(dir, "piece");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertFalse(d.getPage(0).getCOSObject().containsKey("PieceInfo"));
        }
        PdfOptimizer.optimize(in, out, REWRITE.keepPrivateData(true));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getPage(0).getCOSObject().containsKey("PieceInfo"));
        }
    }

    @Test
    void aBrokenCrossReferenceTableIsRebuilt() throws Exception {
        byte[] good = RawPdf.page("/Font<<" + RawPdf.helvetica("F1") + ">>", "BT /F1 12 Tf 10 10 Td (Hi) Tj ET")
                .bytes();
        String s = new String(good, StandardCharsets.ISO_8859_1);
        int at = s.indexOf("xref\n");
        String broken = s.substring(0, at) + "xref\n0 5\n0000000000 65535 f\r\n0000000999 00000 n\r\n"
                + "0000000888 00000 n\r\n0000000777 00000 n\r\n0000000666 00000 n\r\n"
                + s.substring(s.indexOf("trailer"));
        Path in = dir.resolve("broken.pdf");
        Files.write(in, broken.getBytes(StandardCharsets.ISO_8859_1));
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        Pages.assertSameRendering(in, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(1, d.getNumberOfPages());
        }
    }

    @Test
    void aStreamThatDoesNotDecodeCleanlyIsCopiedAsItIs() throws Exception {
        byte[] full = deflate("0 0 1 rg 10 10 50 50 re f\n".repeat(50).getBytes(StandardCharsets.ISO_8859_1));
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 9);
        RawPdf r = RawPdf.page("", "");
        r.objects.set(3, RawPdf.stream("/Filter/FlateDecode", cut));
        Path in = r.write(dir, "cut");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile());
                InputStream raw = ((COSStream) d.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS))
                        .createRawInputStream()) {
            assertArrayEquals(cut, raw.readAllBytes());
        }
    }

    @Test
    void aDecompressionBombIsNotExpanded() throws Exception {
        byte[] zeros = new byte[(int) Recoder.MAX_DECODED_BYTES + (1 << 20)];
        byte[] bomb = deflate(zeros);
        RawPdf r = RawPdf.page("/XObject<</B 5 0 R>>", "q 10 0 0 10 0 0 cm /B Do Q");
        r.add(RawPdf.stream("/Type/XObject/Subtype/Image/Width 10/Height 10/ColorSpace/DeviceGray/BitsPerComponent 8"
                + "/Filter/FlateDecode", bomb));
        Path in = r.write(dir, "bomb");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        assertTrue(Files.size(out) < Files.size(in) + 4096);
    }

    @Test
    void cyclesEndTheWalk() throws Exception {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R/Loop 5 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R 2 0 R]/Count 1>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>");
        r.add(RawPdf.stream("", "0 0 1 rg 10 10 50 50 re f"));
        r.add("[6 0 R 5 0 R]");
        r.add("<</Back 5 0 R/Self 6 0 R>>");
        Path in = r.write(dir, "cycles");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary loop = (COSDictionary) d.getDocumentCatalog().getCOSObject().getCOSArray(COSName.getPDFName("Loop"))
                    .getObject(0);
            assertNotNull(loop);
        }
    }

    @Test
    void deepNestingEndsCleanly() throws Exception {
        int depth = 20_000;
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/Deep " + "[".repeat(depth) + "]".repeat(depth) + ">>");
        Path in = r.write(dir, "deep");
        Path out = dir.resolve("out.pdf");
        try {
            PdfOptimizer.optimize(in, out, REWRITE);
            Pages.assertSameRendering(in, out);
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    void encryptedDocumentsAreRefused() throws Exception {
        Path in = dir.resolve("locked.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            d.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
            d.save(in.toFile());
        }
        IOException e = assertThrows(IOException.class, () -> PdfOptimizer.optimize(in, dir.resolve("out.pdf")));
        assertTrue(e.getMessage().contains("encrypted"));
        assertFalse(Files.exists(dir.resolve("out.pdf")));
    }

    @Test
    void pdfA1KeepsAClassicCrossReferenceTable() throws Exception {
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">"
                + "<pdfaid:part>1</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description></rdf:RDF>"
                + "</x:xmpmeta>";
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        r.add(RawPdf.stream("/Type/Metadata/Subtype/XML", xmp));
        Path in = r.write(dir, "pdfa1");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE);
        String text = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("/ObjStm"));
        assertTrue(text.contains("\nxref\n"));
        assertTrue(text.startsWith("%PDF-1.4"));
        assertTrue(text.contains("<pdfaid:part>1</pdfaid:part>"), "the metadata stays uncompressed");
    }

    @Test
    void aSlowDocumentIsStoppedAtTheTimeout() throws Exception {
        Path in = dir.resolve("noise.pdf");
        try (PDDocument d = new PDDocument()) {
            BufferedImage noise = new BufferedImage(1500, 1500, BufferedImage.TYPE_INT_RGB);
            Random random = new Random(7);
            for (int y = 0; y < 1500; y++) {
                for (int x = 0; x < 1500; x++) {
                    noise.setRGB(x, y, random.nextInt());
                }
            }
            PDPage page = new PDPage();
            d.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.drawImage(LosslessFactory.createFromImage(d, noise), 0, 0, 500, 500);
            }
            d.save(in.toFile());
        }
        assertThrows(PdfOptimizer.TimedOut.class, () -> PdfOptimizer.optimize(in, dir.resolve("out.pdf"),
                REWRITE.effort(PdfOptimizer.Effort.MAX).timeout(Duration.ofMillis(5))));
        assertFalse(Files.exists(dir.resolve("out.pdf")));
    }

    @Test
    void anOutputThatWouldNotBeSmallerIsACopyOfTheInput() throws Exception {
        Path in = sample("first", CompressParameters.NO_COMPRESSION);
        Path once = dir.resolve("once.pdf");
        PdfOptimizer.optimize(in, once, REWRITE);
        Path twice = dir.resolve("twice.pdf");
        PdfOptimizer.Result r = PdfOptimizer.optimize(once, twice);
        if (!r.rewritten()) {
            assertArrayEquals(Files.readAllBytes(once), Files.readAllBytes(twice));
        }
        assertTrue(Files.size(twice) <= Files.size(once));
    }

    @Test
    void signedDocumentsAreWarnedAbout() throws Exception {
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.set(1, "<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[5 0 R]/SigFlags 3>>>>");
        r.add("<</FT/Sig/T(Signature1)/V 6 0 R/Rect[0 0 0 0]/Subtype/Widget/Type/Annot>>");
        r.add("<</Type/Sig/Filter/Adobe.PPKLite/ByteRange[0 10 20 10]/Contents<00>>>");
        Path in = r.write(dir, "signed");
        PdfOptimizer.Result result = PdfOptimizer.optimize(in, dir.resolve("out.pdf"), REWRITE);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("signatures")));
    }

    @Test
    void namesKeepTheirOriginalBytes() throws Exception {
        Path in = RawPdf.page("/Font<</F#e9 <</Type/Font/Subtype/Type1/BaseFont/Helvetica>>>>",
                "BT /F#e9 12 Tf 10 10 Td (Hi) Tj ET").write(dir, "names");
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, REWRITE.effort(PdfOptimizer.Effort.FAST));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary fonts = d.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
            assertArrayEquals(new byte[] {'F', (byte) 0xE9}, fonts.keySet().iterator().next().getBytes());
        }
        Pages.assertSameRendering(in, out);
    }

    @Test
    void theDocumentApiWritesToAStream() throws Exception {
        Path in = sample("api", CompressParameters.DEFAULT_COMPRESSION);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            PdfOptimizer.Result r = PdfOptimizer.optimize(d, out, PdfOptimizer.Options.defaults());
            assertEquals(out.size(), r.outputBytes());
        }
        Path written = dir.resolve("api-out.pdf");
        Files.write(written, out.toByteArray());
        Pages.assertSameRendering(in, written);
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(b, new Deflater(9))) {
            z.write(data);
        }
        return b.toByteArray();
    }
}
