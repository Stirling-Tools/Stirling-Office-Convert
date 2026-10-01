package stirling.software.officeconvert.topdf.field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EqFieldTest {

    private static String eq(String instruction) {
        String x = EqField.omml(instruction, "<w:rPr><w:sz w:val=\"20\"/></w:rPr>");
        return x == null ? "" : x;
    }

    @Test
    void fractionsRadicalsAndScriptsBecomeOfficeMath() {
        String f = eq(" EQ \\f(1,x+2) ");
        assertTrue(f.contains("<m:f><m:num><w:r><w:rPr><w:sz w:val=\"20\"/></w:rPr><w:t xml:space=\"preserve\">1</w:t>"
                + "</w:r></m:num><m:den>") && f.contains(">x+2</w:t>"), f);
        assertTrue(eq("EQ \\r(3,x)").contains("<m:rad><m:deg>"), eq("EQ \\r(3,x)"));
        assertTrue(eq("EQ \\r(x)").contains("<m:degHide m:val=\"on\"/>"), eq("EQ \\r(x)"));
        assertTrue(eq("EQ x\\s\\up 8(2)").contains("<m:sSup><m:e/><m:sup>"), eq("EQ x\\s\\up 8(2)"));
        assertTrue(eq("EQ x\\s\\do4(i)").contains("<m:sSub><m:e/><m:sub>"), eq("EQ x\\s\\do4(i)"));
    }

    @Test
    void rubyOverAWordIsAnUpperLimit() {
        String r = eq("EQ \\* jc2 \\* \"Font:MS Mincho\" \\* hps10 \\o\\ad(\\s\\up 10(kanji),base)");
        assertTrue(r.contains("<m:limUpp><m:e>") && r.contains(">base</w:t>") && r.contains("<m:lim>")
                && r.contains(">kanji</w:t>"), r);
        assertFalse(r.contains("Font") || r.contains("hps"), r);
    }

    @Test
    void bracketsListsArraysIntegralsAndBoxes() {
        assertTrue(eq("EQ \\b\\bc\\[(x)").contains("<m:begChr m:val=\"[\"/><m:endChr m:val=\"]\"/>"));
        assertTrue(eq("EQ \\b\\lc\\{(x)").contains("<m:begChr m:val=\"{\"/><m:endChr m:val=\")\"/>"));
        assertTrue(eq("EQ \\l(a,b)").contains(">,</w:t>"));
        String a = eq("EQ \\a\\al\\co2(1,2,3,4)");
        assertTrue(a.contains("<m:count m:val=\"2\"/>") && a.split("<m:mr>").length == 3, a);
        String sum = eq("EQ \\i\\su(i=1,n,i)");
        assertTrue(sum.contains("m:val=\"\u2211\"") && sum.contains("undOvr") && sum.contains(">i=1</w:t>"), sum);
        assertTrue(eq("EQ \\i\\in(0,1,f)").contains("m:val=\"\u222B\"") && eq("EQ \\i\\in(0,1,f)").contains("subSup"));
        String box = eq("EQ \\x\\to\\bo(z)");
        assertTrue(box.contains("hideLeft") && box.contains("hideRight") && !box.contains("hideTop"), box);
    }

    @Test
    void escapesNestingAndJunkAreSafe() {
        String e = eq("EQ \\f(a\\,b,c(d,e))");
        assertTrue(e.contains(">a,b</w:t>") && e.contains(">c(d,e)</w:t>"), e);
        assertTrue(eq("EQ " + "\\f(".repeat(200) + "x").length() < 20_000);
        assertNull(EqField.omml("DATE \\@ \"yyyy\"", ""));
        assertNull(EqField.omml("EQUALS", ""));
        assertTrue(eq("EQ <&>\"").contains("&lt;&amp;&gt;"), eq("EQ <&>\""));
    }
}
