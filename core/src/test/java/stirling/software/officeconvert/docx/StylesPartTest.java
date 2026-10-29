package stirling.software.officeconvert.docx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

class StylesPartTest {

    private static StyleSheet sheet() {
        return new StyleSheet(new RunStyle("Arial", 11, false, false, false, false, 0, -1, 0, false));
    }

    @Test
    void widowControlStaysOffUnlessTheSheetAsksForIt() {
        assertTrue(StylesPart.xml(sheet()).contains("<w:pPr><w:widowControl w:val=\"0\"/>"));
        StyleSheet on = sheet();
        on.widowControl = true;
        assertTrue(StylesPart.xml(on).contains("<w:pPr><w:widowControl/>"));
    }
}
