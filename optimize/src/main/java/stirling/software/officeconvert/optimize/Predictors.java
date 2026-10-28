package stirling.software.officeconvert.optimize;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class Predictors {

    static final int NONE = 0;

    static final int SUB = 1;

    static final int UP = 2;

    static final int AVERAGE = 3;

    static final int PAETH = 4;

    static final int ADAPTIVE = -1;

    record Layout(int colors, int bitsPerComponent, int columns) {

        int rowBytes() {
            return (int) ((long) colors * bitsPerComponent * columns + 7 >> 3);
        }

        int pixelBytes() {
            return Math.max(1, colors * bitsPerComponent + 7 >> 3);
        }

        static Layout of(COSDictionary parms) throws Filters.Undecodable {
            int colors = parms.getInt(COSName.COLORS, 1);
            int bpc = parms.getInt(COSName.BITS_PER_COMPONENT, 8);
            int columns = parms.getInt(COSName.COLUMNS, 1);
            if (colors < 1 || colors > 32 || columns < 1 || columns > 1 << 24
                    || bpc != 1 && bpc != 2 && bpc != 4 && bpc != 8 && bpc != 16) {
                throw new Filters.Undecodable("the predictor parameters are out of range");
            }
            return new Layout(colors, bpc, columns);
        }
    }

    private Predictors() {}

    static byte[] decode(byte[] data, COSDictionary parms, long limit) throws Filters.Undecodable {
        int predictor = parms == null ? 1 : parms.getInt(COSName.PREDICTOR, 1);
        if (predictor == 1) {
            return data;
        }
        Layout layout = Layout.of(parms);
        if (predictor == 2) {
            return tiff(data, layout);
        }
        if (predictor < 10 || predictor > 15) {
            throw new Filters.Undecodable("predictor " + predictor + " is not defined");
        }
        return png(data, layout);
    }

    private static byte[] png(byte[] data, Layout layout) throws Filters.Undecodable {
        int row = layout.rowBytes();
        int bpp = layout.pixelBytes();
        long stride = row + 1L;
        if (data.length % stride != 0) {
            throw new Filters.Undecodable("the PNG predicted data is not a whole number of rows");
        }
        int rows = (int) (data.length / stride);
        byte[] out = new byte[rows * row];
        for (int r = 0; r < rows; r++) {
            int src = (int) (r * stride);
            int tag = data[src] & 0xFF;
            int dst = r * row;
            int prev = dst - row;
            for (int i = 0; i < row; i++) {
                int x = data[src + 1 + i] & 0xFF;
                int a = i >= bpp ? out[dst + i - bpp] & 0xFF : 0;
                int b = r > 0 ? out[prev + i] & 0xFF : 0;
                int c = r > 0 && i >= bpp ? out[prev + i - bpp] & 0xFF : 0;
                int v = switch (tag) {
                    case NONE -> x;
                    case SUB -> x + a;
                    case UP -> x + b;
                    case AVERAGE -> x + (a + b >>> 1);
                    case PAETH -> x + paeth(a, b, c);
                    default -> throw new Filters.Undecodable("PNG row filter " + tag + " is not defined");
                };
                out[dst + i] = (byte) v;
            }
        }
        return out;
    }

    private static byte[] tiff(byte[] data, Layout layout) throws Filters.Undecodable {
        int row = layout.rowBytes();
        if (data.length % row != 0) {
            throw new Filters.Undecodable("the TIFF predicted data is not a whole number of rows");
        }
        byte[] out = data.clone();
        int colors = layout.colors();
        if (layout.bitsPerComponent() == 8) {
            for (int start = 0; start < out.length; start += row) {
                for (int i = colors; i < row; i++) {
                    out[start + i] = (byte) (out[start + i] + out[start + i - colors]);
                }
            }
            return out;
        }
        if (layout.bitsPerComponent() == 16) {
            for (int start = 0; start < out.length; start += row) {
                for (int i = 2 * colors; i + 1 < row; i += 2) {
                    int v = ((out[start + i] & 0xFF) << 8 | out[start + i + 1] & 0xFF)
                            + ((out[start + i - 2 * colors] & 0xFF) << 8 | out[start + i - 2 * colors + 1] & 0xFF);
                    out[start + i] = (byte) (v >>> 8);
                    out[start + i + 1] = (byte) v;
                }
            }
            return out;
        }
        throw new Filters.Undecodable("TIFF prediction below 8 bits is not decoded");
    }

    static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    static byte[] encodePng(byte[] data, Layout layout, int filter) {
        int row = layout.rowBytes();
        int bpp = layout.pixelBytes();
        int rows = data.length / row;
        byte[] out = new byte[rows * (row + 1)];
        for (int r = 0; r < rows; r++) {
            int src = r * row;
            int dst = r * (row + 1);
            int f = filter != ADAPTIVE ? filter : choose(data, src, row, bpp, r > 0);
            out[dst] = (byte) f;
            filterRow(data, src, row, bpp, r > 0, f, out, dst + 1);
        }
        return out;
    }

    private static int choose(byte[] d, int src, int row, int bpp, boolean hasPrevious) {
        long none = 0;
        long sub = 0;
        long up = 0;
        long avg = 0;
        long paeth = 0;
        int prev = src - row;
        for (int i = 0; i < row; i++) {
            int x = d[src + i] & 0xFF;
            int a = i >= bpp ? d[src + i - bpp] & 0xFF : 0;
            int b = hasPrevious ? d[prev + i] & 0xFF : 0;
            int c = hasPrevious && i >= bpp ? d[prev + i - bpp] & 0xFF : 0;
            none += Math.abs((byte) x);
            sub += Math.abs((byte) (x - a));
            up += Math.abs((byte) (x - b));
            avg += Math.abs((byte) (x - (a + b >>> 1)));
            paeth += Math.abs((byte) (x - paeth(a, b, c)));
        }
        int best = NONE;
        long cost = none;
        if (sub < cost) {
            cost = sub;
            best = SUB;
        }
        if (up < cost) {
            cost = up;
            best = UP;
        }
        if (avg < cost) {
            cost = avg;
            best = AVERAGE;
        }
        if (paeth < cost) {
            best = PAETH;
        }
        return best;
    }

    private static void filterRow(byte[] data, int src, int row, int bpp, boolean hasPrevious, int filter,
            byte[] out, int dst) {
        int prev = src - row;
        for (int i = 0; i < row; i++) {
            int x = data[src + i] & 0xFF;
            int a = i >= bpp ? data[src + i - bpp] & 0xFF : 0;
            int b = hasPrevious ? data[prev + i] & 0xFF : 0;
            int c = hasPrevious && i >= bpp ? data[prev + i - bpp] & 0xFF : 0;
            int v = switch (filter) {
                case SUB -> x - a;
                case UP -> x - b;
                case AVERAGE -> x - (a + b >>> 1);
                case PAETH -> x - paeth(a, b, c);
                default -> x;
            };
            out[dst + i] = (byte) v;
        }
    }
}
