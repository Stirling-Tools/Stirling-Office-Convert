package stirling.software.officeconvert.jpx;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class GreyProfile {

    private static final int[] D50 = {0xF6D6, 0x10000, 0xD32D};

    private static volatile ColorSpace space;

    private GreyProfile() {}

    static ColorSpace space() {
        ColorSpace cs = space;
        if (cs == null) {
            try {
                cs = new ICC_ColorSpace(ICC_Profile.getInstance(bytes()));
            } catch (RuntimeException e) {
                cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            }
            space = cs;
        }
        return cs;
    }

    static byte[] bytes() {
        byte[][] tags = {desc("sGrey JPEG 2000 greyscale"), xyz(D50), curve(), text("No copyright, use freely")};
        String[] names = {"desc", "wtpt", "kTRC", "cprt"};
        int offset = 128 + 4 + 12 * tags.length;
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        u32(table, tags.length);
        for (int i = 0; i < tags.length; i++) {
            table.writeBytes(names[i].getBytes(StandardCharsets.US_ASCII));
            u32(table, offset + data.size());
            u32(table, tags[i].length);
            data.writeBytes(tags[i]);
            while (data.size() % 4 != 0) {
                data.write(0);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = 128 + table.size() + data.size();
        u32(out, size);
        u32(out, 0);
        u32(out, 0x02100000);
        out.writeBytes("mntrGRAYXYZ ".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[12]);
        out.writeBytes("acsp".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[24]);
        u32(out, 0);
        for (int v : D50) {
            u32(out, v);
        }
        out.writeBytes(new byte[48]);
        out.writeBytes(table.toByteArray());
        out.writeBytes(data.toByteArray());
        return out.toByteArray();
    }

    private static byte[] curve() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("curv".getBytes(StandardCharsets.US_ASCII));
        u32(b, 0);
        int n = 1024;
        u32(b, n);
        for (int i = 0; i < n; i++) {
            double v = i / (double) (n - 1);
            double linear = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
            int q = (int) Math.round(linear * 65535);
            b.write(q >> 8);
            b.write(q);
        }
        return b.toByteArray();
    }

    private static byte[] xyz(int[] v) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("XYZ ".getBytes(StandardCharsets.US_ASCII));
        u32(b, 0);
        for (int x : v) {
            u32(b, x);
        }
        return b.toByteArray();
    }

    private static byte[] desc(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("desc".getBytes(StandardCharsets.US_ASCII));
        u32(b, 0);
        u32(b, s.length() + 1);
        b.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
        b.write(0);
        b.writeBytes(new byte[4 + 4 + 2 + 1 + 67]);
        return b.toByteArray();
    }

    private static byte[] text(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes("text".getBytes(StandardCharsets.US_ASCII));
        u32(b, 0);
        b.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
        b.write(0);
        return b.toByteArray();
    }

    private static void u32(ByteArrayOutputStream b, int v) {
        b.write(v >>> 24);
        b.write(v >>> 16);
        b.write(v >>> 8);
        b.write(v);
    }
}
