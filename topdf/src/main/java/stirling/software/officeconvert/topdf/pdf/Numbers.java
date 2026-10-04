package stirling.software.officeconvert.topdf.pdf;

final class Numbers {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Numbers() {}

    static StringBuilder append(StringBuilder b, float value) {
        if (!Float.isFinite(value)) {
            return b.append('0');
        }
        long scaled = Math.round((double) value * 10_000);
        if (scaled < 0) {
            b.append('-');
            scaled = -scaled;
        }
        b.append(scaled / 10_000);
        long fraction = scaled % 10_000;
        if (fraction != 0) {
            b.append('.');
            for (long div = 1_000; div > 0 && fraction != 0; div /= 10) {
                b.append((char) ('0' + fraction / div));
                fraction %= div;
            }
        }
        return b;
    }

    static StringBuilder hex(StringBuilder b, int value, int digits) {
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            b.append(HEX[(value >> shift) & 0xF]);
        }
        return b;
    }
}
