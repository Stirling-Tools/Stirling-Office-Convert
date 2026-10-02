package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

class JpxOptionsTest {

    @Test
    void impossibleOptionsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JpxOptions(-1, null, 100));
        assertThrows(IllegalArgumentException.class, () -> new JpxOptions(0, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new JpxOptions(0, null, -5));
        assertThrows(IllegalArgumentException.class, () -> new JpxOptions(0, new Rectangle(-1, 0, 5, 5), 100));
        assertThrows(IllegalArgumentException.class, () -> new JpxOptions(0, new Rectangle(0, 0, 0, 5), 100));
        assertThrows(IllegalArgumentException.class, () -> JpxOptions.defaults().withRegion(new Rectangle(0, 0, 5, -2)));
    }

    @Test
    void theRegionCannotBeChangedFromOutside() {
        Rectangle area = new Rectangle(1, 2, 3, 4);
        JpxOptions options = JpxOptions.defaults().withRegion(area);
        area.setBounds(9, 9, 9, 9);
        options.region().setBounds(8, 8, 8, 8);
        assertEquals(new Rectangle(1, 2, 3, 4), options.region());
    }
}
