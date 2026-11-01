package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.model.Inline;

class WrappedBoxSpacingTest {

    private static final PageLayout.TextBoxItem BOX = new PageLayout.TextBoxItem(new Box(316, 386, 559, 506), 0xFFFF84,
            318, 557, List.of(), 0, -1, 0, false, false);

    private static PageLayout.Column column(PageLayout.Item... items) {
        return new PageLayout.Column(316, 556, List.of(items));
    }

    @Test
    void aSquareWrappedBoxAcrossTheColumnHoldsTheTextBelowIt() {
        assertTrue(DocumentBuilder.blocksColumn(BOX, column(BOX)));
    }

    @Test
    void aNarrowBoxLeavesRoomBesideIt() {
        PageLayout.TextBoxItem narrow = new PageLayout.TextBoxItem(new Box(316, 386, 400, 506), -1, 318, 398,
                List.of(), 0, -1, 0, false, false);
        assertFalse(DocumentBuilder.blocksColumn(narrow, column(narrow)));
    }

    @Test
    void anOverlaidBoxDoesNotHoldTheText() {
        PageLayout.TextBoxItem overlay = new PageLayout.TextBoxItem(new Box(316, 386, 559, 506), -1, 318, 557,
                List.of(), 0, -1, 0, false, true);
        assertFalse(DocumentBuilder.blocksColumn(overlay, column(overlay)));
    }

    @Test
    void theFillBehindAWrappedBoxNeedsNoRoomOfItsOwn() {
        List<Inline.Shape> fill = List.of(new Inline.Shape(316.2f, 386.6f, 242.6f, 119.3f, 0xFFFF84));
        assertTrue(DocumentBuilder.insideWrap(fill, column(BOX)));
        List<Inline.Shape> apart = List.of(new Inline.Shape(316, 600, 240, 40, 0xFFFF84));
        assertFalse(DocumentBuilder.insideWrap(apart, column(BOX)));
    }
}
