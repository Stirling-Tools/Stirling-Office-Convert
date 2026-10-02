package stirling.software.officeconvert.jpx;

import java.awt.Rectangle;

public record JpxOptions(int reduce, Rectangle region, long maxSamples) {

    public static final long DEFAULT_MAX_SAMPLES = 1L << 27;

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
