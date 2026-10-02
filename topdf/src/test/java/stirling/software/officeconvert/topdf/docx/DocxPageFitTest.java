package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxPageFitTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String SEPARATORS = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/>"
            + "</w:r></w:p></w:footnote><w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r>"
            + "<w:continuationSeparator/></w:r></w:p></w:footnote>";

    private static final String NO_WIDOW_NOTE = "<w:footnote w:id=\"1\"><w:p><w:pPr><w:widowControl w:val=\"0\"/>"
            + "</w:pPr>";

    @TempDir
    Path dir;

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    private static String spacer(int twips) {
        return "<w:p><w:pPr><w:spacing w:line=\"" + twips + "\" w:lineRule=\"exact\"/></w:pPr></w:p>";
    }

    private static String noteRef(String text) {
        return "<w:p><w:r><w:t>" + text + "</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
                + "<w:footnoteReference w:id=\"1\"/></w:r></w:p>";
    }

    @Test
    void lightGrayHighlightIsTheShadeWordDraws() {
        assertEquals(new Color(0xD3D3D3), Colors.highlight("lightGray"));
    }

    @Test
    void aLevelUsedBeforeItsParentCountsTheParentAsStarted() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1\"/></w:lvl><w:lvl w:ilvl=\"1\"><w:start"
                + " w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.%2\"/></w:lvl></w:abstractNum>"
                + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
        String body = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"1\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>"
                + "Sub</w:t></w:r></w:p><w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>"
                + "</w:pPr><w:r><w:t>Top</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "implicit", new DocxDoc().styles(DEFAULTS).numbering(numbering)
                .body(body).bytes());
        assertEquals(r.word("Sub").y(), r.word("1.1").y(), 0.1);
        assertEquals(r.word("Top").y(), r.word("2").y(), 0.1, "the first top-level item after 1.1 is 2");
    }

    @Test
    void aFootnoteWithoutWidowControlMayLeaveOneLineWithItsReference() throws IOException {
        StringBuilder note = new StringBuilder(NO_WIDOW_NOTE);
        for (int i = 0; i < 4; i++) {
            note.append("<w:r><w:t>N").append(i).append("</w:t></w:r><w:r><w:br/></w:r>");
        }
        note.append("</w:p></w:footnote>");
        // The reference line ends 27 pt above the bottom: room for the separator and one note line only
        String body = fillers(53) + noteRef("REF") + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, "notewidow", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(body).bytes());
        assertEquals(1, r.words().stream().filter(w -> w.text().startsWith("REF")).findFirst().orElseThrow().page());
        assertEquals(1, r.word("N0").page(), "one line of the note stays with its reference");
        assertEquals(2, r.word("N1").page());
    }

    @Test
    void aNoteBreaksOnlyWhereWidowControlAllows() throws IOException {
        String note = "<w:footnote w:id=\"1\"><w:p><w:r><w:t>A0</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>A1</w:t></w:r>"
                + "<w:r><w:br/></w:r><w:r><w:t>A2</w:t></w:r></w:p>" + DocxDoc.p("B0") + "</w:footnote>";
        // Room for the separator and two note lines: the three-line paragraph cannot leave one line behind
        DocxDoc.Rendered r = DocxDoc.render(dir, "notebreak", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(fillers(52) + noteRef("REF")).bytes());
        int ref = r.words().stream().filter(w -> w.text().startsWith("REF")).findFirst().orElseThrow().page();
        assertEquals(2, ref, "the reference moves to where its note's first paragraph fits");
        assertEquals(2, r.word("A0").page());
        assertEquals(2, r.word("A2").page());
    }

    @Test
    void linesAfterAReferenceMayPushTheRestOfItsNoteOn() throws IOException {
        StringBuilder note = new StringBuilder(NO_WIDOW_NOTE);
        for (int i = 0; i < 4; i++) {
            note.append("<w:r><w:t>N").append(i).append("</w:t></w:r><w:r><w:br/></w:r>");
        }
        note.append("</w:p></w:footnote>");
        String para = "<w:p><w:r><w:t>REF</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
                + "<w:footnoteReference w:id=\"1\"/></w:r><w:r><w:br/></w:r><w:r><w:t>Second</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "notelater", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(fillers(52) + para).bytes());
        assertEquals(1, r.word("Second").page(), "the line after the reference stays and the note continues");
        assertEquals(1, r.word("N0").page());
        assertEquals(2, r.word("N1").page());
    }

    @Test
    void lineSpacingMayNotHangOverTheFootnotes() throws IOException {
        String note = "<w:footnote w:id=\"1\">" + DocxDoc.p("Note") + "</w:footnote>";
        StringBuilder body = new StringBuilder(noteRef("REF") + spacer(300));
        for (int i = 0; i < 60; i++) {
            body.append("<w:p><w:pPr><w:spacing w:line=\"720\" w:lineRule=\"auto\"/></w:pPr><w:r><w:t>D").append(i)
                    .append("</w:t></w:r></w:p>");
        }
        DocxDoc.Rendered r = DocxDoc.render(dir, "noteslack", new DocxDoc().styles(DEFAULTS)
                .footnotes(SEPARATORS + note).body(body.toString()).bytes());
        DocxDoc.Word last = r.words().stream().filter(w -> w.page() == 1 && w.text().startsWith("D"))
                .reduce((a, b) -> b).orElseThrow();
        float ascent = r.words().stream().filter(w -> w.text().startsWith("REF")).findFirst().orElseThrow().y() - 72;
        float single = 11.5f;
        float pitch = r.word("D1").y() - r.word("D0").y();
        float bottom = last.y() - ascent + pitch;
        float notesTop = r.word("Note").y() - ascent - single;
        assertTrue(bottom <= notesTop + 0.5f, "the last triple-spaced line ends above the notes: " + bottom + " "
                + notesTop);
    }

    @Test
    void aJustifiedLineSqueezesInAWordThatOverrunsByTheRoundingSlack() throws IOException {
        String text = "Roof mounted ventilation fans will be subject to regular, end of cycle maintenance by qualified"
                + " electricians and noisy fans will be isolated and an electrician notified. Silencers will be fitted"
                + " to feed delivery lorries. The movement of vehicles outside of the installation boundary is not"
                + " within the regulatory scope.";
        String styles = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT + "\" w:hAnsi=\"" + FONT
                + "\"/><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault></w:docDefaults>";
        String settings = "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>";
        String a4 = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        String body = "<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "squeeze", new DocxDoc().styles(styles)
                .part("settings.xml", "settings",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings)
                .section(a4).body(body).bytes());
        DocxDoc.Word of = r.words().stream().filter(w -> w.text().equals("of")).reduce((a, b) -> b).orElseThrow();
        DocxDoc.Word the = r.words().stream().filter(w -> w.text().equals("the") && w.y() == of.y()).findFirst()
                .orElse(null);
        assertTrue(the != null, "the word overruns by less than a third of itself, so it stays on the line");
    }
}
