package stirling.software.officeconvert.topdf.pptx;

import java.awt.image.BufferedImage;
import java.util.Arrays;

final class HiddenPixels {

    private HiddenPixels() {}

    static BufferedImage tinted(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        int[] left = new int[w];
        int carry = 0;
        boolean carried = false;
        for (int y = 0; y < h; y++) {
            PixelRows.read(src, 0, y, w, row);
            int last = -1;
            for (int x = 0; x < w; x++) {
                if (row[x] >>> 24 != 0) {
                    last = x;
                }
                left[x] = last;
            }
            if (last >= 0) {
                carry = row[last] & 0xFFFFFF;
                carried = true;
                int next = -1;
                for (int x = w - 1; x >= 0; x--) {
                    if (row[x] >>> 24 != 0) {
                        next = x;
                    } else {
                        int l = left[x];
                        int from = l < 0 || next >= 0 && next - x < x - l ? next : l;
                        row[x] = row[from] & 0xFFFFFF;
                    }
                }
            } else if (carried) {
                Arrays.fill(row, carry);
            }
            PixelRows.write(out, y, w, row);
        }
        return out;
    }
}
