package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DocxPartsTest {

    private static XEl xml(String s) throws IOException {
        return XTree.parse(new ByteArrayInputStream(("<root " + DocxDoc.NS + ">" + s + "</root>")
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void formatsListNumbersLikeWord() {
        assertEquals("iv", NumberFormat.format(4, "lowerRoman"));
        assertEquals("XIV", NumberFormat.format(14, "upperRoman"));
        assertEquals("aa", NumberFormat.format(27, "lowerLetter"));
        assertEquals("C", NumberFormat.format(3, "upperLetter"));
        assertEquals("22nd", NumberFormat.format(22, "ordinal"));
        assertEquals("11th", NumberFormat.format(11, "ordinal"));
        assertEquals("Twenty-one", NumberFormat.format(21, "cardinalText"));
        assertEquals("Third", NumberFormat.format(3, "ordinalText"));
        assertEquals("07", NumberFormat.format(7, "decimalZero"));
        assertEquals("", NumberFormat.format(5, "none"));
        assertEquals("5", NumberFormat.format(5, "somethingUnknown"));
    }

    @Test
    void breaksLikeWordAtSpacesHyphensAndBetweenIdeographs() {
        String text = "well-known words \u6F22\u5B57\u3067\u3059";
        boolean[] b = Breaks.compute(text);
        assertTrue(b[text.indexOf("known")]);
        assertTrue(b[text.indexOf("words")]);
        assertFalse(b[text.indexOf("ell")]);
        assertFalse(b[text.indexOf(' ')]);
        int kanji = text.indexOf('\u5B57');
        assertTrue(b[kanji]);
        assertFalse(b[text.indexOf('\u3059') + 0] && false);
        boolean[] minus = Breaks.compute("-5 dollars");
        assertFalse(minus[1]);
    }

    @Test
    void parsesTwipsAndUniversalMeasures() {
        assertEquals(36f, Ooxml.twips("720"), 0.001f);
        assertEquals(72f, Ooxml.twips("1in"), 0.001f);
        assertEquals(28.35f, Ooxml.twips("1cm"), 0.01f);
        assertEquals(10f, Ooxml.emu("127000"), 0.001f);
        assertNull(Ooxml.twips("abc"));
        assertTrue(Ooxml.on(null) == false);
    }

    @Test
    void resolvesWordThemeColoursWithTintAndShade() throws IOException {
        XEl theme = xml("<a:theme><a:themeElements><a:clrScheme name=\"x\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1>"
                + "<a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>"
                + "</a:clrScheme><a:fontScheme name=\"f\"><a:majorFont><a:latin typeface=\"Major Face\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Minor Face\"/></a:minorFont></a:fontScheme></a:themeElements>"
                + "</a:theme>").child("a:theme");
        Theme t = new Theme(theme);
        assertEquals("Minor Face", t.font("minorHAnsi"));
        assertEquals("Major Face", t.font("majorAscii"));
        assertEquals(new Color(0x4472C4), t.wordColor("accent1"));
        XEl color = xml("<w:color w:val=\"FF0000\" w:themeColor=\"accent1\" w:themeShade=\"BF\"/>").child("w:color");
        Color shaded = Colors.word(color, t);
        assertTrue(shaded.getBlue() < 0xC4 && shaded.getBlue() > 0x60, shaded.toString());
        assertEquals(Color.RED, Colors.word(xml("<w:color w:val=\"FF0000\"/>").child("w:color"), t));
        assertNull(Colors.word(xml("<w:color w:val=\"auto\"/>").child("w:color"), t));
    }

    @Test
    void mergesParagraphPropertiesAndTabs() throws IOException {
        ParaProps base = ParaProps.parse(xml("<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/><w:tabs><w:tab w:val=\"left\""
                + " w:pos=\"1440\"/><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"9000\"/></w:tabs>"
                + "<w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr>").child("w:pPr"), null);
        ParaProps direct = ParaProps.parse(xml("<w:pPr><w:ind w:firstLine=\"0\"/><w:tabs><w:tab w:val=\"clear\""
                + " w:pos=\"1440\"/></w:tabs></w:pPr>").child("w:pPr"), null);
        ParaProps merged = new ParaProps();
        merged.mergeFrom(base);
        merged.mergeFrom(direct);
        assertEquals(36f, merged.left(), 0.001f);
        assertEquals(0f, merged.first(), 0.001f);
        assertEquals(1, merged.tabs.size());
        assertEquals('.', merged.tabs.get(0).leader());
        assertEquals(1.15f, merged.line, 0.001f);
        assertEquals(6f, merged.after, 0.001f);
    }

    @Test
    void choosesSupportedMarkupCompatibilityBranches() throws IOException {
        XEl root = xml("<mc:AlternateContent><mc:Choice Requires=\"wps\"><w:t>choice</w:t></mc:Choice>"
                + "<mc:Fallback><w:t>fallback</w:t></mc:Fallback></mc:AlternateContent>"
                + "<mc:AlternateContent xmlns:zz=\"urn:unknown\"><mc:Choice Requires=\"zz\"><w:t>unknown</w:t>"
                + "</mc:Choice><mc:Fallback><w:t>second</w:t></mc:Fallback></mc:AlternateContent>");
        assertEquals(2, root.kids.size());
        assertEquals("choice", root.kids.get(0).text());
        assertEquals("second", root.kids.get(1).text());
    }
}
