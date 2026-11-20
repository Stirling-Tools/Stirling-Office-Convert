package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

class InlineImageTest {

    private static List<String> shown(String content) {
        List<String> fonts = new ArrayList<>();
        boolean read = Content.text(content.getBytes(StandardCharsets.ISO_8859_1), (font, text) -> {
            fonts.add(font.getName());
            return true;
        }, new HashSet<>());
        return read ? fonts : null;
    }

    @Test
    void textAfterAnImageOfKnownSizeIsRead() {
        assertEquals(List.of("F1", "F2"), shown("BT /F1 12 Tf (a) Tj ET BI /W 2 /H 2 /CS /G /BPC 8 ID \u0000 EI"
                + "\nEI BT /F2 12 Tf (b) Tj ET"));
    }

    @Test
    void sizesThatOverflowAreRefusedRatherThanWrapped() {
        assertEquals(null, shown("BT /F1 12 Tf 10 100 Td (Keep) Tj ET q BI /W 0009045415 /H 1912923437 /CS /G"
                + " /BPC 0283150272 ID X EI Q BT /F2 12 Tf 10 10 Td (Hi) Tj ET EI"));
    }

    @Test
    void oddBitDepthsFractionsAndLengthsPastTheEndAreRefused() {
        assertEquals(null, shown("BI /W 2 /H 1 /CS /G /BPC 3 ID xx EI BT /F1 1 Tf (a) Tj ET"));
        assertEquals(null, shown("BI /W 2.5 /H 1 /CS /G /BPC 8 ID xx EI BT /F1 1 Tf (a) Tj ET"));
        assertEquals(null, shown("BI /W 2 /H 1 /CS /G /BPC 8 /L 99999 ID xx EI BT /F1 1 Tf (a) Tj ET"));
    }

    @Test
    void scanCountsNamedColourSpacesInTheImage() {
        Content.Usage usage = Content.scan("BI /W 1 /H 1 /CS /CS0 /BPC 8 /L 1 ID x EI"
                .getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(usage.complete());
        assertTrue(usage.names().get(Content.Category.COLOR_SPACE).contains(COSName.getPDFName("CS0")));
        assertFalse(usage.names().get(Content.Category.FONT).contains(COSName.getPDFName("CS0")));
    }
}
