package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.fonts.CffCodec;

final class Type1Width {

    private Type1Width() {}

    static Double of(byte[] cs) {
        if (cs == null) {
            return null;
        }
        List<Double> stack = new ArrayList<>();
        int i = 0;
        while (i < cs.length) {
            int b = cs[i] & 0xFF;
            if (b >= 32 && b <= 246) {
                stack.add((double) (b - 139));
                i++;
            } else if (b >= 247 && b <= 254 && i + 1 < cs.length) {
                stack.add((double) CffCodec.twoByteInt(b, cs[i + 1] & 0xFF));
                i += 2;
            } else if (b == 255 && i + 4 < cs.length) {
                stack.add((double) ((cs[i + 1] & 0xFF) << 24 | (cs[i + 2] & 0xFF) << 16 | (cs[i + 3] & 0xFF) << 8
                        | cs[i + 4] & 0xFF));
                i += 5;
            } else if (b == 12 && i + 1 < cs.length && (cs[i + 1] & 0xFF) == 12 && stack.size() >= 2) {
                double d = stack.remove(stack.size() - 1);
                double n = stack.remove(stack.size() - 1);
                if (d == 0) {
                    return null;
                }
                stack.add(n / d);
                i += 2;
            } else if (b == 13) {
                return stack.size() == 2 ? stack.get(1) : null;
            } else if (b == 12 && i + 1 < cs.length && (cs[i + 1] & 0xFF) == 7) {
                return stack.size() == 4 ? stack.get(2) : null;
            } else {
                return null;
            }
        }
        return null;
    }
}
