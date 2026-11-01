package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.StrictFilters;

class UndecodableImageTest {

    private static final int SIDE = 32;

    @Test
    void anImageWhoseFilterChainCannotBeDecodedIsLeftOut() throws IOException {
        try (PDDocument doc = page(2)) {
            COSStream image = doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im1"))
                    .getCOSObject();
            assertFalse(StrictFilters.decodable(image));
            assertFalse(media(doc), "the undecodable picture is left out");
        }
    }

    @Test
    void theSameImageWithItsTrueChainIsKept() throws IOException {
        try (PDDocument doc = page(1)) {
            COSStream image = doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im1"))
                    .getCOSObject();
            assertTrue(StrictFilters.decodable(image));
            assertTrue(media(doc), "the picture is kept");
        }
    }

    @Test
    void textChecksFollowTheFilterAlphabets() {
        assertTrue(StrictFilters.hexText("0a 1B\r\n>zz".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(StrictFilters.hexText(new byte[] {0, 0, 0x0C, 0x48, 0x4C}));
        assertTrue(StrictFilters.a85Text("<~9jqo^z~>".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(StrictFilters.a85Text("9jq{".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void anUnknownFilterIsNotDecodable() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream s = raw(doc, COSName.getPDFName("NoSuchDecode"), "abc");
            assertFalse(StrictFilters.decodable(s));
        }
    }

    @Test
    void aLongTextStageIsCheckedPastItsFirstBlock() throws IOException {
        String body = "00".repeat(20_000);
        try (PDDocument doc = new PDDocument()) {
            assertTrue(StrictFilters.decodable(raw(doc, COSName.ASCII_HEX_DECODE, body + ">")));
            assertFalse(StrictFilters.decodable(raw(doc, COSName.ASCII_HEX_DECODE, body + "\u0001>")));
            assertTrue(StrictFilters.decodable(raw(doc, COSName.ASCII85_DECODE, "<~" + "z".repeat(20_000) + "~>")));
            assertFalse(StrictFilters.decodable(raw(doc, COSName.ASCII85_DECODE, "z".repeat(20_000) + "{~>")));
        }
    }

    private static COSStream raw(PDDocument doc, COSName filter, String data) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.FILTER, filter);
        try (OutputStream out = s.createRawOutputStream()) {
            out.write(data.getBytes(StandardCharsets.ISO_8859_1));
        }
        return s;
    }

    private static PDDocument page(int hexFilters) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(PDRectangle.LETTER);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        doc.addPage(page);
        byte[] pixels = new byte[SIDE * SIDE * 3];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 7);
        }
        COSStream image = doc.getDocument().createCOSStream();
        image.setItem(COSName.TYPE, COSName.XOBJECT);
        image.setItem(COSName.SUBTYPE, COSName.IMAGE);
        image.setInt(COSName.WIDTH, SIDE);
        image.setInt(COSName.HEIGHT, SIDE);
        image.setInt(COSName.BITS_PER_COMPONENT, 8);
        image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
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
            out.write(("BT /F1 12 Tf 72 720 Td (A page with a picture) Tj ET q 200 0 0 200 72 400 cm /Im1 Do Q")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(content);
        return doc;
    }

    private static boolean media(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfToDocx.convert(doc, out, PdfToDocx.Options.defaults());
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().startsWith("word/media/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
