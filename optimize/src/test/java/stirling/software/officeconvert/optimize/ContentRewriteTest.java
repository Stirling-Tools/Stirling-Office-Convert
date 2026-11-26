package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ContentRewriteTest {

    private static String rewrite(String content, boolean pageStart) {
        byte[] out = ContentRewrite.rewrite(content.getBytes(StandardCharsets.ISO_8859_1), pageStart);
        return out == null ? null : new String(out, StandardCharsets.ISO_8859_1).strip().replaceAll("\\s+", " ");
    }

    @Test
    void siblingTextRunsShareOneStateAndOneTextObject() {
        String run = "q q 0 0 0 rg BT /F1 9.96 Tf 1 0 0 -1 1.92 %s Tm 100 Tz -.0171 Tc [(a)]TJ ET Q Q ";

        String out = rewrite(run.formatted("9.4518") + run.formatted("21.8118") + run.formatted("34.1718"), true);

        assertEquals("q 0 0 0 rg BT /F1 9.96 Tf 1 0 0 -1 1.92 9.4518 Tm -.0171 Tc [(a)]TJ 0 -12.36 Td [(a)]TJ"
                + " 0 -12.36 Td [(a)]TJ ET Q", out);
    }

    @Test
    void aGroupIsNotJoinedToOneThatDrawsBeforeResettingWhatTheFirstChanged() {
        String in = "q 2 Tc BT /F1 9 Tf 9 0 0 9 0 0 Tm (a)Tj ET Q q BT /F1 9 Tf 9 0 0 9 0 9 Tm (b)Tj 2 Tc ET Q";

        assertNull(rewrite(in, true));
    }

    @Test
    void aGroupThatPaintsIsLeftAlone() {
        assertNull(rewrite("q 1 0 0 rg 0 0 10 10 re f Q q 1 0 0 rg 0 0 10 10 re f Q", false));
    }

    @Test
    void textObjectsStaySeparateWhenTheRenderModeMayClip() {
        String in = "BT /F1 1 Tf 9 0 0 9 0 0 Tm (a)Tj ET BT 9 0 0 9 0 90 Tm (b)Tj ET";

        assertEquals("BT /F1 1 Tf 9 0 0 9 0 0 Tm (a)Tj 0 10 Td (b)Tj ET", rewrite(in, true));
        assertNull(rewrite(in, false));
        assertNull(rewrite("7 Tr " + in, true));
    }

    @Test
    void aGroupWhoseTextClipsIsNotJoinedToTheNext() {
        String in = "q 7 Tr BT /F1 48 Tf 1 0 0 1 20 40 Tm (OOOO) Tj ET Q q 0 Tr BT /F1 48 Tf 1 0 0 1 20 40 Tm (XXXX) Tj ET Q";

        String out = rewrite(in, true);

        assertTrue(out == null || out.contains("Q q"), out);
    }

    @Test
    void aTextObjectThatClipsKeepsItsEnd() {
        String in = "BT /F1 48 Tf 7 Tr 1 0 0 1 20 40 Tm (OOOO) Tj 0 Tr ET BT 1 0 0 1 20 40 Tm (XXXX) Tj ET";

        String out = rewrite(in, true);

        assertTrue(out == null || out.contains("ET BT"), out);
    }

    @Test
    void textObjectsAreNotJoinedOnceTransparencyMayApply() {
        assertNull(rewrite("/GS1 gs BT /F1 1 Tf 9 0 0 9 0 0 Tm (a)Tj ET BT 9 0 0 9 0 90 Tm (b)Tj ET", true));
    }

    @Test
    void aTextObjectThatMovesRelativelyFirstKeepsItsReset() {
        assertNull(rewrite("BT 9 0 0 9 5 5 Tm (a)Tj ET BT 3 4 Td (b)Tj ET", true));
    }

    @Test
    void aMatrixThatScalesOrRotatesDifferentlyStaysAbsolute() {
        assertNull(rewrite("BT 2 0 0 2 0 0 Tm (a)Tj 1 0 0 1 0 10 Tm (b)Tj ET", false));
        assertNull(rewrite("BT 3 0 0 3 0 0 Tm (a)Tj 3 0 0 3 1 0 Tm (b)Tj ET", false));
    }

    @Test
    void theFirstPlacementInATextObjectBecomesAMoveFromTheOrigin() {
        assertEquals("BT 5 5 Td (a)Tj ET", rewrite("BT 1 0 0 1 5 5 Tm (a)Tj ET", false));
    }

    @Test
    void aSettingInheritedFromOutsideTheStreamIsNotAssumed() {
        assertNull(rewrite("100 Tz 0 g BT /F1 9 Tf (a)Tj ET", false));
        assertEquals("BT /F1 9 Tf (a)Tj ET", rewrite("100 Tz 0 g BT /F1 9 Tf (a)Tj ET", true));
    }

    @Test
    void aRestoreBringsBackTheOuterSetting() {
        assertEquals("1 0 0 rg q 0 1 0 rg Q 0 0 1 1 re f",
                rewrite("1 0 0 rg q 0 1 0 rg Q 1 0 0 rg 0 0 1 1 re f", false));
        assertNull(rewrite("q 1 0 0 rg Q 1 0 0 rg 0 0 1 1 re f", false));
    }

    @Test
    void aStreamWithAnInlineImageIsLeftAlone() {
        assertNull(rewrite("q q BI /W 2 /H 1 /CS /G /BPC 8 ID xx EI Q Q", false));
    }

    @Test
    void contentOverTheInputCapIsNotRewritten() {
        String unit = "BT 1 0 0 1 5 5 Tm (a)Tj ET\n";
        assertNotNull(rewrite(unit, false));
        byte[] big = unit.repeat((int) (ContentRewrite.MAX_INPUT_BYTES / unit.length()) + 1)
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(big.length > ContentRewrite.MAX_INPUT_BYTES);
        assertNull(ContentRewrite.rewrite(big, false));
    }
}
