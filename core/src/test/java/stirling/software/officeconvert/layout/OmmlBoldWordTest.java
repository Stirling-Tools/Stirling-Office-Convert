package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class OmmlBoldWordTest {

    private static final FontInfo CMBX = new FontInfo("Cambria Math", true, false, true, false, false, "CMBX10", true);
    private static final FontInfo CMR = new FontInfo("Cambria Math", false, false, true, false, false, "CMR10", true);

    private static MathToken token(String text, boolean bold, int seq) {
        Glyph g = new Glyph(text, seq * 6, 5, 0, 10, 7.5f, 2.5f, bold ? CMBX : CMR, 0, seq, 3.3f, bold, false);
        return new MathToken(text, 0, g, null, 10);
    }

    @Test
    void adjacentBoldUprightLettersStayBold() {
        List<MathToken> toks = new ArrayList<>();
        toks.add(token("A", true, 0));
        toks.add(token("B", true, 1));
        String xml = OmmlWriter.of(toks);
        assertTrue(xml.contains("<m:sty m:val=\"b\"/></m:rPr>"), xml);
        assertTrue(xml.contains(">AB</m:t>"), xml);
    }

    @Test
    void boldAndRegularLettersDoNotMerge() {
        List<MathToken> toks = new ArrayList<>();
        toks.add(token("A", true, 0));
        toks.add(token("B", false, 1));
        String xml = OmmlWriter.of(toks);
        assertTrue(xml.contains(">A</m:t>"), xml);
        assertTrue(xml.contains(">B</m:t>"), xml);
    }
}
