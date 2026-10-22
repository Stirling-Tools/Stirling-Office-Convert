package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.json.MediaBytes;
import stirling.software.officeconvert.topdf.OfficeToPdf;

// Each case once hung, overflowed the stack or ran out of memory; the limit only catches a hang, it times nothing
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class HostileHtmlTest {

    @TempDir
    Path dir;

    private String convert(String name, String content) throws IOException {
        Path in = Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ZERO));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void customPropertiesThatFanOutStop() throws IOException {
        StringBuilder css = new StringBuilder(":root{--v0:;");
        for (int i = 1; i <= 7; i++) {
            css.append("--v").append(i).append(':').append(("var(--v" + (i - 1) + ") ").repeat(220)).append(';');
        }
        css.append("}p{color:var(--v7)}");
        assertTrue(convert("vars.html", "<style>" + css + "</style><p>still here</p>").contains("still here"));
    }

    @Test
    void textAndRegexSelectorsAreNotRun() throws IOException {
        String html = "<style>p:matches((a|aa)+$){color:red} p:contains(a){color:red} div:has(p){color:red}"
                + " .md\\:block{display:block}</style><div><p>" + "a".repeat(40) + "!</p></div>";
        assertTrue(convert("regex.html", html).contains("aaaa"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void longRowsInsidePanelsAreMeasuredOnce() throws IOException {
        String item = "<div>" + "ab ".repeat(300) + "</div>";
        String row = "<div style=\"display:flex\">" + item.repeat(3_000) + "<div>" + "x".repeat(3_000) + "</div></div>";
        String html = "<style>section{background:#eee}</style>" + "<section><div>".repeat(3) + row
                + "</div></section>".repeat(3) + "<p>end of page</p>";
        assertTrue(convert("rows.html", html).contains("end of page"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aVeryWideLineShrinksThePageOnlySoFar() throws IOException {
        String html = "<pre>" + "y".repeat(100_000) + "</pre><table><tr><td style=\"white-space:nowrap\">"
                + "z ".repeat(50_000) + "</td></tr></table><div style=\"width:99999999px\">w</div><p>tail</p>";
        assertTrue(convert("wideline.html", html).contains("tail"));
    }

    @Test
    void namedGridAreasStayBounded() throws IOException {
        String rows = ("\"" + "a b c d e f g h ".repeat(40) + "\" ").repeat(500);
        String html = "<style>.g{display:grid;grid-template-areas:" + rows + "}.h{display:grid;grid-template-areas:"
                + "\"a a\" \"b c\"}.h:has(>:nth-child(3):last-child){color:blue}</style><div class=g>"
                + "<p>one</p><p>two</p></div><div class=h><p>wide</p><p>left</p><p>right</p></div>";
        String text = convert("areas.html", html);
        assertTrue(text.contains("two") && text.indexOf("wide") < text.indexOf("left") && text.contains("right"));
    }

    @Test
    void nestedLightDarkStops() throws IOException {
        String html = "<p style=\"color:" + "light-dark(".repeat(50_000) + "red" + ")".repeat(50_000) + "\">x</p>";
        assertTrue(convert("lightdark.html", html).contains("x"));
    }

    @Test
    void aGifIsJudgedByItsFrameNotItsScreen() {
        byte[] gif = new byte[64];
        byte[] head = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, gif, 0, 6);
        gif[6] = 1;
        gif[8] = 1;
        gif[13] = 0x2C;
        gif[18] = (byte) 0xB0;
        gif[19] = (byte) 0xB3;
        gif[20] = (byte) 0xB0;
        gif[21] = (byte) 0xB3;
        assertNull(MediaBytes.of(gif), "a 1x1 screen holding a 46000x46000 frame");
    }

    @Test
    void svgWorkIsBudgeted() throws IOException {
        String path = "<path id=\"p\" d=\"M0 0" + " L1 1 L0 1".repeat(20_000) + "\"/>";
        String html = "<svg viewBox=\"0 0 10 10\" width=\"200\" height=\"200\"><defs>" + path + "</defs>"
                + "<use href=\"#p\"/>".repeat(20_000) + "</svg><p>after</p>";
        assertTrue(convert("svguse.html", html).contains("after"));
        String points = "<svg viewBox=\"0 0 10 10\"><polygon points=\"" + "1,1 ".repeat(1_500_000) + "\"/></svg><p>poly</p>";
        assertTrue(convert("points.html", points).contains("poly"));
    }

    @Test
    void oddCssValuesAreIgnored() throws IOException {
        String html = "<p style=\"line-height:1.2.3;font-weight:99999999999\">one</p><p style=\"line-height:.\">two</p>"
                + "<div style=\"display:flex;gap:\"><span>a</span><span>b</span></div>"
                + "<div style=\"display:grid;grid-template-columns:1fr repeat(\"><div>c</div><div>d</div></div>"
                + "<div style=\"display:grid;grid-template-columns:repeat(auto-fill,minmax(\"><div>e</div><div>f</div></div>";
        String text = convert("odd.html", html);
        for (String s : new String[] {"one", "two", "a", "c", "e"}) {
            assertTrue(text.contains(s), s + " in " + text);
        }
    }

    @Test
    void aJsonTableKeepsAtMostAWordTableOfColumns() throws IOException {
        String cells = "{\"span\":63},".repeat(5_000) + "{\"span\":63}";
        Path in = Files.writeString(dir.resolve("wide.json"), "{\"body\":[{\"type\":\"table\",\"columns\":["
                + "1,".repeat(5_000) + "1],\"rows\":[{\"cells\":[" + cells + "]}]}]}", StandardCharsets.UTF_8);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("wide.pdf"), OfficeToPdf.Options.defaults());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("63 columns")), r.warnings().toString());
    }

    @Test
    void emptyJsonValuesShareOneObject() throws IOException {
        Object a = stirling.software.officeconvert.json.Json.parse("[{},{},[],[]]");
        java.util.List<?> list = (java.util.List<?>) a;
        assertEquals(4, list.size());
        assertTrue(list.get(0) == list.get(1) && list.get(2) == list.get(3));
    }
}
