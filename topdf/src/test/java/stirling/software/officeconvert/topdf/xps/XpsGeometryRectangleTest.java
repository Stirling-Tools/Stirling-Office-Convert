package stirling.software.officeconvert.topdf.xps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.PathIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class XpsGeometryRectangleTest {

    private static final String[] NUMBERS = {"0", "1", "10", "-5", "+3", ".5", "2.25", "1e1", "1E-1", "3.", "007",
        "1e", "100.125", "-0", "12345678901234567890"};

    @Test
    void handPickedDataAgreesWithTheGeneralParser() throws IOException {
        String[] samples = {"M 409,975 V 976 H 410 V 975 Z", "M 1,1 L 5,1 5,4 1,4 Z", "M 1,1 L 5,1 5,4 1,4 1,1 Z",
            "F 1 M 0,0 H 10 V 10 H 0 Z", "F0 M0,0H10V10H0Z", "M 1-2 L 5-2 5,4 1,4 Z", "M .5,.5 H 1.5 V 2.5 H .5 Z",
            "M 1,1 V 4 H 5 V 1 Z", "M 1,1 1,1 L 5,1 5,4 1,4 Z", "M 1,1 H 5 H 5 V 4 H 1 Z", "M 1e1,1 H 2e1 V 5 H 1e1 Z",
            "M 1,1 L 5,1 5,4 1,4", "M 1,1 L 5,1 5,4 2,4 Z", "m 1,1 h 4 v 3 h -4 z", "M 1,1 H 5 V 4 H 1 Z M 0,0",
            "M 1,1 H 1 V 4 H 1 Z", "L 1,1 H 5 V 4 H 1 Z", "M 1,1 H 5,6 V 4 H 1 Z", "M 1,1, H 5, V 4, H 1, Z",
            "M 1,1 H 5 V 4 H 1 Z Z", "M 3,3 H 1 V 1 H 3 Z"};
        int accepted = 0;
        for (String s : samples) {
            if (check(s)) {
                accepted++;
            }
        }
        assertTrue(accepted >= 12, "accepted " + accepted);
    }

    @Test
    void randomDataAgreesWithTheGeneralParser() throws IOException {
        Random r = new Random(7);
        String[] commands = {"M", "L", "H", "V", "Z", "F 1", "C", "m", "h"};
        String[] gaps = {" ", ",", "", "  ", ", "};
        int accepted = 0;
        for (int round = 0; round < 20_000; round++) {
            String s;
            if (r.nextInt(3) == 0) {
                StringBuilder b = new StringBuilder();
                int n = 1 + r.nextInt(12);
                for (int i = 0; i < n; i++) {
                    b.append(r.nextInt(4) == 0 ? commands[r.nextInt(commands.length)]
                            : NUMBERS[r.nextInt(NUMBERS.length)]);
                    b.append(gaps[r.nextInt(gaps.length)]);
                }
                s = b.toString();
            } else {
                String x0 = NUMBERS[r.nextInt(NUMBERS.length)];
                String y0 = NUMBERS[r.nextInt(NUMBERS.length)];
                String x1 = NUMBERS[r.nextInt(NUMBERS.length)];
                String y1 = NUMBERS[r.nextInt(NUMBERS.length)];
                String g = gaps[r.nextInt(gaps.length)];
                s = switch (r.nextInt(3)) {
                    case 0 -> "M " + x0 + "," + y0 + g + "H " + x1 + g + "V " + y1 + g + "H " + x0 + g + "Z";
                    case 1 -> "M " + x0 + "," + y0 + " L " + x1 + "," + y0 + g + x1 + "," + y1 + g + x0 + "," + y1
                            + (r.nextBoolean() ? g + x0 + "," + y0 : "") + " Z";
                    default -> "M " + x0 + "," + y0 + " V " + y1 + g + "H " + x1 + g + "V " + y0 + g + "Z";
                };
            }
            if (check(s)) {
                accepted++;
            }
        }
        assertTrue(accepted > 1000, "accepted " + accepted);
    }

    private static boolean check(String data) throws IOException {
        double[] out = new double[16];
        int segments = XpsGeometry.rectangle(data, out);
        if (segments < 0) {
            return false;
        }
        int[] spent = {0};
        XpsGeometry g = XpsGeometry.abbreviated(data, n -> spent[0] += n);
        assertEquals(segments, spent[0], data);
        List<double[]> points = new ArrayList<>();
        double[] c = new double[6];
        int moves = 0;
        int closes = 0;
        for (PathIterator it = g.fill.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                moves++;
                points.add(new double[] {c[0], c[1]});
            } else if (type == PathIterator.SEG_LINETO) {
                points.add(new double[] {c[0], c[1]});
            } else if (type == PathIterator.SEG_CLOSE) {
                closes++;
            } else {
                throw new AssertionError("curve in " + data);
            }
        }
        assertEquals(1, moves, data);
        assertEquals(1, closes, data);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : points) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            assertTrue((p[0] == out[12] || p[0] == out[14]) && (p[1] == out[13] || p[1] == out[15]), data);
        }
        assertEquals(out[12], minX, 0, data);
        assertEquals(out[13], minY, 0, data);
        assertEquals(out[14], maxX, 0, data);
        assertEquals(out[15], maxY, 0, data);
        return true;
    }
}
