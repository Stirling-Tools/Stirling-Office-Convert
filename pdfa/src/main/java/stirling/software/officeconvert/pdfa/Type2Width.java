package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.fonts.CffCodec;

final class Type2Width {

    private Type2Width() {}

    static Double operand(byte[] cs) {
        if (cs == null) {
            return null;
        }
        List<Double> stack = new ArrayList<>();
        int i = 0;
        while (i < cs.length) {
            int b = cs[i] & 0xFF;
            if (b == 28 && i + 2 < cs.length) {
                stack.add((double) (short) ((cs[i + 1] & 0xFF) << 8 | cs[i + 2] & 0xFF));
                i += 3;
            } else if (b >= 32 && b <= 246) {
                stack.add((double) (b - 139));
                i++;
            } else if (b >= 247 && b <= 254 && i + 1 < cs.length) {
                stack.add((double) CffCodec.twoByteInt(b, cs[i + 1] & 0xFF));
                i += 2;
            } else if (b == 255 && i + 4 < cs.length) {
                int v = (cs[i + 1] & 0xFF) << 24 | (cs[i + 2] & 0xFF) << 16 | (cs[i + 3] & 0xFF) << 8 | cs[i + 4] & 0xFF;
                stack.add(v / 65_536.0);
                i += 5;
            } else {
                return width(b, stack);
            }
        }
        return null;
    }

    private static Double width(int op, List<Double> stack) {
        int n = stack.size();
        boolean present = switch (op) {
            case 1, 3, 18, 23, 19, 20 -> n % 2 == 1;
            case 21 -> n == 3;
            case 4, 22 -> n == 2;
            case 14 -> n == 1 || n == 5;
            default -> false;
        };
        boolean known = switch (op) {
            case 1, 3, 18, 23, 19, 20, 21, 4, 22, 14 -> true;
            default -> false;
        };
        if (!known) {
            return null;
        }
        return present ? stack.get(0) : Double.NaN;
    }
}
