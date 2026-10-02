package stirling.software.officeconvert.topdf.vsdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PathsTest {

    private static Sheet.Row row(String type, double x, double y, Double a) {
        return new Sheet.Row(type, false, a == null ? Map.of("X", "" + x, "Y", "" + y)
                : Map.of("X", "" + x, "Y", "" + y, "A", "" + a));
    }

    @Test
    void aPositiveBowBulgesToTheRightOfTheChord() {
        Cells.Geometry g = new Cells.Geometry("0", Map.of(), List.of(row("MoveTo", 1.35, 0, null),
                row("ArcTo", 1.5, 0.15, 0.0439)));
        List<Paths.Path> paths = Paths.build(g, 1.5, 1, Paths.MAX_POINTS);
        assertEquals(1, paths.size());
        assertEquals(2, paths.get(0).segments.size());
        assertEquals('C', paths.get(0).segments.get(1).op());
        double[] p = paths.get(0).segments.get(1).pts();
        double mx = 0.125 * 1.35 + 0.375 * p[0] + 0.375 * p[2] + 0.125 * p[4];
        double my = 0.125 * 0 + 0.375 * p[1] + 0.375 * p[3] + 0.125 * p[5];
        assertEquals(1.425 + 0.0439 / Math.sqrt(2), mx, 1e-3);
        assertEquals(0.075 - 0.0439 / Math.sqrt(2), my, 1e-3);
    }
}
