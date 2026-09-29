package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.CloudMetrics;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontMetrics;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxTextLayoutTest {

    private static final float EMU = 12_700;

    @TempDir
    Path dir;

    @Test
    void cloudFontMetricsCoverTheOfficeFamilies() {
        for (boolean bold : new boolean[] {false, true}) {
            for (boolean italic : new boolean[] {false, true}) {
                assertNotNull(CloudMetrics.of("Aptos", bold, italic));
                assertNotNull(CloudMetrics.of("aptos  narrow", bold, italic));
            }
        }
        CloudMetrics aptos = CloudMetrics.of("Aptos", false, false);
        assertTrue(aptos.advance('m') > 0.8f && aptos.advance('m') < 1f, "m " + aptos.advance('m'));
        assertTrue(aptos.advance(' ') < 0.25f, "space " + aptos.advance(' '));
        assertTrue(aptos.advance('é') > 0 && aptos.advance('€') > 0);
        assertTrue(Float.isNaN(aptos.advance(0x4E2D)));
        assertNull(CloudMetrics.of("Calibri", false, false));
        CloudMetrics regular = CloudMetrics.of("Neue Haas Grotesk Text Pro", false, false);
        assertEquals(regular, CloudMetrics.of("Neue Haas Grotesk Text Pro", false, true));
        assertEquals(CloudMetrics.of("Neue Haas Grotesk Text Pro", true, false),
                CloudMetrics.of("Neue Haas Grotesk Text Pro", true, true));
    }

    @Test
    void missingCloudFontsAreLaidOutWithTheirOwnAdvanceWidths() throws IOException {
        assumeTrue(FontLibrary.system().find("Aptos", false, false).substituted(), "Aptos is installed here");
        String p = "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"2000\"><a:latin typeface=\"Aptos\"/></a:rPr>"
                + "<a:t>mmmm iiii</a:t></a:r></a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 6096000, 914400,
                "<a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p));
        Decks.Converted c = Decks.convert(dir, "aptos.pptx", pptx);
        assertTrue(c.text().contains("mmmm iiii"), c.text());
        CloudMetrics aptos = CloudMetrics.of("Aptos", false, false);
        float first = Chars.device(aptos.advance('m') * 20) * 4 + Chars.device(aptos.advance(' ') * 20);
        List<TextPosition> pos = c.positions(0);
        assertEquals(72 + first, x(pos, 'i'), 0.05);
        assertEquals(72, x(pos, 'm'), 0.05);
        float share = 0.939f / (0.939f + 0.2817f);
        assertEquals(72 + 1.2f * 20 * share, baseline(pos, 'm'), 0.05);
    }

    @Test
    void softHyphensBreakLinesAndShowAHyphenOnlyThere() throws IOException {
        FontFace arial = FontLibrary.system().find(TextStyles.DEFAULT_FAMILY, false, false);
        float fits = arial.width("Dort ste-", 20);
        float whole = arial.width("Dort stehen", 20);
        long width = Math.round((fits + whole) / 2 * EMU);
        String shy = "­";
        String p = "<a:p>" + Decks.run("Dort ste" + shy + "hen Helfer", "sz=\"2000\" kern=\"0\"") + "</a:p>";
        String q = "<a:p>" + Decks.run("ab" + shy + "cd", "sz=\"2000\"") + "</a:p>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, width, 1828800,
                "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", p)
                + Decks.textBox(11, 914400, 3657600, 6096000, 914400,
                        "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>", q));
        String text = Decks.convert(dir, "shy.pptx", pptx).text().replace("\r", "");
        assertTrue(text.contains("Dort ste-\nhen"), text);
        assertTrue(text.contains("abcd"), text);
        assertFalse(text.contains("ab-"), text);
    }

    @Test
    void linesBelowSingleSpacingKeepTheSingleDescent() throws IOException {
        String p90 = "<a:p><a:pPr><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc></a:pPr>"
                + Decks.run("Alpha", "sz=\"4000\"") + "</a:p>";
        String p60 = "<a:p><a:pPr><a:lnSpc><a:spcPct val=\"60000\"/></a:lnSpc></a:pPr>"
                + Decks.run("Beta", "sz=\"4000\"") + "</a:p>";
        String bodyPr = "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 4572000, 914400, bodyPr, p90)
                + Decks.textBox(11, 914400, 2743200, 4572000, 914400, bodyPr, p60));
        List<TextPosition> pos = Decks.convert(dir, "descent.pptx", pptx).positions(0);
        FontMetrics m = FontLibrary.system().find(TextStyles.DEFAULT_FAMILY, false, false).metrics();
        float ascent = m.useTypoMetrics() ? m.typoAscender() : m.winAscent();
        float descent = m.useTypoMetrics() ? -m.typoDescender() : m.winDescent();
        float share = ascent / (ascent + descent);
        float single = 1.2f * 40;
        float h90 = 0.9f * single;
        assertEquals(72 + Math.max(0.75f * h90, h90 - single * (1 - share)), baseline(pos, 'A'), 0.05);
        float h60 = 0.6f * single;
        assertEquals(216 + Math.max(0.75f * h60, h60 - single * (1 - share)), baseline(pos, 'B'), 0.05);
    }

    @Test
    void unwrappedCenteredTextStillKeepsItsRightMargin() throws IOException {
        String plain = "<a:p><a:pPr algn=\"ctr\"/>" + Decks.run("Middle", "sz=\"2000\" kern=\"0\"") + "</a:p>";
        String margin = "<a:p><a:pPr marR=\"254000\" algn=\"ctr\"/>" + Decks.run("Margin", "sz=\"2000\" kern=\"0\"") + "</a:p>";
        String bodyPr = "<a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, 4572000, 914400, bodyPr, plain)
                + Decks.textBox(11, 914400, 2743200, 4572000, 914400, bodyPr, margin));
        List<TextPosition> pos = Decks.convert(dir, "margin.pptx", pptx).positions(0);
        FontFace face = FontLibrary.system().find(TextStyles.DEFAULT_FAMILY, false, false);
        float middle = 72 + (360 - Chars.device(face.width("Middle", 20))) / 2;
        float shifted = 72 + (360 - 20 - Chars.device(face.width("Margin", 20))) / 2;
        assertEquals(middle, x(pos, 'M'), 0.5);
        assertEquals(shifted, xAfter(pos, 'M', 1), 0.5);
    }

    @Test
    void aLineThatOverrunsTheBoxByAFractionOfAPointWraps() throws IOException {
        FontFace face = FontLibrary.system().find(TextStyles.DEFAULT_FAMILY, false, false);
        float width = 0;
        for (char ch : "Measured words".toCharArray()) {
            width += Chars.device(face.advance(ch) * 20f / face.unitsPerEm());
        }
        String p = "<a:p>" + Decks.run("Measured words", "sz=\"2000\" kern=\"0\"") + "</a:p>";
        String bodyPr = "<a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"/>";
        byte[] pptx = Decks.slideXml(Decks.textBox(10, 914400, 914400, Math.round((width - 0.01f) * EMU), 1828800,
                bodyPr, p) + Decks.textBox(11, 914400, 3657600, Math.round((width + 0.01f) * EMU), 914400, bodyPr,
                        p.replace("Measured", "Measures")));
        String text = Decks.convert(dir, "overrun.pptx", pptx).text().replace("\r", "");
        assertTrue(text.contains("Measured\nwords"), text);
        assertTrue(text.contains("Measures words"), text);
    }

    @Test
    void pagesAreTransparencyGroupsLikePowerPoints() throws IOException {
        Decks.Converted c = Decks.convert(dir, "group.pptx", Fixtures.pptx("Grouped"));
        try (PDDocument d = c.open()) {
            COSDictionary group = d.getPage(0).getCOSObject().getCOSDictionary(COSName.GROUP);
            assertNotNull(group);
            assertEquals(COSName.TRANSPARENCY, group.getCOSName(COSName.S));
            assertEquals(COSName.DEVICERGB, group.getCOSName(COSName.CS));
        }
    }

    private static float x(List<TextPosition> pos, char c) {
        return xAfter(pos, c, 0);
    }

    private static float xAfter(List<TextPosition> pos, char c, int skip) {
        List<Float> found = new ArrayList<>();
        for (TextPosition t : pos) {
            if (t.getUnicode().charAt(0) == c) {
                found.add(t.getXDirAdj());
            }
        }
        if (found.size() <= skip) {
            throw new AssertionError("no " + c);
        }
        return found.get(skip);
    }

    private static float baseline(List<TextPosition> pos, char c) {
        for (TextPosition t : pos) {
            if (t.getUnicode().charAt(0) == c) {
                return t.getYDirAdj();
            }
        }
        throw new AssertionError("no " + c);
    }
}
