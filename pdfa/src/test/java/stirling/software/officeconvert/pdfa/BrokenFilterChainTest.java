package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;


class BrokenFilterChainTest {

    @TempDir
    Path dir;

    private static COSStream hexTwice(PDDocument doc, byte[] data) throws Exception {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createRawOutputStream()) {
            out.write((HexFormat.of().withUpperCase().formatHex(data) + ">").getBytes(StandardCharsets.US_ASCII));
        }
        COSArray filters = new COSArray();
        filters.add(COSName.ASCII_HEX_DECODE);
        filters.add(COSName.ASCII_HEX_DECODE);
        s.setItem(COSName.FILTER, filters);
        return s;
    }

    private static COSStream image(PDDocument doc, int components, byte value) throws Exception {
        byte[] pixels = new byte[8 * 8 * components];
        Arrays.fill(pixels, value);
        for (int i = 0; components == 3 && i < pixels.length; i += 3) {
            pixels[i] = (byte) 255;
        }
        COSStream s = hexTwice(doc, pixels);
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, 8);
        s.setInt(COSName.HEIGHT, 8);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, components == 3 ? COSName.DEVICERGB : COSName.DEVICEGRAY);
        return s;
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A2U", "A3B", "A4"})
    void aProfileWhoseFilterChainCannotDecodeIsReplaced(PdfALevel level) throws Exception {
        Path input = dir.resolve("icc.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream profile = hexTwice(doc, IccProfiles.srgb());
            profile.setInt(COSName.N, 3);
            COSArray space = new COSArray();
            space.add(COSName.ICCBASED);
            space.add(profile);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem("CS0", space);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.COLORSPACE, spaces);
            page.setResources(resources);
            Samples.raw(page, doc, "/CS0 cs 1 0 0 sc 0 0 200 200 re f");
            doc.save(input.toFile());
        }
        Path output = dir.resolve("icc-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            COSDictionary spaces = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.COLORSPACE);
            COSStream profile = (COSStream) ((COSArray) spaces.getDictionaryObject("CS0")).getObject(1);
            assertEquals(COSName.FLATE_DECODE, profile.getDictionaryObject(COSName.FILTER));
            assertEquals("RGB ", IccProfiles.header(ColourFixer.read(profile)).colourSpace());
        }
    }

    @Test
    void flatteningDoesNotRevealAnImageThatCannotBeDecoded() throws Exception {
        Path input = dir.resolve("masked.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream picture = image(doc, 3, (byte) 0);
            picture.setItem(COSName.SMASK, image(doc, 1, (byte) 200));
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem("Im0", picture);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
            page.setResources(resources);
            Samples.raw(page, doc, "q 300 0 0 300 100 300 cm /Im0 Do Q");
            doc.save(input.toFile());
        }
        Path output = dir.resolve("masked-out.pdf");
        PdfToPdfA.Result result = PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertFalse(result.flattenedPages().isEmpty());
        VeraPdf.assertCompliant(output, PdfALevel.A1B);
        assertEquals(0, red(ColourRenderingTest.render(output)));
    }

    private static int red(BufferedImage image) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                if ((p >> 16 & 255) > 200 && (p >> 8 & 255) < 120) {
                    n++;
                }
            }
        }
        return n;
    }
}
