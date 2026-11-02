package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;

class UndecodableFiltersTest {

    private static final int SIDE = 48;

    @Test
    void aPictureWhoseFilterChainCannotBeDecodedIsLeftAsItWas() throws IOException {
        try (PDDocument doc = page(2)) {
            COSStream image = image(doc);
            byte[] before = raw(image);
            LineArt.convert(doc, LineArt.Options.defaults());
            assertEquals(8, image.getInt(COSName.BITS_PER_COMPONENT));
            assertArrayEquals(before, raw(image));
        }
    }

    @Test
    void theSamePictureWithItsTrueChainIsBinarized() throws IOException {
        try (PDDocument doc = page(1)) {
            COSStream image = image(doc);
            LineArt.convert(doc, LineArt.Options.defaults());
            assertEquals(1, image.getInt(COSName.BITS_PER_COMPONENT));
        }
    }

    private static COSStream image(PDDocument doc) throws IOException {
        return doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im1")).getCOSObject();
    }

    private static byte[] raw(COSStream stream) throws IOException {
        try (InputStream in = stream.createRawInputStream()) {
            return in.readAllBytes();
        }
    }

    private static PDDocument page(int hexFilters) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        PDResources resources = new PDResources();
        page.setResources(resources);
        doc.addPage(page);
        byte[] pixels = new byte[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                pixels[y * SIDE + x] = (byte) ((x / 6 + y / 6) % 2 == 0 ? 20 : 235);
            }
        }
        COSStream image = doc.getDocument().createCOSStream();
        image.setItem(COSName.TYPE, COSName.XOBJECT);
        image.setItem(COSName.SUBTYPE, COSName.IMAGE);
        image.setInt(COSName.WIDTH, SIDE);
        image.setInt(COSName.HEIGHT, SIDE);
        image.setInt(COSName.BITS_PER_COMPONENT, 8);
        image.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        COSArray filters = new COSArray();
        for (int i = 0; i < hexFilters; i++) {
            filters.add(COSName.ASCII_HEX_DECODE);
        }
        image.setItem(COSName.FILTER, filters);
        try (OutputStream out = image.createRawOutputStream()) {
            out.write((HexFormat.of().formatHex(pixels) + ">").getBytes(StandardCharsets.US_ASCII));
        }
        COSDictionary xobjects = new COSDictionary();
        xobjects.setItem(COSName.getPDFName("Im1"), image);
        resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
        PDStream content = new PDStream(doc);
        try (OutputStream out = content.createOutputStream()) {
            out.write("q 200 0 0 200 72 400 cm /Im1 Do Q".getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(content);
        return doc;
    }
}
