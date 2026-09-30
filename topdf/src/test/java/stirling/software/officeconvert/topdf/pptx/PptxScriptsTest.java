package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class PptxScriptsTest {

    @TempDir
    Path dir;

    @Test
    void devanagariInALeftToRightLineIsShaped() throws IOException {
        FontLibrary fonts = FontLibrary.system();
        assumeTrue(fonts.fallback(0x0915, fonts.find("Calibri", false, false)) != null, "no Devanagari font here");
        String p = "<a:p><a:r><a:rPr lang=\"hi-IN\" sz=\"2800\"/><a:t>\u0939\u093F\u0928\u094D\u0926\u0940"
                + " \u0915\u094D\u0937\u092E\u093E \u0915\u093F</a:t></a:r></a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 914400,
                "<a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        Decks.Converted c = Decks.convert(dir, "hindi.pptx", pptx);
        assertTrue(c.text().contains("\u0915\u094D\u0937\u092E\u093E"), c.text());
        List<TextPosition> pos = c.positions(0);
        assertFalse(pos.stream().anyMatch(t -> t.getUnicode().equals("\u093F")), "the i sign was drawn on its own");
    }

    @Test
    void aRightToLeftBulletStandsRightOfItsText() throws IOException {
        String p = "<a:p><a:pPr marL=\"457200\" indent=\"-342900\" algn=\"r\" rtl=\"1\"><a:buFont typeface=\"Arial\"/>"
                + "<a:buChar char=\"*\"/></a:pPr><a:r><a:rPr lang=\"he-IL\" sz=\"2800\"/><a:t>\u05E4\u05E8\u05D9\u05D8\u0020\u05E8\u05D0\u05E9\u05D5\u05DF\u0020\u05D1\u05E8\u05E9\u05D9\u05DE\u05D4</a:t></a:r></a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 914400,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        List<TextPosition> pos = Decks.convert(dir, "bullet.pptx", pptx).positions(0);
        float bullet = Float.NaN;
        float text = 0;
        for (TextPosition t : pos) {
            if (t.getUnicode().equals("*")) {
                bullet = t.getXDirAdj();
            } else if (!t.getUnicode().isBlank()) {
                text = Math.max(text, t.getXDirAdj() + t.getWidthDirAdj());
            }
        }
        assertTrue(bullet > text, "bullet " + bullet + ", text ends at " + text);
        assertTrue(bullet < 72 + 480, "bullet " + bullet);
    }

    @Test
    void thaiWithoutSpacesWrapsInsideItsBox() throws IOException {
        FontLibrary fonts = FontLibrary.system();
        assumeTrue(fonts.fallback(0x0E01, fonts.find("Calibri", false, false)) != null, "no Thai font here");
        String p = "<a:p><a:r><a:rPr lang=\"th-TH\" sz=\"1800\"/><a:t>\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E22\u0E40\u0E1B\u0E47\u0E19\u0E20\u0E32\u0E29\u0E32\u0E17\u0E35\u0E48\u0E44\u0E21\u0E48\u0E21\u0E35\u0E01\u0E32\u0E23\u0E40\u0E27\u0E49\u0E19\u0E27\u0E23\u0E23\u0E04\u0E23\u0E30\u0E2B\u0E27\u0E48\u0E32\u0E07\u0E04\u0E33\u0E41\u0E25\u0E30\u0E40\u0E02\u0E35\u0E22\u0E19\u0E15\u0E34\u0E14\u0E01\u0E31\u0E19\u0E44\u0E1B\u0E08\u0E19\u0E08\u0E1A\u0E1B\u0E23\u0E30\u0E42\u0E22\u0E04\u0E22\u0E32\u0E27\u0E21\u0E32\u0E01</a:t></a:r></a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 3175000, 2743200,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        List<TextPosition> pos = Decks.convert(dir, "thai.pptx", pptx).positions(0);
        float right = 0;
        Set<Integer> lines = new TreeSet<>();
        for (TextPosition t : pos) {
            right = Math.max(right, t.getXDirAdj() + t.getWidthDirAdj());
            lines.add(Math.round(t.getYDirAdj()));
        }
        assertTrue(lines.size() >= 3, lines.toString());
        assertTrue(right <= 72 + 250 + 1, "text runs to " + right);
    }

    @Test
    void justifiedRightToLeftLinesFillTheBoxAndEndOnTheRight() throws IOException {
        String p = "<a:p><a:pPr algn=\"just\" rtl=\"1\"/><a:r><a:rPr lang=\"ar-SA\" sz=\"2400\"/><a:t>\u064A\u0648\u0644\u062F\u0020\u062C\u0645\u064A\u0639\u0020\u0627\u0644\u0646\u0627\u0633\u0020\u0623\u062D\u0631\u0627\u0631\u064B\u0627\u0020\u0645\u062A\u0633\u0627\u0648\u064A\u0646\u0020\u0641\u064A\u0020\u0627\u0644\u0643\u0631\u0627\u0645\u0629\u0020\u0648\u0627\u0644\u062D\u0642\u0648\u0642\u0020\u0648\u0642\u062F\u0020\u0648\u0647\u0628\u0648\u0627\u0020\u0639\u0642\u0644\u0627\u064B\u0020\u0648\u0636\u0645\u064A\u0631\u064B\u0627\u0020\u0648\u0639\u0644\u064A\u0647\u0645\u0020\u0623\u0646\u0020\u064A\u0639\u0627\u0645\u0644\u0020\u0628\u0639\u0636\u0647\u0645\u0020\u0628\u0639\u0636\u064B\u0627\u0020\u0628\u0631\u0648\u062D\u0020\u0627\u0644\u0625\u062E\u0627\u0621\u0020</a:t></a:r></a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 2743200,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        List<TextPosition> pos = Decks.convert(dir, "justify.pptx", pptx).positions(0);
        Map<Integer, float[]> lines = new TreeMap<>();
        for (TextPosition t : pos) {
            if (!t.getUnicode().isBlank() && t.getWidthDirAdj() > 0.5f) {
                float[] e = lines.computeIfAbsent(Math.round(t.getYDirAdj() / 12), y -> new float[] {Float.MAX_VALUE, 0});
                e[0] = Math.min(e[0], t.getXDirAdj());
                e[1] = Math.max(e[1], t.getXDirAdj() + t.getWidthDirAdj());
            }
        }
        assertTrue(lines.size() >= 2, lines.keySet().toString());
        for (float[] e : lines.values()) {
            assertEquals(552, e[1], 2, "a line ends at " + e[1]);
        }
        assertEquals(72, lines.values().iterator().next()[0], 2);
    }
}
