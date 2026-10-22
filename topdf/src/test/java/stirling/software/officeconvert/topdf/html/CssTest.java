package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CssTest {

    private static final float VIEWPORT = 602;

    @Test
    void mediaQueriesAnswerAsAPrintedPage() {
        assertTrue(Media.matches("", VIEWPORT));
        assertTrue(Media.matches("print", VIEWPORT));
        assertTrue(Media.matches("all and (min-width: 400px)", VIEWPORT));
        assertFalse(Media.matches("screen", VIEWPORT));
        assertFalse(Media.matches("screen and (min-width: 100px)", VIEWPORT));
        assertTrue(Media.matches("not screen", VIEWPORT));
        assertFalse(Media.matches("speech", VIEWPORT));
        assertTrue(Media.matches("(max-width: 700px)", VIEWPORT));
        assertFalse(Media.matches("(min-width: 1024px)", VIEWPORT));
        assertTrue(Media.matches("(width >= 30em)", VIEWPORT));
        assertFalse(Media.matches("(width >= 60em)", VIEWPORT));
        assertTrue(Media.matches("(min-width: 2000px), print", VIEWPORT));
        assertFalse(Media.matches("(prefers-color-scheme: dark)", VIEWPORT));
        assertFalse(Media.matches("(hover: hover)", VIEWPORT));
        assertFalse(Media.matches("(some-future-feature)", VIEWPORT));
    }

    @Test
    void rulesInsideMediaSupportsAndLayersApplyOnlyWhenTheyMatch() {
        List<Css.Rule> rules = Css.rules("p{color:red} @media (max-width: 300px){p{color:blue}}"
                + " @media print{h1{color:green}} @supports (display:grid){.g{display:grid}}"
                + " @layer base{em{color:gray}} @import url(x.css); a{color:black}", new ArrayList<>(), VIEWPORT);
        List<String> selectors = rules.stream().map(Css.Rule::selector).toList();
        assertEquals(List.of("p", "h1", ".g", "em", "a"), selectors);
    }

    @Test
    void deeplyNestedAtRulesStopAtALimit() {
        String sheet = "@media print{".repeat(10_000) + "p{color:red}" + "}".repeat(10_000);
        assertTrue(Css.rules(sheet, new ArrayList<>(), VIEWPORT).isEmpty());
    }

    @Test
    void selectorListsSplitOnlyAtTheirTopLevel() {
        List<Css.Rule> rules = Css.rules(":is(h1, h2) span, p{color:red}", new ArrayList<>(), VIEWPORT);
        assertEquals(List.of(":is(h1, h2) span", "p"), rules.stream().map(Css.Rule::selector).toList());
    }

    @Test
    void calcMinMaxAndClampWorkInPoints() {
        assertEquals(6.25f, Css.length("calc(10px + 10pt - 15px)", 12, 100), 0.01);
        assertEquals(40f, Css.length("calc(50% - 10pt)", 12, 100), 0.01);
        assertEquals(24f, Css.length("calc(2 * 1em)", 12, 100), 0.01);
        assertEquals(6f, Css.length("min(10pt, 6pt, 8pt)", 12, 100), 0.01);
        assertEquals(10f, Css.length("max(10pt, 6pt)", 12, 100), 0.01);
        assertEquals(20f, Css.length("clamp(20pt, 5%, 40pt)", 12, 100), 0.01);
        assertEquals(30f, Css.length("calc((10pt + 5pt) * 2)", 12, 100), 0.01);
        assertNull(Css.length("calc(10pt / 0)", 12, 100));
        assertNull(Css.length("calc(" + "(".repeat(100) + "1pt" + ")".repeat(100) + ")", 12, 100));
        assertNull(Css.length("calc(1pt + )", 12, 100));
    }

    @Test
    void remCountsInTheRootFontSize() {
        try {
            Css.rootSize(7.5f);
            assertEquals(15f, Css.length("2rem", 12, 0), 0.01);
        } finally {
            Css.clearRootSize();
        }
        assertEquals(24f, Css.length("2rem", 12, 0), 0.01);
    }

    private static Map<String, String> declared(String html, String selector) {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        return new Cascade(doc, VIEWPORT).declared(doc.selectFirst(selector));
    }

    @Test
    void importantBeatsSpecificityOrderAndTheStyleAttribute() {
        String css = "<style>@media print{.head{display:none !important}} header.head#h{display:block}"
                + " p{color:red!important} #x{color:blue}</style>";
        assertEquals("none", declared(css + "<header id=h class=head style=\"display:flex\">x</header>", "header")
                .get("display"));
        assertEquals("red", declared(css + "<p id=x style=\"color:green\">x</p>", "p").get("color"));
        assertEquals("green", declared("<style>p{color:red!important}</style><p style=\"color:green!important\">x</p>",
                "p").get("color"));
    }

    @Test
    void hasMatchesOnlyOneChildCompound() {
        String css = "<style>.g:has(>:nth-child(3):last-child){grid-template-areas:\"a a\" \"b c\"}"
                + ".g:has(> p span){color:red}.g:has(>p:contains(x)){color:blue}</style>";
        Map<String, String> d = declared(css + "<div class=g><p>1</p><p>2</p><p><span>x</span></p></div>", "div");
        assertEquals("\"a a\" \"b c\"", d.get("grid-template-areas"));
        assertNull(d.get("color"));
        assertNull(declared(css + "<div class=g><p>1</p><p>2</p></div>", "div").get("grid-template-areas"));
    }

    @Test
    void escapedUtilityClassesMatch() {
        String html = "<style>.md\\:hidden{display:none}.w-1\\/2{width:50%}</style><div class=\"md:hidden w-1/2\">x</div>";
        Map<String, String> d = declared(html, "div");
        assertEquals("none", d.get("display"));
        assertEquals("50%", d.get("width"));
    }

    @Test
    void hugeStyleSheetsStillMatchQuickly() {
        StringBuilder css = new StringBuilder("<style>");
        for (int i = 0; i < 100_000; i++) {
            css.append(".c").append(i).append(" > span.k").append(i).append("{color:red}");
        }
        css.append(".target{color:blue}</style>");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            body.append("<div class=c").append(i).append("><span class=k").append(i).append(">t</span></div>");
        }
        assertEquals("blue", declared(css + body.toString() + "<p class=target>x</p>", "p.target").get("color"));
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(css + body.toString());
        Cascade c = new Cascade(doc, VIEWPORT);
        for (org.jsoup.nodes.Element e : doc.getAllElements()) {
            assertEquals(e.is("span") ? "red" : null, c.declared(e).get("color"));
        }
    }

    @Test
    void coloursWithAlphaShowAsOnWhitePaper() {
        assertEquals(-1, Css.color("#0000"));
        assertEquals(-1, Css.color("#00000000"));
        assertEquals(-1, Css.color("rgba(0, 0, 0, 0.05)"));
        assertEquals(0x808080, Css.color("rgb(0 0 0 / 50%)"), 0x010101);
        assertEquals(0xFF0000, Css.color("#f00f"));
        assertEquals(0x336699, Css.color("#336699ff"));
        assertEquals(0xFF0000, Css.color("hsl(0, 100%, 50%)"));
        assertEquals(0x00FF00, Css.color("hsl(120deg 100% 50%)"));
        assertEquals(0x0000FF, Css.color("light-dark(#00f, #fff)"));
        assertNull(Css.color("currentcolor"));
        assertNull(Css.color("#12345"));
    }

    @Test
    void tablesInQuirksModeDoNotInheritTextStyles() {
        String quirks = "<html><body style=\"text-align:center;font-size:30px\"><table><tr><td>x</td></tr></table>";
        assertEquals("start", declared(quirks, "table").get("text-align"));
        assertNull(declared("<!DOCTYPE html>" + quirks, "table").get("text-align"));
    }

    @Test
    void bareMonospaceIsSetAtThirteenPixelsWhileSizesFollowKeywords() {
        Look root = new Look();
        Map<String, String> mono = Map.of("font-family", "monospace");
        assertEquals(9.75f, root.child(mono, 400, false).size, 0.01);
        assertEquals(12f, root.child(Map.of("font-family", "monospace, monospace"), 400, false).size, 0.01);
        Look em = root.child(Map.of("font-family", "monospace", "font-size", "1.2em"), 400, false);
        assertEquals(11.7f, em.size, 0.01);
        Look sized = root.child(Map.of("font-size", "20px"), 400, false);
        assertEquals(15f, sized.child(mono, 400, false).size, 0.01);
        Look inside = root.child(mono, 400, false).child(Map.of("font-family", "Arial"), 400, true);
        assertEquals(12f, inside.size, 0.01);
    }

    @Test
    void customPropertiesInheritAndFallBack() {
        Vars.Scope root = Vars.scope(Vars.Scope.EMPTY, Map.of("--brand", "#336699", "--gap", "var(--unit)",
                "--unit", "8px"));
        Map<String, String> d = new HashMap<>(Map.of("color", "var(--brand)", "margin-top", "var(--gap)",
                "padding", "var(--missing, 4px)", "display", "var(--nothing)"));
        Vars.resolve(d, Vars.scope(root, d));
        assertEquals("#336699", d.get("color"));
        assertEquals("8px", d.get("margin-top"));
        assertEquals("4px", d.get("padding"));
        assertFalse(d.containsKey("display"), "an unresolvable var() leaves the property undeclared");
    }

    @Test
    void selfReferencingOrExplodingVariablesAreCutOff() {
        Map<String, String> own = new HashMap<>();
        own.put("--a", "var(--a)");
        StringBuilder bomb = new StringBuilder("x");
        for (int i = 0; i < 12; i++) {
            own.put("--b" + i, "var(--b" + (i + 1) + ") var(--b" + (i + 1) + ")");
        }
        own.put("--b12", bomb.toString());
        Vars.Scope s = Vars.scope(Vars.Scope.EMPTY, own);
        Map<String, String> d = new HashMap<>(Map.of("color", "var(--a)", "font-family", "var(--b0)"));
        Vars.resolve(d, s);
        assertFalse(d.containsKey("color"));
        assertFalse(d.containsKey("font-family"));
    }
}
