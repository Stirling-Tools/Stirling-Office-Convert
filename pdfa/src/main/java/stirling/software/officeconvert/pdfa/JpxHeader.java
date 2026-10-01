package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

record JpxHeader(int channels, int bitDepth, boolean perChannelDepths, boolean palette, List<int[]> colours) {

    private static final int JP2_SIGNATURE = 0x6A502020;

    static JpxHeader parse(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(data);
        if ((b.getShort(0) & 0xFFFF) == 0xFF4F) {
            return codestream(b);
        }
        if (b.getInt(4) != JP2_SIGNATURE) {
            return null;
        }
        int[] ihdr = null;
        boolean bpcc = false;
        boolean pclr = false;
        List<int[]> colr = new ArrayList<>();
        int at = 0;
        while (at + 8 <= data.length) {
            long len = b.getInt(at) & 0xFFFFFFFFL;
            int type = b.getInt(at + 4);
            int body = at + 8;
            if (len == 1 && at + 16 <= data.length) {
                len = b.getLong(at + 8);
                body = at + 16;
            } else if (len == 0) {
                len = data.length - at;
            }
            if (len < body - at || at + len > data.length) {
                break;
            }
            int end = (int) (at + len);
            if (type == box("jp2h")) {
                int in = body;
                while (in + 8 <= end) {
                    int l = b.getInt(in);
                    int t = b.getInt(in + 4);
                    if (l < 8 || in + l > end) {
                        break;
                    }
                    if (t == box("ihdr") && l >= 22) {
                        ihdr = new int[] {b.getShort(in + 16) & 0xFFFF, data[in + 18] & 0xFF};
                    } else if (t == box("bpcc")) {
                        bpcc = true;
                    } else if (t == box("pclr")) {
                        pclr = true;
                    } else if (t == box("colr") && l >= 11) {
                        int meth = data[in + 8] & 0xFF;
                        int approx = data[in + 10] & 0xFF;
                        int enumCs = meth == 1 && l >= 15 ? b.getInt(in + 11) : -1;
                        colr.add(new int[] {meth, approx, enumCs});
                    }
                    in += l;
                }
            } else if (type == box("jp2c")) {
                break;
            }
            at = end;
        }
        if (ihdr == null) {
            return null;
        }
        int bpc = ihdr[1];
        return new JpxHeader(ihdr[0], bpc == 255 ? -1 : bpc + 1, bpc == 255 || bpcc, pclr, colr);
    }

    private static JpxHeader codestream(ByteBuffer b) {
        if (b.capacity() < 42 || (b.getShort(2) & 0xFFFF) != 0xFF51) {
            return null;
        }
        int components = b.getShort(40) & 0xFFFF;
        if (b.capacity() < 42 + 3 * components) {
            return null;
        }
        int depth = (b.get(42) & 0xFF) + 1;
        boolean mixed = false;
        for (int i = 1; i < components; i++) {
            mixed |= (b.get(42 + 3 * i) & 0xFF) + 1 != depth;
        }
        return new JpxHeader(components, mixed ? -1 : depth, mixed, false, List.of());
    }

    boolean allowedInPdfA(boolean dictionaryHasColourSpace) {
        if (channels != 1 && channels != 3 && channels != 4 || perChannelDepths || bitDepth < 1 || bitDepth > 38) {
            return false;
        }
        if (dictionaryHasColourSpace) {
            return true;
        }
        if (colours.isEmpty() || palette) {
            return false;
        }
        if (colours.size() > 1 && colours.stream().filter(c -> c[1] == 1).count() != 1) {
            return false;
        }
        int[] first = colours.get(0);
        return first[0] >= 1 && first[0] <= 3 && first[2] != 19;
    }

    private static int box(String tag) {
        return tag.charAt(0) << 24 | tag.charAt(1) << 16 | tag.charAt(2) << 8 | tag.charAt(3);
    }
}
