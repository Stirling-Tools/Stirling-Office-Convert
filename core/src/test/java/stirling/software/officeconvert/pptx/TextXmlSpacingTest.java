package stirling.software.officeconvert.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.ScriptWidths;

class TextXmlSpacingTest {

    private static RunStyle scaled(int scale) {
        return new RunStyle("Times New Roman", 11, false, false, false, false, 0, -1, 0, false, 0f, scale, false);
    }

    @Test
    void narrowedIndicRunsSpaceEachClusterByItsOwnWidth() {
        String text = "\u0C2E\u0C3E\u0C28\u0C35\u0C41\u0C32\u0C41 \u0C38\u0C2E\u0C3E\u0C28\u0C41\u0C32\u0C41";
        float unit = ScriptWidths.perUnit(text, false);
        assertEquals(Math.round(-0.1f * unit * 11 * 100), TextXml.spacing(scaled(90), text));
        assertEquals(Math.round(-0.1f * 0.5f * 11 * 100), TextXml.spacing(scaled(90), "Latin"));
        assertEquals(0, TextXml.spacing(scaled(100), text));
    }
}
