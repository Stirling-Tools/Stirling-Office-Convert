package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxWidowKeepTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    private static String lines(String pPr, int n) {
        StringBuilder b = new StringBuilder("<w:p><w:pPr>").append(pPr).append("</w:pPr>");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append("<w:r><w:br/></w:r>");
            }
            b.append("<w:r><w:t>L").append(i).append("</w:t></w:r>");
        }
        return b.append("</w:p>").toString();
    }

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    private Map<Integer, Integer> linesPerPage(DocxDoc.Rendered r) throws IOException {
        Map<Integer, Integer> pages = new TreeMap<>();
        for (DocxDoc.Word w : r.words()) {
            if (w.text().matches("L\\d+")) {
                pages.merge(w.page(), 1, Integer::sum);
            }
        }
        return pages;
    }

    @Test
    void aKeepWithNextHeadingStaysWithTheTextBeforeAParagraphThatStartsANewPage() throws IOException {
        int room = (int) render("room", fillers(80)).words().stream().filter(w -> w.page() == 1).count();
        for (int n = room - 4; n < room; n++) {
            String body = fillers(n) + "<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:t>Head</w:t></w:r></w:p>"
                    + lines("<w:pageBreakBefore/>", 6);
            DocxDoc.Rendered r = render("keepbreak" + n, body);
            assertEquals(1, r.word("Head").page(), "with " + n + " of " + room + " lines filled");
            assertEquals(2, r.word("L0").page());
            assertEquals(2, r.pages());
        }
    }

    @Test
    void widowAndOrphanCountsWiderThanTwoKeepThatManyLinesTogether() throws IOException {
        String three = "<w:widowControl xmlns:so=\"urn:stirling-software:office-convert\" so:widows=\"3\""
                + " so:orphans=\"3\"/>";
        boolean threeSeen = false;
        boolean twoSeen = false;
        for (int n = 44; n < 56; n++) {
            Map<Integer, Integer> wide = linesPerPage(render("three" + n, fillers(n) + lines(three, 8)));
            if (wide.size() > 1) {
                assertTrue(wide.values().stream().allMatch(c -> c >= 3), n + " fillers: " + wide);
                threeSeen |= wide.containsValue(3);
            }
            Map<Integer, Integer> plain = linesPerPage(render("two" + n, fillers(n) + lines("", 8)));
            if (plain.size() > 1) {
                assertTrue(plain.values().stream().allMatch(c -> c >= 2), n + " fillers: " + plain);
                twoSeen |= plain.containsValue(2);
            }
        }
        assertTrue(threeSeen, "three lines may stand on a page");
        assertTrue(twoSeen, "Word's widow control leaves two lines");
    }

    @Test
    void widowCountsOfOneLetASingleLineStandAlone() throws IOException {
        String one = "<w:widowControl xmlns:so=\"urn:stirling-software:office-convert\" so:widows=\"1\""
                + " so:orphans=\"1\"/>";
        boolean lone = false;
        for (int n = 48; n < 56; n++) {
            lone |= linesPerPage(render("one" + n, fillers(n) + lines(one, 8))).containsValue(1);
        }
        assertTrue(lone);
        assertFalse(linesPerPage(render("off", fillers(53) + lines("<w:widowControl w:val=\"0\"/>", 8)))
                .isEmpty());
    }
}
