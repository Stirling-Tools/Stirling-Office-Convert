package stirling.software.officeconvert.jpx;

import java.awt.Rectangle;

public record JpxOptions(int reduce, Rectangle region, long maxSamples) {

    public static final long DEFAULT_MAX_SAMPLES = 1L << 27;

    public JpxOptions {
        if (reduce < 0) {
            throw new IllegalArgumentException("reduce must not be negative: " + reduce);
        }
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("maxSamples must be positive: " + maxSamples);
        }
        if (region != null && (region.x < 0 || region.y < 0 || region.width <= 0 || region.height <= 0)) {
            throw new IllegalArgumentException("region must have a non-negative origin and a positive size: " + region);
        }
        region = region == null ? null : new Rectangle(region);
    }

    @Override
    public Rectangle region() {
        return region == null ? null : new Rectangle(region);
    }

    public static JpxOptions defaults() {
        return new JpxOptions(0, null, DEFAULT_MAX_SAMPLES);
    }

    public JpxOptions withReduce(int levels) {
        return new JpxOptions(Math.max(0, levels), region, maxSamples);
    }

    public JpxOptions withRegion(Rectangle area) {
        return new JpxOptions(reduce, area, maxSamples);
    }
}
