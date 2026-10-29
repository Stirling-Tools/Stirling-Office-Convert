package stirling.software.officeconvert.topdf.io;

import java.awt.geom.Rectangle2D;

import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.draw.geom.IAdjustableShape;

final class LiteralContext extends Context {

    private static final int MAX_DIGITS = 15;

    LiteralContext(CustomGeometry geom, Rectangle2D anchor, IAdjustableShape props) {
        super(geom, anchor, props);
    }

    @Override
    public double getValue(String key) {
        int n = key == null ? 0 : key.length();
        int start = n > 0 && key.charAt(0) == '-' ? 1 : 0;
        if (n == start || n - start > MAX_DIGITS) {
            return super.getValue(key);
        }
        long v = 0;
        for (int i = start; i < n; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return super.getValue(key);
            }
            v = v * 10 + (c - '0');
        }
        return start == 1 ? -(double) v : v;
    }
}
