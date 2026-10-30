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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.QueryParser;
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
    void rulesEndingInEscapedOrNonAsciiOrDifferentlyCasedNamesStillApply() {
        String[][] cases = {
            {"<p class=\"caf\u00e9\" id=t>x</p>", ".caf\u00e9"},
            {"<p class=\"p-[2px]\" id=t>x</p>", ".p-\\[2px\\]"},
            {"<p class=\"a+b\" id=t>x</p>", ".a\\+b"},
            {"<p class=\"md:x\" id=t>x</p>", ".md\\:x"},
            {"<p id=\"123\">x</p>", "#\\31 23"},
            {"<p class=\"Outer\" id=t>x</p>", ".OUTER"},
            {"<p class=\"\u0130\" id=t>x</p>", ".i"},
        };
        for (String[] c : cases) {
            Document d = Jsoup.parse("<style>" + c[1] + "{color:red}</style>" + c[0]);
            Element t = d.getElementById("t") != null ? d.getElementById("t") : d.selectFirst("p");
            assertTrue(QueryParser.parse(c[1]).matches(d, t), c[1]);
            assertEquals("red", new Cascade(d, 800).declared(t).get("color"), c[1]);
        }
    }

    @Test
    void escapedNamesAreIndexedByWhatTheyDecodeTo() {
        assertEquals(".a\\+b", Cascade.lastCompound("div .a\\+b"));
        assertEquals("span", Cascade.lastCompound(".a\\+b span"));
        assertEquals("p-[2px]", Cascade.ident(Cascade.lastCompound("div .p-\\[2px\\]"), '.'));
        assertEquals("123", Cascade.ident(Cascade.lastCompound("#\\31 23"), '#'));
        assertEquals("\u00e9t", Cascade.ident(".\\e9t", '.'));
        assertEquals("caf\u00e9", Cascade.ident("p.caf\u00e9:hover", '.'));
        assertEquals("x", Cascade.ident(".x:not(.z)[a=\".q\"]", '.'));
        assertNull(Cascade.ident("p:not(.z)", '.'));
    }

    @Test
    void theAncestorFilterNeverRejectsWhatJsoupMatches() {
        String[][] cases = {
            {"<div class=\"caf\u00e9\"><span id=t>x</span></div>", ".caf\u00e9 span"},
            {"<div id=\"\u00f1and\u00fa\"><span id=t>x</span></div>", "#\u00f1and\u00fa span"},
            {"<div class=\"p-[2px]\"><span id=t>x</span></div>", ".p-\\[2px\\] span"},
            {"<div id=\"123\"><span id=t>x</span></div>", "#\\31 23 span"},
            {"<div class=\"a+b\"><span id=t>x</span></div>", ".a\\+b span"},
            {"<div class=\"a b\"><span id=t>x</span></div>", ".a\\ b span"},
            {"<div class=\"\u0130\"><span id=t>x</span></div>", ".i span"},
            {"<div class=\"\u212a\"><span id=t>x</span></div>", ".k span"},
            {"<div class=\"a\u2003b\"><span id=t>x</span></div>", ".a span"},
            {"<div class=\"\u00e9t\"><span id=t>x</span></div>", ".\\e9t span"},
            {"<div class=\"md:x\"><span id=t>x</span></div>", ".md\\:x span"},
            {"<div class=\"x\"><span id=t>x</span></div>", ":is(.x) span"},
            {"<div class=\"x\"><span id=t>x</span></div>", "div:not(.y) span"},
            {"<div data-a=\"q r\"><span id=t>x</span></div>", "[data-a=\"q r\"] span"},
            {"<div data-a=\"]\"><span id=t>x</span></div>", "[data-a=\"]\"] span"},
            {"<div class=\"Outer\"><span id=t>x</span></div>", ".OUTER span"},
            {"<DIV ID=\"Main\"><span id=t>x</span></DIV>", "div#Main span"},
            {"<div class=\"x\"><p></p><span id=t>x</span></div>", ".x p ~ span"},
            {"<div class=\"x\"><span id=t>x</span></div>", "div.x>span"},
            {"<div class=\"x\"><span id=t>x</span></div>", "div.x\tspan"},
            {"<div class=\"x\"><span id=t>x</span></div>", "div.x\nspan"},
            {"<div class=\"x_y\"><span id=t>x</span></div>", ".x_y span"},
            {"<div class=\"a\"><b class=\"c\"><span id=t>x</span></b></div>", ".a > .c > span"},
            {"<svg><g class=\"k\"><text id=t>x</text></g></svg>", "g.k text"},
            {"<svg><foreignObject><p id=t>x</p></foreignObject></svg>", "foreignObject p"},
        };
        for (String[] c : cases) {
            Document d = Jsoup.parse(c[0]);
            Element t = d.getElementById("t");
            assertTrue(QueryParser.parse(c[1]).matches(d, t), c[1]);
            long[] need = Cascade.above(c[1]);
            assertTrue(need == null || Cascade.within(need, new Cascade(d, 800).ancestry(t)), c[1]);
        }
        assertNotNull(Cascade.above(".a > .c > span"));
        assertNotNull(Cascade.above(".OUTER span"));
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
