package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class FormulaLinesTest {

    private static final FontInfo ITALIC =
            new FontInfo("Times New Roman", false, true, true, false, false, "MathJax_Math-Italic", true);
    private static final FontInfo MAIN =
            new FontInfo("Times New Roman", false, false, true, false, false, "MathJax_Main-Regular", true);
    private static final FontInfo LARGE =
            new FontInfo("Times New Roman", false, false, true, false, false, "MathJax_Size2-Regular", true);
    private static final FontInfo PLAIN =
            new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    private static Glyph g(List<Glyph> out, String text, float x, float width, float y, float size, FontInfo font) {
        Glyph glyph = new Glyph(text, x, width, y, size, 0.75f * size, 0.25f * size, font, 0, out.size(), 0.3f * size,
                false, font == ITALIC);
        out.add(glyph);
        return glyph;
    }

    private static String text(List<Glyph> glyphs) {
        return glyphs.stream().sorted(Comparator.comparingDouble((Glyph x) -> x.x)).map(x -> x.text + mark(x))
                .collect(Collectors.joining());
    }

    private static String mark(Glyph x) {
        return x.vertAlign > 0 ? "^" : x.vertAlign < 0 ? "_" : "";
    }

    private static List<Glyph> sum(FontInfo italic, FontInfo main, FontInfo large) {
        List<Glyph> out = new ArrayList<>();
        g(out, "k", 104.2f, 3, 93.1f, 5.5f, italic);
        g(out, "∑", 100, 11, 100, 7.8f, large);
        g(out, "i", 101.1f, 2, 110.6f, 5.5f, italic);
        g(out, "=", 103, 4, 110.6f, 5.5f, main);
        g(out, "1", 107.3f, 3, 110.6f, 5.5f, main);
        g(out, "f", 111.5f, 4, 100, 7.8f, italic);
        g(out, "i", 115.5f, 2, 103.3f, 5.5f, italic);
        g(out, "=", 121.1f, 6, 100, 7.8f, main);
        g(out, "N", 129.3f, 6, 100, 7.8f, italic);
        return out;
    }

    @Test
    void limitsOfABigOperatorBecomeScriptsOnOneLine() {
        List<Glyph> in = sum(ITALIC, MAIN, LARGE);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals("∑i_=_1_k^fi_ = N", text(out), "lower limit as subscript, upper as superscript, then the summand");
        assertEquals(1, out.stream().map(x -> x.baseline).distinct().count(), "one baseline");
    }

    @Test
    void textFontsAreLeftAlone() {
        List<Glyph> in = sum(PLAIN, PLAIN, PLAIN);
        assertSame(in, FormulaLines.rebuild(in, List.of(), List.of()).glyphs());
    }

    @Test
    void aStackedFractionReadsAsASlash() {
        List<Glyph> in = new ArrayList<>();
        g(in, "1", 100.5f, 4, 96, 7.8f, MAIN);
        g(in, "N", 100, 6, 107, 7.8f, ITALIC);
        g(in, "∗", 109, 4, 100, 7.8f, MAIN);
        g(in, "x", 115, 4.5f, 100, 7.8f, ITALIC);
        Rule bar = new Rule(true, 98, 100, 106, 0.5f, 0);
        FormulaLines.Rebuilt r = FormulaLines.rebuild(in, List.of(bar), List.of());
        assertEquals("1/N ∗ x", text(r.glyphs()));
        assertTrue(r.consumed().contains(bar), "the fraction bar is not drawn again");
    }

    @Test
    void aRootCoversWhatIsUnderItsBar() {
        List<Glyph> in = new ArrayList<>();
        g(in, "√", 100, 8, 102, 7.8f, LARGE);
        g(in, "a", 109, 4, 100, 7.8f, ITALIC);
        g(in, "+", 115, 6, 100, 7.8f, MAIN);
        g(in, "b", 123, 4, 100, 7.8f, ITALIC);
        Rule bar = new Rule(true, 92, 108, 128, 0.5f, 0);
        assertEquals("√(a + b)", text(FormulaLines.rebuild(in, List.of(bar), List.of()).glyphs()));
    }

    @Test
    void aBinomialStacksItsRows() {
        List<Glyph> in = new ArrayList<>();
        g(in, "(", 100, 5, 105, 7.8f, LARGE);
        g(in, "n", 106, 4, 99, 7.8f, ITALIC);
        g(in, "k", 106, 4, 109.7f, 7.8f, ITALIC);
        g(in, ")", 111, 5, 105, 7.8f, LARGE);
        assertEquals("(n¦k)", text(FormulaLines.rebuild(in, List.of(), List.of()).glyphs()));
    }

    @Test
    void formulasOnSeparateLinesStaySeparate() {
        List<Glyph> in = sum(ITALIC, MAIN, LARGE);
        for (Glyph x : sum(ITALIC, MAIN, LARGE)) {
            g(in, x.text, x.x, x.width, x.baseline + 25, x.size, x.font);
        }
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals(2, out.stream().map(x -> x.baseline).distinct().count());
    }

    @Test
    void anInlineLineKeepsItsScriptsAndSpaces() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 100, 4.5f, 100, 7.8f, ITALIC);
        g(in, "i", 104.6f, 2, 103.3f, 5.5f, ITALIC);
        g(in, "+", 109, 6, 100, 7.8f, MAIN);
        g(in, "y", 117, 4, 100, 7.8f, ITALIC);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals("xi_ + y", text(out));
        assertEquals(117 + 4, out.stream().mapToDouble(Glyph::right).max().orElseThrow(), 0.01, "keeps its width");
    }

    @Test
    void aCasesBlockReadsRowByRow() {
        List<Glyph> in = new ArrayList<>();
        g(in, "{", 100, 6, 105, 7.8f, LARGE);
        g(in, "p", 106, 4, 100, 7.8f, ITALIC);
        g(in, "f", 130, 3, 100, 7.8f, MAIN);
        g(in, "o", 133, 4, 100, 7.8f, MAIN);
        g(in, "r", 137, 3, 100, 7.8f, MAIN);
        g(in, "1", 106, 4, 109.7f, 7.8f, MAIN);
        g(in, "f", 130, 3, 109.7f, 7.8f, MAIN);
        g(in, "o", 133, 4, 109.7f, 7.8f, MAIN);
        g(in, "r", 137, 3, 109.7f, 7.8f, MAIN);
        assertEquals("{p for\u00A61 for", text(FormulaLines.rebuild(in, List.of(), List.of()).glyphs()));
    }

    @Test
    void spacedDotsBecomeAnEllipsis() {
        List<Glyph> in = new ArrayList<>();
        g(in, "1", 100, 4, 100, 7.8f, MAIN);
        g(in, ",", 104, 2, 100, 7.8f, MAIN);
        g(in, ".", 107.3f, 2, 100, 7.8f, MAIN);
        g(in, ".", 110.6f, 2, 100, 7.8f, MAIN);
        g(in, ".", 113.9f, 2, 100, 7.8f, MAIN);
        g(in, "n", 117.2f, 4, 100, 7.8f, ITALIC);
        assertEquals("1, \u2026 n", text(FormulaLines.rebuild(in, List.of(), List.of()).glyphs()));
    }

    @Test
    void aTextSizeBinomialAfterABracketStacks() {
        List<Glyph> in = new ArrayList<>();
        g(in, "(", 100, 4, 102, 7.8f, LARGE);
        g(in, "n", 104.5f, 3, 98, 5.5f, ITALIC);
        g(in, "x", 104.5f, 3, 105, 5.5f, ITALIC);
        g(in, ")", 108, 4, 102, 7.8f, LARGE);
        g(in, "p", 113.5f, 4, 102, 7.8f, ITALIC);
        assertEquals("(n\u00A6x) p", text(FormulaLines.rebuild(in, List.of(), List.of()).glyphs()));
    }

    private static String math(List<Glyph> out) {
        String math = out.getFirst().math;
        assertTrue(out.stream().allMatch(x -> math.equals(x.math)), "every glyph of a formula carries the same equation");
        return math;
    }

    @Test
    void aSumBecomesAWordNaryWithItsSummand() {
        String math = math(FormulaLines.rebuild(sum(ITALIC, MAIN, LARGE), List.of(), List.of()).glyphs());
        assertTrue(math.startsWith("<m:nary><m:naryPr><m:chr m:val=\"\u2211\"/><m:limLoc m:val=\"undOvr\"/>"), math);
        assertTrue(math.contains("<m:sub><m:r>") && math.contains(">i=1</m:t>"), math);
        assertTrue(math.contains("<m:sup><m:r>") && math.contains(">k</m:t>"), math);
        assertTrue(math.contains("<m:e><m:sSub><m:e>"), "the summand f with its subscript is the body: " + math);
    }

    @Test
    void aFractionAndARootBecomeWordStructures() {
        List<Glyph> in = new ArrayList<>();
        g(in, "1", 100.5f, 4, 96, 7.8f, MAIN);
        g(in, "N", 100, 6, 107, 7.8f, ITALIC);
        Rule bar = new Rule(true, 98, 100, 106, 0.5f, 0);
        String fraction = math(FormulaLines.rebuild(in, List.of(bar), List.of()).glyphs());
        assertTrue(fraction.contains("<m:f><m:num>") && fraction.contains("<m:den>"), fraction);
        assertTrue(!fraction.contains(">/</m:t>"), "the slash is only for the one-line text: " + fraction);
        List<Glyph> root = new ArrayList<>();
        g(root, "\u221A", 100, 8, 102, 7.8f, LARGE);
        g(root, "a", 109, 4, 100, 7.8f, ITALIC);
        g(root, "+", 115, 6, 100, 7.8f, MAIN);
        g(root, "b", 123, 4, 100, 7.8f, ITALIC);
        String radical = math(FormulaLines.rebuild(root, List.of(new Rule(true, 92, 108, 128, 0.5f, 0)), List.of())
                .glyphs());
        assertTrue(radical.contains("<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/><m:e>"), radical);
        assertTrue(!radical.contains("(") && !radical.contains("\u221A"), "brackets and the root sign are drawn by Word");
    }

    @Test
    void connectingWordsStayUpright() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 100, 4.5f, 100, 7.8f, ITALIC);
        g(in, "i", 104.6f, 2, 103.3f, 5.5f, ITALIC);
        g(in, "a", 108.5f, 4, 100, 7.8f, ITALIC);
        g(in, "n", 112.5f, 4, 100, 7.8f, ITALIC);
        g(in, "d", 116.5f, 4, 100, 7.8f, ITALIC);
        g(in, "y", 123, 4, 100, 7.8f, ITALIC);
        String math = math(FormulaLines.rebuild(in, List.of(), List.of()).glyphs());
        assertTrue(math.contains("<m:r><m:rPr><m:nor/></m:rPr>") && math.contains(">and</m:t>"), math);
    }

    private static long cpuMillis(Runnable work) {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        long start = bean.getCurrentThreadCpuTime();
        work.run();
        return (bean.getCurrentThreadCpuTime() - start) / 1_000_000;
    }

    @Test
    void manyLongRulesBesideAFormulaStayCheap() {
        List<Glyph> in = sum(ITALIC, MAIN, LARGE);
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            rules.add(new Rule(true, i * 0.02f, 0, 600, 0.5f, 0));
        }
        long ms = cpuMillis(() -> FormulaLines.rebuild(in, rules, List.of()));
        assertTrue(ms < 3_000, "took " + ms + " ms of CPU");
    }

    @Test
    void aHugeFormulaStaysCheap() {
        List<Glyph> in = new ArrayList<>();
        for (int i = 0; i < 3_900; i++) {
            float x = 10 + (i % 120) * 5f;
            float y = 100 + (i / 120) * 9f;
            g(in, i % 2 == 0 ? "x" : "2", x, 4.5f, i % 2 == 0 ? y : y - 3, i % 2 == 0 ? 7.8f : 5.5f, ITALIC);
        }
        g(in, "\u2211", 0, 11, 100, 7.8f, LARGE);
        long ms = cpuMillis(() -> FormulaLines.rebuild(in, List.of(), List.of()));
        assertTrue(ms < 3_000, "took " + ms + " ms of CPU");
    }

    @Test
    void textThatIsNotValidXmlIsLeftOutOfTheEquation() {
        List<Glyph> in = new ArrayList<>();
        g(in, "1", 100.5f, 4, 96, 7.8f, MAIN);
        g(in, "\uD800", 100, 6, 107, 7.8f, ITALIC);
        g(in, "\uFFFE", 107, 6, 107, 7.8f, ITALIC);
        String math = math(FormulaLines.rebuild(in, List.of(new Rule(true, 98, 100, 113, 0.5f, 0)), List.of()).glyphs());
        assertTrue(math.chars().noneMatch(c -> Character.isSurrogate((char) c) || c == 0xFFFE), math);
    }

    private static List<Glyph> word(String letters, float x, float y) {
        List<Glyph> out = new ArrayList<>();
        for (int k = 0; k < letters.length(); k++) {
            g(out, String.valueOf(letters.charAt(k)), x + 4 * k, 4, y, 7.8f, ITALIC);
        }
        return out;
    }

    @Test
    void wordsTypedWithoutSpacesAreSeparated() {
        List<Glyph> in = word("qwithq", 100, 100);
        in.addAll(word("ifXandYindependent", 100, 130));
        in.addAll(word("Uniform", 100, 160));
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        String byLine = out.stream().sorted(Comparator.comparingDouble((Glyph x) -> x.baseline).thenComparingDouble(x -> x.x))
                .map(x -> x.text).collect(Collectors.joining());
        assertTrue(byLine.contains("q with q"), byLine);
        assertTrue(byLine.contains("if X and Y independent"), byLine);
        assertTrue(byLine.contains("Uniform"), "a word that only contains a connector stays whole: " + byLine);
    }

    @Test
    void aSubscriptInsideASuperscriptKeepsItsPlace() {
        List<Glyph> in = new ArrayList<>();
        g(in, "\u222B", 100, 6, 104, 7.8f, LARGE);
        g(in, "x", 106.5f, 3, 95.5f, 5.5f, ITALIC);
        g(in, "0", 109.5f, 2.5f, 97, 3.9f, MAIN);
        g(in, "a", 106.5f, 3, 108, 5.5f, ITALIC);
        g(in, "f", 113, 4, 100, 7.8f, ITALIC);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        String math = math(out);
        assertTrue(math.contains("<m:sup><m:sSub><m:e>") && math.contains(">0</m:t></m:r></m:sub></m:sSub></m:sup>"),
                "x0 is x with subscript 0 inside the upper limit: " + math);
        assertTrue(out.stream().filter(x -> x.text.equals("0")).allMatch(x -> x.vertAlign == 1),
                "the one-line text keeps it raised with the rest of the limit");
    }
}
