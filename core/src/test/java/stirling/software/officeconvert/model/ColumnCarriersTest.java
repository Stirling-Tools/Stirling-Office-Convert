package stirling.software.officeconvert.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnCarriersTest {

    @Test
    void aBreakOnlyParagraphCarriesItsBreakUnlessItHoldsAnAnchor() {
        Paragraph plain = new Paragraph();
        plain.inlines.add(new Inline.ColumnBreak());
        assertTrue(ColumnCarriers.carrier(plain));
        Paragraph anchored = new Paragraph();
        anchored.inlines.add(new Inline.ColumnBreak());
        anchored.anchors.add("page3");
        assertFalse(ColumnCarriers.carrier(anchored));
    }
}
