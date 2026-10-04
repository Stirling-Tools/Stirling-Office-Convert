package stirling.software.officeconvert.jpx;

record Quant(int style, int guard, int[] exponent, int[] mantissa) {

    static final int NONE = 0;
    static final int DERIVED = 1;
    static final int EXPOUNDED = 2;

    static Quant read(Bytes b, int bytes) throws JpxException {
        int s = b.u8();
        int style = s & 0x1F;
        int guard = s >> 5;
        int left = bytes - 1;
        int count = switch (style) {
            case NONE -> left;
            case DERIVED -> 1;
            case EXPOUNDED -> left / 2;
            default -> throw new JpxException("Invalid JPEG 2000 quantization style");
        };
        if (count < 1 || count > 3 * 32 + 1) {
            throw new JpxException("Invalid JPEG 2000 quantization");
        }
        int[] exponent = new int[count];
        int[] mantissa = new int[count];
        for (int i = 0; i < count; i++) {
            if (style == NONE) {
                exponent[i] = b.u8() >> 3;
            } else {
                int v = b.u16();
                exponent[i] = v >> 11;
                mantissa[i] = v & 0x7FF;
            }
        }
        return new Quant(style, guard, exponent, mantissa);
    }

    int exponent(int levels, int resolution, int band) {
        if (style == DERIVED) {
            int nb = resolution == 0 ? levels : levels + 1 - resolution;
            return exponent[0] - levels + nb;
        }
        int i = resolution == 0 ? 0 : 1 + 3 * (resolution - 1) + band - 1;
        return exponent[Math.min(i, exponent.length - 1)];
    }

    int mantissa(int resolution, int band) {
        if (style == DERIVED) {
            return mantissa[0];
        }
        int i = resolution == 0 ? 0 : 1 + 3 * (resolution - 1) + band - 1;
        return mantissa[Math.min(i, mantissa.length - 1)];
    }
}
