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
