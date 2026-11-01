package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class CollectionTextTest {

    private static final String EMPTY_MAP = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
            + "/CMapType 2 def /CMapName /R1 def 1 begincodespacerange <00> <FF> endcodespacerange "
            + "endcmap CMapName currentdict /CMap defineresource pop end end";

    @Test
    void codesTheTextMapLeavesOutAreReadThroughTheFontsCharacterCollection() throws IOException {
        assertEquals("嘆囈", text("Japan1", EMPTY_MAP));
    }

    @Test
    void aFontOutsideTheCjkCollectionsKeepsWhatItHad() throws IOException {
        assertEquals("୰ᅧ", text("Identity", EMPTY_MAP));
    }

    @Test
    void codesTheTextMapCoversKeepItsText() throws IOException {
        String map = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapType 2 def /CMapName /R1 def "
                + "1 begincodespacerange <0000> <FFFF> endcodespacerange 2 beginbfchar <0B70> <0B70> <1167> <1167> "
                + "endbfchar endcmap CMapName currentdict /CMap defineresource pop end end";
        assertEquals("୰ᅧ", text("Japan1", map));
    }

    private static String text(String ordering, String toUnicode) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType0Font font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(document, in, false);
            }
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, ordering);
            info.setInt(COSName.SUPPLEMENT, 0);
            font.getDescendantFont().getCOSObject().setItem(COSName.CIDSYSTEMINFO, info);
            COSStream map = document.getDocument().createCOSStream();
            try (OutputStream out = map.createOutputStream()) {
                out.write(toUnicode.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, map);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType0Font(font.getCOSObject()));
            page.setResources(resources);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write("BT /F1 20 Tf 72 600 Td <0B701167> Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            StringBuilder sb = new StringBuilder();
            for (Glyph g : pages.getFirst().glyphs()) {
                sb.append(g.text);
            }
            return sb.toString();
        }
    }
}
