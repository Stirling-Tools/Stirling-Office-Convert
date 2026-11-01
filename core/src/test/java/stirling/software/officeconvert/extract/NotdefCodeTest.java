package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class NotdefCodeTest {

    @Test
    void codesTheEncodingNamesNotdefShowNoText() throws IOException {
        assertEquals("2.Eat", text(new byte[] {(byte) 0xC2, (byte) 0xA0, '2', '.', 'E', 'a', 't'}));
    }

    private static String text(byte[] shown) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(46));
            differences.add(COSName.getPDFName("period"));
            differences.add(COSInteger.get(50));
            differences.add(COSName.getPDFName("two"));
            differences.add(COSInteger.get(69));
            differences.add(COSName.getPDFName("E"));
            differences.add(COSInteger.get(97));
            differences.add(COSName.getPDFName("a"));
            differences.add(COSInteger.get(116));
            differences.add(COSName.getPDFName("t"));
            differences.add(COSInteger.get(160));
            differences.add(COSName.getPDFName(".notdef"));
            differences.add(COSInteger.get(194));
            differences.add(COSName.getPDFName(".notdef"));
            COSDictionary encoding = new COSDictionary();
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
            dict.setName(COSName.BASE_FONT, "Helvetica");
            dict.setItem(COSName.ENCODING, encoding);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(dict));
            page.setResources(resources);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write("BT /F1 18 Tf 14 600 Td (".getBytes(StandardCharsets.US_ASCII));
                out.write(shown);
                out.write(") Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            StringBuilder sb = new StringBuilder();
            for (Glyph g : pages.getFirst().glyphs()) {
                sb.append(g.text);
            }
            return sb.toString().strip();
        }
    }
}
