package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class PartialTextMapTest {

    @Test
    void glyphsTheTextMapLeavesOutAreStillDrawn() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType0Font font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(document, in, false);
            }
            TrueTypeFont ttf = ((PDCIDFontType2) font.getDescendantFont()).getTrueTypeFont();
            int a = ttf.getUnicodeCmapLookup().getGlyphId('a');
            int h = unencoded(ttf);
            String code = String.format("%04X", a);
            String map = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapType 2 def /CMapName /P def "
                    + "1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <" + code + "> <0061> "
                    + "endbfchar endcmap CMapName currentdict /CMap defineresource pop end end";
            COSStream stream = document.getDocument().createCOSStream();
            try (OutputStream out = stream.createOutputStream()) {
                out.write(map.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, stream);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType0Font(font.getCOSObject()));
            page.setResources(resources);
            PDStream content = new PDStream(document);
            try (OutputStream out = content.createOutputStream()) {
                out.write(("BT /F1 30 Tf 72 600 Td <" + code + HexFormat.of().toHexDigits((short) h) + "> Tj ET")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            List<Glyph> glyphs = pages.getFirst().glyphs();
            assertEquals(2, glyphs.size());
            assertEquals("a", glyphs.getFirst().text);
        }
    }

    private static int unencoded(TrueTypeFont ttf) throws IOException {
        for (int gid = ttf.getNumberOfGlyphs() - 1; gid > 0; gid--) {
            var glyph = ttf.getGlyph().getGlyph(gid);
            if (glyph != null && glyph.getNumberOfContours() > 0 && ttf.getUnicodeCmapLookup().getCharCodes(gid) == null) {
                return gid;
            }
        }
        throw new AssertionError("no unencoded glyph");
    }
}
