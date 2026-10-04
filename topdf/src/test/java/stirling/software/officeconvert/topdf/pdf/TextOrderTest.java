package stirling.software.officeconvert.topdf.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TextOrderTest {

    private static final int N = TextOrder.NEUTRAL;

    private static final int L = TextOrder.LEFT;

    private static final int R = TextOrder.RIGHT;

    private static final int D = TextOrder.NUMBER;

    @Test
    void rightToLeftWordsDrawnLeftToRightAreReadFromTheRight() {
        assertArrayEquals(new int[] {4, 3, 2, 1, 0}, TextOrder.order(new int[] {R, N, R, N, R}, true));
    }

    @Test
    void latinAndNumbersInsideRightToLeftTextKeepTheirOwnOrder() {
        // visual: [heb3] [ ] [abc] [ ] [def] [ ] [heb1]; logical: heb1, abc, def, heb3
        int[] order = TextOrder.order(new int[] {R, N, L, N, L, N, R}, true);
        assertArrayEquals(new int[] {6, 5, 2, 3, 4, 1, 0}, order);
        assertArrayEquals(new int[] {2, 1, 0}, TextOrder.order(new int[] {R, N, D}, true));
    }

    @Test
    void hebrewInsideLatinTextIsReversedOnItsOwn() {
        // "see A B." shows as "see B A."
        assertArrayEquals(new int[] {0, 1, 4, 3, 2, 5}, TextOrder.order(new int[] {L, N, R, N, R, N}, false));
        assertArrayEquals(new int[] {0, 1, 2}, TextOrder.order(new int[] {L, N, L}, false));
    }

    @Test
    void piecesAreKindedByTheirStrongCharacters() {
        assertEquals(R, TextOrder.kind("שלום", 0, 4));
        assertEquals(L, TextOrder.kind("abc", 3, 0));
        assertEquals(R, TextOrder.kind("(אב) abc", 3, 2));
        assertEquals(D, TextOrder.kind("1948,", 0, 0));
        assertEquals(D, TextOrder.kind("١٩٤٨", 0, 0));
        assertEquals(N, TextOrder.kind(" - ", 0, 0));
    }

    @Test
    void onlyLinesWithRightToLeftTextAreReordered() {
        List<TextOrder.Piece> pieces = new ArrayList<>();
        pieces.add(piece("left", 10, 30, 100));
        pieces.add(piece("right", 32, 50, 100));
        pieces.add(piece("שלום", 10, 30, 120));
        pieces.add(piece(" ", 30, 33, 120));
        pieces.add(piece("עולם", 33, 50, 120));
        pieces.add(piece("next", 10, 30, 140));
        List<String> texts = new ArrayList<>();
        for (TextOrder.Piece p : TextOrder.reading(pieces)) {
            texts.add(p.text());
        }
        assertEquals(List.of("left", "right", "עולם", " ", "שלום", "next"), texts);
    }

    @Test
    void piecesOnAnotherBaselineOrFarApartAreAnotherLine() {
        assertTrue(TextOrder.sameLine(piece("a", 10, 20, 100), piece("b", 21, 30, 101)));
        assertFalse(TextOrder.sameLine(piece("a", 10, 20, 100), piece("b", 21, 30, 115)));
        assertFalse(TextOrder.sameLine(piece("a", 10, 20, 100), piece("b", 5, 9, 100)));
        assertFalse(TextOrder.sameLine(piece("a", 10, 20, 100), piece("b", 200, 210, 100)));
        TextOrder.Piece turned = new TextOrder.Piece("", 21, 30, 100, 12, "b", false);
        assertFalse(TextOrder.sameLine(piece("a", 10, 20, 100), turned), "rotated text keeps its drawn order");
    }

    @Test
    void theEndsOfALineTellItsDirectionElseTheMajorityDoes() {
        assertTrue(TextOrder.rightToLeft(new int[] {R, N, L, N, R}, 20, 5), "Hebrew at both ends around a URL");
        assertFalse(TextOrder.rightToLeft(new int[] {L, N, R, N, L}, 5, 20));
        assertTrue(TextOrder.rightToLeft(new int[] {L, N, R}, 3, 8));
        assertFalse(TextOrder.rightToLeft(new int[] {L, N, R}, 8, 3));
    }

    private static TextOrder.Piece piece(String text, float x0, float x1, float baseline) {
        return new TextOrder.Piece("", x0, x1, baseline, 12, text, true);
    }
}
