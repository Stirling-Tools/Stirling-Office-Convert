package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.fonts.CharStringExpansion;

class FontProgramExpansionTest {

    @Test
    void theFontBudgetStopsTheScanOnceItIsSpent() {
        int each = CharStringExpansion.MAX_TOKENS;
        int needed = (int) (CharStringExpansion.MAX_FONT_TOKENS / each) + 1;
        Counting font = new Counting(needed * 4, each);

        assertThrows(IOException.class, font::checkExpansion);
        assertTrue(font.expanded <= needed, font.expanded + " glyphs expanded, " + needed + " needed");
    }

    private static final class Counting extends FontProgram {

        private final int count;

        private final int tokens;

        int expanded;

        Counting(int count, int tokens) {
            this.count = count;
            this.tokens = tokens;
        }

        @Override
        int count() {
            return count;
        }

        @Override
        String name() {
            return "Counting";
        }

        @Override
        String name(int gid) {
            return "g" + gid;
        }

        @Override
        int gid(String name) {
            return 0;
        }

        @Override
        GeneralPath rawPath(int gid) {
            return new GeneralPath();
        }

        @Override
        double rawWidth(int gid) {
            return 0;
        }

        @Override
        CharStringExpansion.Result expansion(int gid) {
            expanded++;
            return new CharStringExpansion.Result(tokens, new int[0]);
        }

        @Override
        List<Number> matrix() {
            return List.of();
        }

        @Override
        String weight() {
            return null;
        }

        @Override
        float italicAngle() {
            return 0;
        }
    }
}
