package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class OmmlFenceTest {

    private static final FontInfo CMEX = new FontInfo("Cambria Math", false, false, true, false, true, "CMEX10", true);
    private static final FontInfo CMMI = new FontInfo("Cambria Math", false, true, true, false, false, "CMMI10", true);

    private static String omml(String... parts) {
        List<MathToken> toks = new ArrayList<>();
        for (String p : parts) {
            boolean fence = "()|".contains(p);
            Glyph g = new Glyph(p, 0, 5, 0, 10, 7.5f, 2.5f, fence ? CMEX : CMMI, 0, toks.size(), 3.3f, false, !fence);
            if (fence) {
                g.hang = 2;
            }
            toks.add(new MathToken(p, 0, g, null, 10));
        }
        return OmmlWriter.of(toks);
    }

    @Test
    void barsInsideBracketsPairWithEachOther() {
        String xml = omml("(", "|", "x", "|", ")");
        assertTrue(xml.contains("<m:begChr m:val=\"(\"/><m:endChr m:val=\")\"/>"), xml);
        assertTrue(xml.contains("<m:begChr m:val=\"|\"/><m:endChr m:val=\"|\"/>"), xml);
    }

    @Test
    void aLoneBarInsideBracketsStaysASeparator() {
        String xml = omml("(", "a", "|", "b", ")");
        assertTrue(xml.contains("<m:begChr m:val=\"(\"/><m:endChr m:val=\")\"/>"), xml);
    }
}
