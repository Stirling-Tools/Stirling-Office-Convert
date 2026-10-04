package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class PptxAutofitTest {

    private static final String BODY = "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\">";

    @TempDir
    Path dir;

    private List<TextPosition> convert(String name, String autofit, String paragraphs) throws IOException {
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 2743200, BODY + autofit + "</a:bodyPr>",
                paragraphs));
        return Decks.convert(dir, name, pptx).positions(0);
    }

    @Test
    void shrunkFontSizesRoundToWholePointsAndTheDefaultSpacingIsReduced() throws IOException {
        TestFonts.assumeInstalled("Calibri", false, TestFonts.CALIBRI);
        String ps = "<a:p>" + Decks.run("Alpha", "sz=\"2200\"") + "</a:p><a:p>" + Decks.run("Beta", "sz=\"2200\"")
                + "</a:p>";
        List<TextPosition> pos = convert("shrink.pptx", "<a:normAutofit fontScale=\"92500\" lnSpcReduction=\"20000\"/>",
                ps);
        assertEquals(20, find(pos, 'A').getTextMatrix().getScalingFactorX(), 0.01);
        assertEquals(0.8f * 1.2f * 20, find(pos, 'B').getYDirAdj() - find(pos, 'A').getYDirAdj(), 0.02);
    }

    @Test
    void spacingBeforeIsNotReducedAndUsesTheRoundedSize() throws IOException {
        String before = "<a:pPr><a:spcBef><a:spcPct val=\"50000\"/></a:spcBef></a:pPr>";
        String ps = "<a:p>" + before + Decks.run("Alpha", "sz=\"2200\"") + "</a:p><a:p>" + before
                + Decks.run("Beta", "sz=\"2200\"") + "</a:p>";
        List<TextPosition> pos = convert("before.pptx", "<a:normAutofit fontScale=\"92500\" lnSpcReduction=\"10000\"/>",
                ps);
        assertEquals(0.9f * 24 + 0.5f * 24, find(pos, 'B').getYDirAdj() - find(pos, 'A').getYDirAdj(), 0.02);
    }

    @Test
    void theReductionTakesPercentagePointsOffTheLineSpacing() throws IOException {
        String ninety = "<a:pPr><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc></a:pPr>";
        String ps = "<a:p>" + ninety + Decks.run("Alpha", "sz=\"1800\"") + "</a:p><a:p>" + ninety
                + Decks.run("Beta", "sz=\"1800\"") + "</a:p>";
        List<TextPosition> pos = convert("points.pptx", "<a:normAutofit lnSpcReduction=\"10000\"/>", ps);
        assertEquals(0.8f * 1.2f * 18, find(pos, 'B').getYDirAdj() - find(pos, 'A').getYDirAdj(), 0.02);
    }

    @Test
    void textTooLongForItsColumnsIsSharedOutEvenly() throws IOException {
        StringBuilder ps = new StringBuilder();
        for (String w : new String[] {"Aa", "Bb", "Cc", "Dd", "Ee", "Ff"}) {
            ps.append("<a:p>").append(Decks.run(w, "sz=\"2000\"")).append("</a:p>");
        }
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 5486400, 609600,
                "<a:bodyPr wrap=\"square\" numCol=\"2\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"><a:noAutofit/>"
                        + "</a:bodyPr>", ps.toString()));
        List<TextPosition> pos = Decks.convert(dir, "columns.pptx", pptx).positions(0);
        assertEquals(72, find(pos, 'C').getXDirAdj(), 0.05);
        assertEquals(72 + 216, find(pos, 'D').getXDirAdj(), 0.05);
        assertEquals(find(pos, 'A').getYDirAdj(), find(pos, 'D').getYDirAdj(), 0.05);
    }

    @Test
    void aLineNeverBreaksAfterATabButAfterASpace() throws IOException {
        String word = "m".repeat(40);
        String ps = "<a:p>" + Decks.run("\t" + word, "sz=\"2000\"") + "</a:p><a:p>"
                + Decks.run("see " + word.replace('m', 'w'), "sz=\"2000\"") + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 2540000, 2743200, BODY + "</a:bodyPr>", ps));
        List<TextPosition> pos = Decks.convert(dir, "longword.pptx", pptx).positions(0);
        assertEquals(72 + 1.2f * 20 * 0.905f / (0.905f + 0.212f), find(pos, 'm').getYDirAdj(), 1.5);
        assertTrue(find(pos, 'w').getYDirAdj() > find(pos, 's').getYDirAdj() + 20);
    }

    @Test
    void withoutAStoredScaleSizesStayAsWritten() throws IOException {
        TestFonts.assumeInstalled("Calibri", false, TestFonts.CALIBRI);
        String ps = "<a:p>" + Decks.run("Alpha", "sz=\"1050\"") + "</a:p><a:p>" + Decks.run("Beta", "sz=\"1050\"")
                + "</a:p>";
        List<TextPosition> pos = convert("plain.pptx", "<a:normAutofit/>", ps);
        assertEquals(10.5f, find(pos, 'A').getTextMatrix().getScalingFactorX(), 0.01);
        assertEquals(1.2f * 10.5f, find(pos, 'B').getYDirAdj() - find(pos, 'A').getYDirAdj(), 0.02);
    }

    private static TextPosition find(List<TextPosition> pos, char c) {
        for (TextPosition t : pos) {
            if (t.getUnicode().charAt(0) == c) {
                return t;
            }
        }
        throw new AssertionError("no " + c);
    }
}
