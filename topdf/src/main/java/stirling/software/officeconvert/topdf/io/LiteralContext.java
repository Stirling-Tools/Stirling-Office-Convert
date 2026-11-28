package stirling.software.officeconvert.topdf.io;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Map;

import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.draw.geom.IAdjustableShape;

final class LiteralContext extends Context {

    private static final int MAX_DIGITS = 15;

    private Map<String, Double> known;

    LiteralContext(CustomGeometry geom, Rectangle2D anchor, IAdjustableShape props) {
        super(geom, anchor, props);
        known = new HashMap<>();
    }

    @Override
    public double getValue(String key) {
        int n = key == null ? 0 : key.length();
        int start = n > 0 && key.charAt(0) == '-' ? 1 : 0;
        if (n == start || n - start > MAX_DIGITS) {
            return named(key);
        }
        long v = 0;
        for (int i = start; i < n; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return named(key);
            }
            v = v * 10 + (c - '0');
        }
        return start == 1 ? -(double) v : v;
    }

    private double named(String key) {
        if (known == null || key == null) {
            return super.getValue(key);
        }
        Double v = known.get(key);
        if (v != null) {
            return v;
        }
        double value = super.getValue(key);
        known.put(key, value);
        return value;
    }
}
