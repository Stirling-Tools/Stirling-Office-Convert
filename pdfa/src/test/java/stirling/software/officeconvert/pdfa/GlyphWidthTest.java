package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlyphWidthTest {

    private static byte[] fixed(double v) {
        int f = (int) Math.round(v * 65_536);
        return new byte[] {(byte) 255, (byte) (f >> 24), (byte) (f >> 16), (byte) (f >> 8), (byte) f};
    }

    @Test
    void type2WidthKeepsFractionsAndFollowsTheArgumentCount() {
        byte[] cs = FontPrograms.concat(fixed(55.5), FontPrograms.num2(10), FontPrograms.num2(20), new byte[] {21, 14});
        assertEquals(55.5, Type2Width.operand(cs), 1e-9);
        assertTrue(Double.isNaN(Type2Width.operand(FontPrograms.concat(FontPrograms.num2(10), FontPrograms.num2(20),
                new byte[] {21, 14}))));
        assertEquals(300, Type2Width.operand(FontPrograms.concat(FontPrograms.num2(300), new byte[] {14})), 1e-9);
        assertTrue(Double.isNaN(Type2Width.operand(new byte[] {14})));
        assertEquals(-5, Type2Width.operand(FontPrograms.concat(FontPrograms.num2(-5), FontPrograms.num2(1),
                FontPrograms.num2(2), new byte[] {1, 14})), 1e-9);
        assertNull(Type2Width.operand(FontPrograms.concat(FontPrograms.num2(3), new byte[] {10})));
        assertNull(Type2Width.operand(null));
    }

    @Test
    void type1WidthEvaluatesDivBeforeTheSideBearingCommand() {
        byte[] hsbw = FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(5111), FontPrograms.num(10),
                new byte[] {12, 12, 13});
        assertEquals(511.1, Type1Width.of(hsbw), 1e-9);
        byte[] sbw = FontPrograms.concat(FontPrograms.num(0), FontPrograms.num(0), FontPrograms.num(600),
                FontPrograms.num(0), new byte[] {12, 7});
        assertEquals(600, Type1Width.of(sbw), 1e-9);
        assertNull(Type1Width.of(FontPrograms.concat(FontPrograms.num(1), new byte[] {13})));
        assertNull(Type1Width.of(new byte[] {10}));
    }
}
