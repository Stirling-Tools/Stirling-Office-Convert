package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlendingProfilesTest {

    private static final String CONTENT = "/CS0 cs 0 1 1 0 sc 100 600 200 100 re f 0 0 0 1 k 100 400 200 100 re f";

    @TempDir
    Path dir;

    @Test
    void pageGroupRepeatingAProfileUsedOnThePageLosesItsColourSpace() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, CONTENT, FontSamples.trueType(d));
            COSArray space = cmykSpace(d);
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.GROUP);
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, space);
            page.getCOSObject().setItem(COSName.GROUP, group);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem("CS0", space);
            spaces.setItem("DefaultCMYK", space);
            page.getResources().getCOSObject().setItem(COSName.COLORSPACE, spaces);
            d.save(in.toFile());
        }
        assertTrue(VeraPdf.failures(in, PdfALevel.A4).containsKey("6.2.4.2-3"));
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument after = Loader.loadPDF(out.toFile())) {
            COSDictionary page = after.getPage(0).getCOSObject();
            assertNull(((COSDictionary) page.getDictionaryObject(COSName.GROUP)).getDictionaryObject(COSName.CS));
            COSDictionary spaces = colourSpaces(page);
            assertTrue(spaces.getDictionaryObject("CS0") instanceof COSArray);
            assertTrue(spaces.getDictionaryObject("DefaultCMYK") instanceof COSArray);
        }
        assertSameColours(in, out);
    }

    @Test
    void spacesRepeatingACmykOutputIntentBecomeDeviceCmyk() throws Exception {
        Path in = dir.resolve("oi.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, CONTENT, FontSamples.trueType(d));
            COSArray space = cmykSpace(d);
            COSDictionary intent = new COSDictionary();
            intent.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
            intent.setItem(COSName.S, COSName.getPDFName("GTS_PDFA1"));
            intent.setString(COSName.getPDFName("OutputConditionIdentifier"), "CGATS TR 001");
            intent.setItem(COSName.getPDFName("DestOutputProfile"), space.getObject(1));
            COSArray intents = new COSArray();
            intents.add(intent);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OUTPUT_INTENTS, intents);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem("CS0", space);
            page.getResources().getCOSObject().setItem(COSName.COLORSPACE, spaces);
            d.save(in.toFile());
        }
        assertTrue(VeraPdf.failures(in, PdfALevel.A4).containsKey("6.2.4.2-3"));
        Path out = dir.resolve("oi-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument after = Loader.loadPDF(out.toFile())) {
            assertEquals(COSName.DEVICECMYK, colourSpaces(after.getPage(0).getCOSObject()).getDictionaryObject("CS0"));
        }
        assertSameColours(in, out);
    }

    private static COSArray cmykSpace(PDDocument d) throws Exception {
        COSStream profile = d.getDocument().createCOSStream();
        try (OutputStream out = profile.createOutputStream(COSName.FLATE_DECODE)) {
            byte[] printer = IccProfiles.cmyk();
            System.arraycopy("prtr".getBytes(StandardCharsets.US_ASCII), 0, printer, 12, 4);
            out.write(printer);
        }
        profile.setInt(COSName.N, 4);
        profile.setItem(COSName.ALTERNATE, COSName.DEVICECMYK);
        COSArray space = new COSArray();
        space.add(COSName.ICCBASED);
        space.add(profile);
        return space;
    }

    private static COSDictionary colourSpaces(COSDictionary page) {
        return (COSDictionary) ((COSDictionary) page.getDictionaryObject(COSName.RESOURCES))
                .getDictionaryObject(COSName.COLORSPACE);
    }

    private static void assertSameColours(Path in, Path out) throws Exception {
        try (PDDocument before = Loader.loadPDF(in.toFile()); PDDocument after = Loader.loadPDF(out.toFile())) {
            BufferedImage a = new PDFRenderer(before).renderImage(0);
            BufferedImage b = new PDFRenderer(after).renderImage(0);
            for (int[] p : new int[][] {{200, 842 - 650}, {200, 842 - 450}}) {
                int e = a.getRGB(p[0], p[1]);
                int g = b.getRGB(p[0], p[1]);
                for (int shift = 0; shift <= 16; shift += 8) {
                    assertTrue(Math.abs((e >> shift & 0xFF) - (g >> shift & 0xFF)) <= 2,
                            String.format("expected %06X but was %06X", e & 0xFFFFFF, g & 0xFFFFFF));
                }
            }
        }
    }
}
