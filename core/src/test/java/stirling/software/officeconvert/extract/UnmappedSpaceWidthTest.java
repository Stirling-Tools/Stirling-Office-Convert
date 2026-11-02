package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;

class UnmappedSpaceWidthTest {

    private static final String LETTERS_ONLY = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
            + "/CMapType 2 def /CMapName /L def 1 begincodespacerange <00> <FF> endcodespacerange "
            + "1 beginbfrange <61> <7A> <0061> endbfrange endcmap CMapName currentdict /CMap defineresource pop end end";

    private static final String WITH_SPACE = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
            + "/CMapType 2 def /CMapName /L def 1 begincodespacerange <00> <FF> endcodespacerange "
            + "1 beginbfchar <20> <0020> endbfchar 1 beginbfrange <61> <7A> <0061> endbfrange endcmap "
            + "CMapName currentdict /CMap defineresource pop end end";

    @Test
    void aPlaceholderWidthForASpaceTheTextMapLeavesOutGivesWayToTheFontsOwnSpace() throws IOException {
        assertEquals(569f / 2048 * 18, spaceWidth(LETTERS_ONLY), 0.05f);
    }

    @Test
    void aSpaceTheTextMapCoversKeepsItsDeclaredWidth() throws IOException {
        assertEquals(0.4f * 18, spaceWidth(WITH_SPACE), 0.05f);
    }

    private static float spaceWidth(String toUnicode) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDTrueTypeFont font;
            try (InputStream in = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDTrueTypeFont.load(document, in, WinAnsiEncoding.INSTANCE);
            }
            int first = font.getCOSObject().getInt(COSName.FIRST_CHAR);
            COSArray widths = font.getCOSObject().getCOSArray(COSName.WIDTHS);
            widths.set(32 - first, COSInteger.get(500));
            COSStream map = document.getDocument().createCOSStream();
            try (OutputStream out = map.createOutputStream()) {
                out.write(toUnicode.getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, map);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDTrueTypeFont(font.getCOSObject()));
            page.setResources(resources);
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write("BT /F1 18 Tf 72 600 Td [(two)-157.7(years)] TJ ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            return pages.getFirst().glyphs().getFirst().spaceWidth;
        }
    }
}
