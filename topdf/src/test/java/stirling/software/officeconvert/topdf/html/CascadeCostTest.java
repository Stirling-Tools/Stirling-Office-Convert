package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import stirling.software.officeconvert.topdf.OfficeToPdf;

class CascadeCostTest {

    @TempDir
    Path dir;

    private OfficeToPdf.Result convert(String name, String html) throws IOException {
        Path in = Files.writeString(dir.resolve(name), html, StandardCharsets.UTF_8);
        return OfficeToPdf.convert(in, dir.resolve(name + ".pdf"), OfficeToPdf.Options.defaults()
                .timeout(Duration.ZERO));
    }

    private String text(String name) throws IOException {
        try (PDDocument doc = Loader.loadPDF(dir.resolve(name + ".pdf").toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aSelectorCostsOneStepPerCombinatorAndPseudoClass() {
        assertEquals(1, Cascade.cost(".a"));
        assertEquals(1, Cascade.cost("div.box#main"));
        assertEquals(3, Cascade.cost("ul li a"));
        assertEquals(2, Cascade.cost("ul > li"));
        assertEquals(6, Cascade.cost("div.c0 > p:nth-child(0) span:not(.a):not(.b)"));
        assertEquals(2, Cascade.cost("a[href]"));
    }

    @Test
    void onlyAncestorStepsAreRequiredAbove() {
        assertNull(Cascade.above("span.x"));
        assertNull(Cascade.above("h1 + p"));
        assertNull(Cascade.above(":not(div p) span"));
        assertNotNull(Cascade.above("div p"));
        assertNotNull(Cascade.above("div > p ~ span"));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void rulesNeedingAncestorsStillMatchThroughTheFilter() throws IOException {
        String css = "#main div.box > p em, ul li + li b, article aside i, .Outer span, section > p + p u,"
                + " body :not(.k) > s { display: none }";
        convert("filter.html", "<style>" + css + "</style><div id=\"main\"><div class=\"box\"><p>shown"
                + "<em>HIDDEN1</em></p></div></div><ul><li>a</li><li><b>HIDDEN2</b></li></ul><article><div><aside>"
                + "<i>HIDDEN3</i></aside></div></article><div class=\"outer\"><span>HIDDEN4</span></div>"
                + "<section><p>p1</p><p><u>HIDDEN5</u></p></section><div><s>HIDDEN6</s></div><p>end</p>");
        String text = text("filter.html");
        assertTrue(text.contains("shown") && text.contains("end"), text);
        for (int i = 1; i <= 6; i++) {
            assertFalse(text.contains("HIDDEN" + i), i + ": " + text);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void costlyMatchingIsCutOffSooner() throws IOException {
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            css.append("div.c > p:nth-child(1) span:not(.a):not(.b) { margin: ").append(i).append("px }\n");
        }
        String body = "<div class=\"c\"><p><span>x</span></p></div>".repeat(1000);
        OfficeToPdf.Result r = convert("costly.html", "<style>" + css + "</style>" + body);
        assertTrue(r.warnings().contains(Cascade.LEFT_OUT_WARNING), r.warnings().toString());
    }
}
