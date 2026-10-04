package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;

final class IccProfiles {

    static final String SRGB_ID = "sRGB IEC61966-2.1";

    private static final double[][] PRIMARIES = {{0.4361, 0.2225, 0.0139}, {0.3851, 0.7169, 0.0971},
            {0.1431, 0.0606, 0.7141}};

    private static final double[] D50 = {0.9642, 1.0, 0.8249};

    private static volatile byte[] srgb;

    private static volatile byte[] gray;

    private IccProfiles() {}

    static byte[] srgb() {
        byte[] p = srgb;
        if (p == null) {
            p = build("RGB ", "sRGB IEC61966-2.1 (Stirling)", true);
            srgb = p;
        }
        return p.clone();
    }

    static byte[] gray() {
        byte[] p = gray;
        if (p == null) {
            p = build("GRAY", "Gray, sRGB tone curve (Stirling)", false);
            gray = p;
        }
        return p.clone();
    }

    static byte[] cmyk() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/icc/CGATS001Compat-v2-micro.icc")) {
            if (in == null) {
                throw new IOException("The CMYK profile bundled with PDFBox is missing");
            }
            return in.readAllBytes();
        }
    }

    record Header(int size, int major, String deviceClass, String colourSpace) {}

    static Header header(byte[] p) {
        if (p == null || p.length < 132) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(p);
        if (b.getInt(36) != 0x61637370) {
            return null;
        }
        return new Header(b.getInt(0), p[8] & 0xFF, new String(p, 12, 4, StandardCharsets.US_ASCII),
                new String(p, 16, 4, StandardCharsets.US_ASCII));
    }

    static int components(String colourSpace) {
        return switch (colourSpace) {
            case "GRAY" -> 1;
            case "RGB " , "Lab ", "XYZ " -> 3;
            case "CMYK" -> 4;
            default -> -1;
        };
    }

    private static byte[] build(String space, String description, boolean rgb) {
        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        String[] sigs = rgb ? new String[] {"desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"}
                : new String[] {"desc", "cprt", "wtpt", "kTRC"};
        byte[][] data = new byte[sigs.length][];
        data[0] = desc(description);
        data[1] = text("No copyright, use freely");
        data[2] = xyz(D50);
        byte[] curve = curve();
        if (rgb) {
            data[3] = xyz(PRIMARIES[0]);
            data[4] = xyz(PRIMARIES[1]);
            data[5] = xyz(PRIMARIES[2]);
            data[6] = curve;
            data[7] = curve;
            data[8] = curve;
        } else {
            data[3] = curve;
        }
        int tableSize = 4 + 12 * sigs.length;
        int offset = 128 + tableSize;
        ByteBuffer table = ByteBuffer.allocate(tableSize);
        table.putInt(sigs.length);
        int curveOffset = -1;
        for (int i = 0; i < sigs.length; i++) {
            boolean shared = data[i] == curve && curveOffset >= 0;
            int at = shared ? curveOffset : offset;
            if (data[i] == curve && curveOffset < 0) {
                curveOffset = offset;
            }
            table.put(sigs[i].getBytes(StandardCharsets.US_ASCII));
            table.putInt(at);
            table.putInt(data[i].length);
            if (!shared) {
                tags.writeBytes(data[i]);
                int pad = (4 - data[i].length % 4) % 4;
                tags.writeBytes(new byte[pad]);
                offset += data[i].length + pad;
            }
        }
        int size = 128 + tableSize + tags.size();
        ByteBuffer h = ByteBuffer.allocate(128);
        h.putInt(size);
        h.putInt(0);
        h.putInt(0x02100000);
        h.put("mntr".getBytes(StandardCharsets.US_ASCII));
        h.put(space.getBytes(StandardCharsets.US_ASCII));
        h.put("XYZ ".getBytes(StandardCharsets.US_ASCII));
        h.putShort((short) 2026);
        h.putShort((short) 1);
        h.putShort((short) 1);
        h.putShort((short) 0);
        h.putShort((short) 0);
        h.putShort((short) 0);
        h.put("acsp".getBytes(StandardCharsets.US_ASCII));
        h.putInt(0);
        h.putInt(0);
        h.putInt(0);
        h.putInt(0);
        h.putLong(0);
        h.putInt(0);
        h.putInt(s15(D50[0]));
        h.putInt(s15(D50[1]));
        h.putInt(s15(D50[2]));
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.writeBytes(h.array());
        out.writeBytes(table.array());
        out.writeBytes(tags.toByteArray());
        return out.toByteArray();
    }

    private static byte[] curve() {
        int n = 256;
        ByteBuffer b = ByteBuffer.allocate(12 + 2 * n);
        b.put("curv".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        b.putInt(n);
        for (int i = 0; i < n; i++) {
            double v = i / (double) (n - 1);
            double lin = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
            b.putShort((short) Math.round(lin * 65535));
        }
        return b.array();
    }

    private static byte[] xyz(double[] v) {
        ByteBuffer b = ByteBuffer.allocate(20);
        b.put("XYZ ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        for (double d : v) {
            b.putInt(s15(d));
        }
        return b.array();
    }

    private static byte[] text(String s) {
        byte[] t = s.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(8 + t.length + 1);
        b.put("text".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        b.put(t);
        b.put((byte) 0);
        return b.array();
    }

    private static byte[] desc(String s) {
        byte[] t = s.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(12 + t.length + 1 + 8 + 3 + 67);
        b.put("desc".getBytes(StandardCharsets.US_ASCII));
        b.putInt(0);
        b.putInt(t.length + 1);
        b.put(t);
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putShort((short) 0);
        b.put((byte) 0);
        return b.array();
    }

    private static int s15(double v) {
        return (int) Math.round(v * 65536);
    }
}
