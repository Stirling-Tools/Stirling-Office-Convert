package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxRightTabTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    private static String entry(String pPr, String text, String number) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r><w:r><w:tab/>"
                + "<w:t>" + number + "</w:t></w:r></w:p>";
    }

    @Test
    void aRightTabSetInsideTheRightIndentAlignsAtItsStop() throws IOException {
        // Text column 468 pt, right indent 72 pt: the stop at 440 pt lies inside the indent
        String pPr = "<w:tabs><w:tab w:val=\"right\" w:pos=\"8800\"/></w:tabs><w:ind w:right=\"1440\"/>";
        DocxDoc.Rendered r = render("inindent", entry(pPr, "Heading", "27"));
        DocxDoc.Word n = r.word("27");
        assertEquals(72 + 440 - 11.12f, n.x(), 0.6f, "the number ends at the tab stop, not at the indent");
    }

    @Test
    void aRightTabPastTheMarginStopsAtTheLineEnd() throws IOException {
        String pPr = "<w:tabs><w:tab w:val=\"right\" w:pos=\"11000\"/></w:tabs>";
        DocxDoc.Rendered r = render("pastmargin", entry(pPr, "Heading", "27"));
        assertEquals(72 + 468 - 11.12f, r.word("27").x(), 0.6f);
    }

    @Test
    void aTabAfterTheLastStopWrapsInsteadOfRunningIntoTheIndent() throws IOException {
        // The stop sits at the right edge; after text reaching that edge the next tab has no stop left on the line
        String pPr = "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"7920\"/></w:tabs><w:ind w:right=\"1440\"/>"
                + "<w:jc w:val=\"both\"/>";
        String body = "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t xml:space=\"preserve\">Basis</w:t></w:r><w:r><w:tab/>"
                + "<w:t xml:space=\"preserve\">22 Technical issues of the method that run on long</w:t></w:r><w:r><w:tab/>"
                + "<w:t>23</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("wraptab", body);
        DocxDoc.Word n = null;
        for (DocxDoc.Word w : r.words()) {
            if (w.text().endsWith("23")) {
                n = w;
            }
        }
        assertTrue(n != null, "the number is drawn");
        assertTrue(n.y() > r.word("Basis").y() + 5, "and goes to the next line: " + n);
    }
}
