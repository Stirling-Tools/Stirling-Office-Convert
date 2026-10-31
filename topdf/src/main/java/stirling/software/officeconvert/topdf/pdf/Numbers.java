package stirling.software.officeconvert.topdf.pdf;

final class Numbers {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Numbers() {}

    static StringBuilder append(StringBuilder b, float value) {
        return append(b, value, 10_000);
    }

    static StringBuilder fine(StringBuilder b, double value) {
        return append(b, value, 100_000_000);
    }

    private static StringBuilder append(StringBuilder b, double value, long scale) {
        if (!Double.isFinite(value)) {
            return b.append('0');
        }
        long scaled = Math.round(value * scale);
        if (scaled < 0) {
            b.append('-');
            scaled = -scaled;
        }
        b.append(scaled / scale);
        long fraction = scaled % scale;
        if (fraction != 0) {
            b.append('.');
            for (long div = scale / 10; div > 0 && fraction != 0; div /= 10) {
                b.append((char) ('0' + fraction / div));
                fraction %= div;
            }
        }
        return b;
    }

    static double written(float value) {
        return Float.isFinite(value) ? Math.round((double) value * 10_000) / 10_000.0 : 0;
    }

    static StringBuilder hex(StringBuilder b, int value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            b.append(HEX[(value >> shift) & 0xF]);
        }
        return b;
    }
}
