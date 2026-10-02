package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;

final class SyntheticCodestream {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    SyntheticCodestream(long width, long height, int[] dx, int[] dy) {
        u16(0xFF4F);
        u16(0xFF51);
        u16(38 + 3 * dx.length);
        u16(0);
        u32(width);
        u32(height);
        u32(0);
        u32(0);
        u32(width);
        u32(height);
        u32(0);
        u32(0);
        u16(dx.length);
        for (int c = 0; c < dx.length; c++) {
            u8(7);
            u8(dx[c]);
            u8(dy[c]);
        }
    }

    static SyntheticCodestream grey(long width, long height) {
        return new SyntheticCodestream(width, height, new int[] {1}, new int[] {1});
    }

    SyntheticCodestream coding(int order, int layers, int levels, boolean unitPrecincts) {
        int precincts = unitPrecincts ? levels + 1 : 0;
        u16(0xFF52);
        u16(12 + precincts);
        u8(unitPrecincts ? 1 : 0);
        u8(order);
        u16(layers);
        u8(0);
        u8(levels);
        u8(4);
        u8(4);
        u8(0);
        u8(1);
        for (int i = 0; i < precincts; i++) {
            u8(0);
        }
        int bands = 3 * levels + 1;
        u16(0xFF5C);
        u16(3 + bands);
        u8(2 << 5);
        for (int i = 0; i < bands; i++) {
            u8(9 << 3);
        }
        return this;
    }

    SyntheticCodestream progressionChanges(int count, int compStart, int layerEnd, int order) {
        u16(0xFF5F);
        u16(2 + 7 * count);
        for (int i = 0; i < count; i++) {
            u8(0);
            u8(compStart);
            u16(layerEnd);
            u8(33);
            u8(1);
            u8(order);
        }
        return this;
    }

    byte[] tile() {
        u16(0xFF90);
        u16(10);
        u16(0);
        u32(15);
        u8(0);
        u8(1);
        u16(0xFF93);
        u8(0);
        u16(0xFFD9);
        return out.toByteArray();
    }


    static byte[] samples(int width, int height, int components, int depth, int order,
            boolean reversible, boolean markers, boolean poc, boolean tiles, boolean subsampled) {
        int[] dx = new int[components];
        int[] dy = new int[components];
        java.util.Arrays.fill(dx, 1);
        java.util.Arrays.fill(dy, 1);
        if (subsampled) {
            for (int c = 1; c < components; c++) {
                dx[c] = 2;
                dy[c] = 2;
            }
        }
        SyntheticCodestream s = new SyntheticCodestream(width, height, dx, dy);
        byte[] header = s.out.toByteArray();
        for (int c = 0; c < components; c++) {
            header[42 + c * 3] = (byte) (depth - 1);
        }
        if (tiles) {
            java.util.Arrays.fill(header, 24, 32, (byte) 0);
            header[27] = 1;
            header[31] = 1;
        }
        s.out.reset();
        s.out.writeBytes(header);
        s.u16(0xFF52);
        s.u16(13);
        s.u8(1 | (markers ? 6 : 0));
        s.u8(order);
        s.u16(1);
        s.u8(0);
        s.u8(0);
        s.u8(0);
        s.u8(0);
        s.u8(0);
        s.u8(reversible ? 1 : 0);
        s.u8(0);
        s.u16(0xFF5C);
        s.u16(reversible ? 4 : 5);
        s.u8((2 << 5) | (reversible ? 0 : 2));
        if (reversible) {
            s.u8(depth << 3);
        } else {
            s.u16(depth << 11);
        }
        if (poc) {
            s.u16(0xFF5F);
            s.u16(9);
            s.u8(0);
            s.u8(0);
            s.u16(1);
            s.u8(1);
            s.u8(components);
            s.u8(order);
        }
        int count = tiles ? width * height : 1;
        for (int t = 0; t < count; t++) {
            SyntheticCodestream body = grey(1, 1);
            body.out.reset();
            if (tiles) {
                for (int c = 0; c < components; c++) {
                    body.samplePacket(t % width, t / width, c, depth, markers);
                }
            } else if (order == 2 || order == 3) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        for (int c = 0; c < components; c++) {
                            if (x % dx[c] == 0 && y % dy[c] == 0) {
                                body.samplePacket(x, y, c, depth, markers);
                            }
                        }
                    }
                }
            } else {
                for (int c = 0; c < components; c++) {
                    for (int y = 0; y < height; y += dy[c]) {
                        for (int x = 0; x < width; x += dx[c]) {
                            body.samplePacket(x, y, c, depth, markers);
                        }
                    }
                }
            }
            s.u16(0xFF90);
            s.u16(10);
            s.u16(t);
            s.u32(14 + body.out.size());
            s.u8(0);
            s.u8(1);
            s.u16(0xFF93);
            s.out.writeBytes(body.out.toByteArray());
        }
        s.u16(0xFFD9);
        return s.out.toByteArray();
    }

    static byte[] wavelet(boolean reversible) {
        SyntheticCodestream s = grey(4, 4).coding(0, 1, 2, false);
        byte[] header = s.out.toByteArray();
        if (!reversible) {
            header[58] = 0;
        }
        s.out.reset();
        s.out.writeBytes(header);
        SyntheticCodestream body = grey(1, 1);
        body.out.reset();
        body.samplePacket(0, 0, 0, 9, false);
        body.u8(0);
        body.u8(0);
        s.u16(0xFF90);
        s.u16(10);
        s.u16(0);
        s.u32(14 + body.out.size());
        s.u8(0);
        s.u8(1);
        s.u16(0xFF93);
        s.out.writeBytes(body.out.toByteArray());
        s.u16(0xFFD9);
        return s.out.toByteArray();
    }

    static byte[] rgba() {
        byte[] stream = samples(7, 5, 4, 8, 0, true, true, false, false, false);
        SyntheticCodestream s = grey(1, 1);
        s.out.reset();
        s.u32(12);
        s.u32(0x6A502020);
        s.u32(0x0D0A870A);
        s.u32(39);
        s.u32(0x6A703268);
        s.u32(15);
        s.u32(0x636F6C72);
        s.u8(1);
        s.u8(0);
        s.u8(0);
        s.u32(16);
        s.u32(16);
        s.u32(0x63646566);
        s.u16(1);
        s.u16(3);
        s.u16(1);
        s.u16(0);
        s.u32(8 + stream.length);
        s.u32(0x6A703263);
        s.out.writeBytes(stream);
        return s.out.toByteArray();
    }

    private void samplePacket(int x, int y, int c, int depth, boolean markers) {
        if (markers) {
            u16(0xFF91);
            u16(4);
            u16(0);
        }
        String bits = "11" + "0".repeat(depth) + "100010";
        for (int i = 0; i < bits.length(); i += 8) {
            String octet = bits.substring(i, Math.min(i + 8, bits.length()));
            u8(Integer.parseInt(octet, 2) << (8 - octet.length()));
        }
        if (markers) {
            u16(0xFF92);
        }
        u8(((x + 3 * y + c) & 1) == 0 ? 0 : 8);
        u8(0);
    }

    private void u8(int v) {
        out.write(v);
    }

    private void u16(int v) {
        u8(v >> 8);
        u8(v);
    }

    private void u32(long v) {
        u16((int) (v >>> 16));
        u16((int) v);
    }
}
