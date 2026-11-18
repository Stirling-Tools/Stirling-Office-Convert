package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

class TexFormulaTest {

    private static final FontInfo CMMI = new FontInfo("Cambria Math", false, true, true, false, false, "CMMI10", true);
    private static final FontInfo CMSY = new FontInfo("Cambria Math", false, false, true, false, true, "CMSY10", true);
    private static final FontInfo CMEX = new FontInfo("Cambria Math", false, false, true, false, true, "CMEX10", true);
    private static final FontInfo CMR = new FontInfo("Times New Roman", false, false, true, false, false, "CMR10", true);
    private static final FontInfo CMR7 = new FontInfo("Times New Roman", false, false, true, false, false, "CMR7", true);

    private static Glyph g(List<Glyph> out, String text, float x, float width, float y, float size, FontInfo font) {
        Glyph glyph = new Glyph(text, x, width, y, size, 0.75f * size, 0.25f * size, font, 0, out.size(), 3.3f,
                false, font == CMMI);
        out.add(glyph);
        return glyph;
    }

    private static void word(List<Glyph> out, String text, float x, float y) {
        for (int i = 0; i < text.length(); i++) {
            g(out, text.substring(i, i + 1), x + 5 * i, 5, y, 10, CMR);
        }
    }

    private static String math(List<Glyph> glyphs) {
        return glyphs.stream().map(x -> x.math).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static List<String> maths(List<Glyph> glyphs) {
        return glyphs.stream().map(x -> x.math).filter(Objects::nonNull).distinct().toList();
    }

    @Test
    void inlineMathTakesItsBodyFontDigitsAndOperatorsButNotTheProse() {
        List<Glyph> in = new ArrayList<>();
        word(in, "Let", 50, 100);
        g(in, "f", 70, 5, 100, 10, CMMI);
        g(in, "(", 75, 4, 100, 10, CMR);
        g(in, "x", 79, 5, 100, 10, CMMI);
        g(in, ")", 84, 4, 100, 10, CMR);
        g(in, "=", 91, 8, 100, 10, CMR);
        g(in, "x", 102, 5, 100, 10, CMMI);
        g(in, "2", 107, 3.5f, 96, 7, CMR7);
        g(in, "+", 113, 8, 100, 10, CMR);
        g(in, "1", 124, 5, 100, 10, CMR);
        g(in, ".", 129, 3, 100, 10, CMR);
        word(in, "and", 136, 100);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals(1, maths(out).size(), "one formula");
        String m = math(out);
        assertTrue(m.contains("<m:sSup>") && m.contains(">2<") && m.contains("1"), m);
        assertFalse(m.contains("Let") || m.contains("and") || m.contains("."), m);
        assertTrue(out.stream().filter(x -> x.text.equals(".")).allMatch(x -> x.math == null), "full stop stays text");
    }

    @Test
    void proseBetweenTwoFormulasStaysText() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 50, 5, 100, 10, CMMI);
        g(in, "≤", 58, 8, 100, 10, CMSY);
        g(in, "y", 69, 5, 100, 10, CMMI);
        word(in, "and", 78, 100);
        g(in, "α", 97, 6, 100, 10, CMMI);
        g(in, "∈", 106, 7, 100, 10, CMSY);
        g(in, "R", 116, 7, 100, 10, new FontInfo("Cambria Math", false, false, true, false, true, "MSBM10", true));
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals(2, maths(out).size(), maths(out).toString());
        assertTrue(out.stream().filter(x -> "and".contains(x.text) && x.font == CMR).allMatch(x -> x.math == null));
        assertTrue(maths(out).get(1).contains("ℝ") || maths(out).get(0).contains("ℝ"), "blackboard R");
    }

    @Test
    void tableRulesAreNotFractionBars() {
        List<Glyph> in = new ArrayList<>();
        g(in, "3", 60, 5, 95, 10, CMR);
        g(in, "×", 67, 7, 95, 10, CMSY);
        g(in, "3", 76, 5, 95, 10, CMR);
        g(in, "5", 60, 5, 112, 10, CMR);
        g(in, "×", 67, 7, 112, 10, CMSY);
        g(in, "5", 76, 5, 112, 10, CMR);
        Rule row = new Rule(true, 100, 40, 200, 0.4f, 0);
        Rule side = new Rule(false, 40, 85, 120, 0.4f, 0);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(row, side), List.of()).glyphs();
        assertEquals(2, maths(out).size(), "one formula per cell");
        assertTrue(maths(out).stream().noneMatch(m -> m.contains("<m:f>")), maths(out).toString());
    }

    @Test
    void aHangingDisplaySumTakesItsLimits() {
        List<Glyph> in = new ArrayList<>();
        Glyph sum = g(in, "∑", 100, 14, 92, 10, CMEX);
        sum.hang = 7.6f;
        g(in, "∞", 103, 7, 89, 7, CMSY);
        g(in, "k", 100, 4, 113, 7, CMMI);
        g(in, "=", 104, 6, 113, 7, CMR7);
        g(in, "1", 110, 3.5f, 113, 7, CMR7);
        g(in, "x", 118, 5, 100, 10, CMMI);
        String m = math(FormulaLines.rebuild(in, List.of(), List.of()).glyphs());
        assertNotNull(m);
        assertTrue(m.contains("<m:nary>") && m.contains("<m:sub>") && m.contains("k=1") && m.contains("∞"), m);
    }

    @Test
    void tallParenthesesAroundRowsBecomeAMatrixDelimiter() {
        List<Glyph> in = new ArrayList<>();
        g(in, "A", 80, 7, 100, 10, CMMI);
        g(in, "=", 90, 8, 100, 10, CMR);
        Glyph open = g(in, "(", 101, 5, 88, 10, CMEX);
        open.hang = 10f;
        g(in, "a", 108, 5, 95, 10, CMMI);
        g(in, "b", 118, 5, 95, 10, CMMI);
        g(in, "c", 108, 5, 107, 10, CMMI);
        g(in, "d", 118, 5, 107, 10, CMMI);
        Glyph close = g(in, ")", 125, 5, 88, 10, CMEX);
        close.hang = 10f;
        String m = math(FormulaLines.rebuild(in, List.of(), List.of()).glyphs());
        assertNotNull(m);
        assertTrue(m.contains("<m:d>") && m.contains("<m:eqArr>") && m.contains("m:begChr m:val=\"(\""), m);
    }

    @Test
    void stackedProseLinesAreNotJoinedThroughTheirFormulas() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 50, 5, 100, 10, CMMI);
        g(in, "+", 58, 8, 100, 10, CMR);
        g(in, "y", 69, 5, 100, 10, CMMI);
        g(in, "x", 51, 5, 112.5f, 10, CMMI);
        g(in, "−", 59, 8, 112.5f, 10, CMSY);
        g(in, "z", 70, 5, 112.5f, 10, CMMI);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertEquals(2, maths(out).size(), "each line keeps its own formula");
        assertNull(maths(out).stream().filter(m -> m.contains("eqArr")).findFirst().orElse(null));
    }

    @Test
    void manyRulesBesideAFormulaStayCheap() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 50, 5, 100, 10, CMMI);
        g(in, "+", 58, 8, 100, 10, CMR);
        g(in, "y", 69, 5, 100, 10, CMMI);
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            rules.add(new Rule(i % 2 == 0, 200 + i * 0.01f, 10, 40, 0.4f, 0));
        }
        long start = System.nanoTime();
        List<Glyph> out = FormulaLines.rebuild(in, rules, List.of()).glyphs();
        assertEquals(1, maths(out).size());
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "budgeted");
    }

    @Test
    void anArrowShaftIsNotAFractionBar() {
        List<Glyph> in = new ArrayList<>();
        g(in, "x", 60, 5, 95, 10, CMMI);
        g(in, "y", 60, 5, 112, 10, CMMI);
        Rule shaft = new Rule(true, 100, 55, 70, 0.4f, 0);
        VectorMark head = new VectorMark(68, 98, 73, 102, 3, false, true, true, false, 0, false, 0, 0f, false);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(shaft), List.of(), List.of(head)).glyphs();
        assertTrue(maths(out).stream().noneMatch(m -> m.contains("<m:f>")), maths(out).toString());
    }

    @Test
    void aFormulaLabellingADiagramIsLeftAlone() {
        List<Glyph> in = new ArrayList<>();
        g(in, "f", 50, 5, 100, 10, CMMI);
        g(in, "(", 55, 4, 100, 10, CMR);
        g(in, "x", 59, 5, 100, 10, CMMI);
        g(in, ")", 64, 4, 100, 10, CMR);
        VectorMark arrowHead = new VectorMark(70, 94, 76, 99, 3, false, true, true, false, 0, false, 0, 0f, false);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of(), List.of(arrowHead)).glyphs();
        assertTrue(maths(out).isEmpty(), "diagram labels stay as they are");
    }

    @Test
    void aNameBeforeAnAffiliationMarkStaysText() {
        List<Glyph> in = new ArrayList<>();
        word(in, "Ghosh", 50, 100);
        g(in, "8", 75, 3.5f, 96, 7, CMR7);
        g(in, "1", 78.5f, 3.5f, 96, 7, CMR7);
        g(in, ",", 82, 2, 96, 7, CMMI);
        g(in, "9", 84, 3.5f, 96, 7, CMR7);
        List<Glyph> out = FormulaLines.rebuild(in, List.of(), List.of()).glyphs();
        assertTrue(out.stream().filter(x -> "Ghosh".contains(x.text) && x.font == CMR).allMatch(x -> x.math == null));
    }
}
