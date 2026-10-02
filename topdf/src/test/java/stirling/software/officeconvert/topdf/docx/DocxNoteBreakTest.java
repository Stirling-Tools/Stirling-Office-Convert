package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxNoteBreakTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String SEPARATORS = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/>"
            + "</w:r></w:p></w:footnote><w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r>"
            + "<w:continuationSeparator/></w:r></w:p></w:footnote>";

    private static final String BODY_REFS = "<w:p><w:r><w:t>Body</w:t></w:r><w:r><w:footnoteReference w:id=\"1\"/>"
            + "</w:r><w:r><w:footnoteReference w:id=\"2\"/></w:r></w:p>";

    @TempDir
    Path dir;

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    private float noteGap(String name, String styles, String secondNote) throws IOException {
        String notes = "<w:footnote w:id=\"1\"><w:p><w:r><w:t>ONE</w:t></w:r></w:p></w:footnote><w:footnote"
                + " w:id=\"2\">" + secondNote + "</w:footnote>";
        DocxDoc.Rendered r = DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS + styles)
                .footnotes(SEPARATORS + notes).body(BODY_REFS).bytes());
        return r.word("TWO").y() - r.word("ONE").y();
    }

    @Test
    void autoSpacingBeforeAFootnotesFirstParagraphIsKept() throws IOException {
        float plain = noteGap("noteplain", "", "<w:p><w:r><w:t>TWO</w:t></w:r></w:p>");
        float direct = noteGap("noteauto", "", "<w:p><w:pPr><w:spacing w:before=\"100\" w:beforeAutospacing=\"1\"/>"
                + "</w:pPr><w:r><w:t>TWO</w:t></w:r></w:p>");
        assertEquals(14, direct - plain, 0.1);
        String style = "<w:style w:type=\"paragraph\" w:styleId=\"Web\"><w:name w:val=\"Web\"/><w:pPr><w:spacing"
                + " w:after=\"100\" w:afterAutospacing=\"1\" w:before=\"100\" w:beforeAutospacing=\"1\"/></w:pPr>"
                + "</w:style>";
        float styled = noteGap("notestyle", style, "<w:p><w:pPr><w:pStyle w:val=\"Web\"/></w:pPr><w:r><w:footnoteRef/>"
                + "</w:r><w:r><w:t xml:space=\"preserve\"> TWO</w:t></w:r></w:p>");
        assertEquals(14, styled - plain, 0.1);
    }

    @Test
    void aNoteKeepsClearOfTheSpaceAfterAndCarriesRealLinesPastABlankTail() throws IOException {
        StringBuilder note = new StringBuilder("<w:footnote w:id=\"1\"><w:p>");
        for (int i = 0; i < 4; i++) {
            note.append("<w:r><w:t xml:space=\"preserve\"> N").append(i).append("</w:t></w:r>");
            if (i < 3) {
                note.append("<w:r><w:br/></w:r>");
            }
        }
        note.append("</w:p><w:p/></w:footnote>");
        // 50 lines end 73 pt above the bottom: the separator and all four note lines fit, the empty last paragraph
        // fits too unless the reference paragraph's 12 pt space after is kept clear
        String body = fillers(49) + "<w:p><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:r><w:t>REF</w:t></w:r><w:r>"
                + "<w:footnoteReference w:id=\"1\"/></w:r></w:p>" + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, "noteafter", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(body).bytes());
        assertEquals(1, r.word("N1").page());
        assertEquals(2, r.word("N2").page(), "widow control carries two real lines, not the blank paragraph alone");
        assertEquals(2, r.word("N3").page());
    }

    @Test
    void aLineBreakSizesOnlyALineThatHoldsNothingElse() throws IOException {
        String big = "<w:r><w:rPr><w:sz w:val=\"40\"/></w:rPr><w:br/></w:r>";
        String small = "<w:r><w:rPr><w:sz w:val=\"4\"/></w:rPr><w:br/></w:r>";
        String body = DocxDoc.p("Top") + "<w:p><w:r><w:t>Text</w:t></w:r>" + big + "<w:r><w:t>Next</w:t></w:r></w:p>"
                + DocxDoc.p("Mid") + "<w:p>" + small + "<w:r><w:t>Low</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "breaks", new DocxDoc().styles(DEFAULTS).body(body).bytes());
        assertEquals(11.5, r.word("Text").y() - r.word("Top").y(), 0.1);
        assertEquals(11.5, r.word("Next").y() - r.word("Text").y(), 0.1, "a 20 pt break after text adds nothing");
        float tiny = r.word("Low").y() - r.word("Mid").y() - 11.5f;
        assertEquals(2.3, tiny, 0.2, "a break alone on its line is as tall as its 2 pt font");
    }

    @Test
    void endnotesRunningOnToANewPageStartBelowTheContinuationSeparator() throws IOException {
        String seps = "<w:endnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:endnote>"
                + "<w:endnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r>"
                + "</w:p></w:endnote>";
        StringBuilder note = new StringBuilder("<w:endnote w:id=\"1\"><w:p><w:pPr><w:widowControl w:val=\"0\"/>"
                + "</w:pPr>");
        for (int i = 0; i < 6; i++) {
            note.append("<w:r><w:t xml:space=\"preserve\"> E").append(i).append("</w:t></w:r><w:r><w:br/></w:r>");
        }
        note.append("</w:p></w:endnote>");
        String body = fillers(52) + "<w:p><w:r><w:t>REF</w:t></w:r><w:r><w:endnoteReference w:id=\"1\"/></w:r></w:p>";
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).body(body).part("endnotes.xml", "endnotes",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.endnotes+xml",
                "<w:endnotes " + DocxDoc.NS + ">" + seps + note + "</w:endnotes>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "endnotes", doc.bytes());
        DocxDoc.Word first = r.words().stream().filter(w -> w.page() == 2).findFirst().orElseThrow();
        // The continuation separator's own 11.5 pt line comes first on the new page
        assertEquals(72 + 11.5 + 9.3, first.y(), 0.5);
    }

    @Test
    void theSpaceAfterALastLineMayNotRunOverTheFootnotes() throws IOException {
        String note = "<w:footnote w:id=\"1\"><w:p><w:r><w:t xml:space=\"preserve\"> Note</w:t></w:r></w:p>"
                + "</w:footnote>";
        String last = "<w:p><w:pPr><w:spacing w:after=\"%s\"/></w:pPr><w:r><w:t>Last</w:t></w:r></w:p>";
        String body = "<w:p><w:r><w:t>R</w:t></w:r><w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>" + fillers(52);
        // The last line ends at 693 pt, 4 pt above the separator: its 12 pt space after does not fit there
        DocxDoc.Rendered spaced = DocxDoc.render(dir, "afternotes", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(body + last.formatted("240")).bytes());
        assertEquals(2, spaced.word("Last").page());
        DocxDoc.Rendered tight = DocxDoc.render(dir, "afternone", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(body + last.formatted("0")).bytes());
        assertEquals(1, tight.word("Last").page());
    }

    @Test
    void aNumberedMarkOfAContinuousBreakAbsorbsTheNextSpaceBefore() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt"
                + " w:val=\"bullet\"/><w:lvlText w:val=\"-\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        String sect = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        String mark = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"%s\"/></w:numPr><w:spacing"
                + " w:after=\"120\"/>" + sect + "</w:pPr></w:p>";
        String next = "<w:p><w:pPr><w:spacing w:before=\"120\"/></w:pPr><w:r><w:t>Next</w:t></w:r></w:p>";
        String continuous = "<w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar"
                + " w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\""
                + " w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        DocxDoc.Rendered numbered = DocxDoc.render(dir, "markspace", new DocxDoc().styles(DEFAULTS).numbering(numbering)
                .body(DocxDoc.p("Top") + mark.formatted("1") + next).section(continuous).bytes());
        assertEquals(11.5, numbered.word("Next").y() - numbered.word("Top").y(), 0.1);
    }

    private float noteLine(String name, int compatibility) throws IOException {
        String notice = "<w:footnote w:type=\"continuationNotice\" w:id=\"1\"><w:p/></w:footnote>";
        String note = "<w:footnote w:id=\"2\"><w:p><w:r><w:t xml:space=\"preserve\"> Note</w:t></w:r></w:p>"
                + "</w:footnote>";
        String settings = "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"" + compatibility + "\"/></w:compat>"
                + "</w:settings>";
        DocxDoc doc = new DocxDoc().styles(DEFAULTS).footnotes(SEPARATORS + notice + note)
                .body("<w:p><w:r><w:t>R</w:t></w:r><w:r><w:footnoteReference w:id=\"2\"/></w:r></w:p>")
                .part("settings.xml", "settings",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings);
        return DocxDoc.render(dir, name, doc.bytes()).word("Note").y();
    }

    @Test
    void wordTwentyTenLayoutKeepsRoomForTheContinuationNotice() throws IOException {
        assertEquals(11.5, noteLine("notice15", 15) - noteLine("notice14", 14), 0.1);
    }
}

