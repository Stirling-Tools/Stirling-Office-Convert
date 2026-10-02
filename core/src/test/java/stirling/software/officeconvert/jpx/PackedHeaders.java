package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PackedHeaders {

    private record Part(byte[] sot, byte[] markers, byte[] headers, byte[] body) {
    }

    private PackedHeaders() {}

    static byte[] toPpt(byte[] j2k) {
        List<Part> parts = new ArrayList<>();
        int first = split(j2k, parts);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j2k, 0, first);
        for (Part p : parts) {
            ByteArrayOutputStream markers = new ByteArrayOutputStream();
            markers.writeBytes(p.markers());
            segments(markers, 0xFF61, p.headers(), 1000);
            write(out, p, markers.toByteArray());
        }
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    static byte[] toPpm(byte[] j2k) {
        List<Part> parts = new ArrayList<>();
        int first = split(j2k, parts);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (Part p : parts) {
            int n = p.headers().length;
            all.writeBytes(new byte[] {(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n});
            all.writeBytes(p.headers());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j2k, 0, first);
        segments(out, 0xFF60, all.toByteArray(), 333);
        for (Part p : parts) {
            write(out, p, p.markers());
        }
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Part p, byte[] markers) {
        int psot = p.sot().length + markers.length + 2 + p.body().length;
        byte[] sot = p.sot().clone();
        sot[6] = (byte) (psot >> 24);
        sot[7] = (byte) (psot >> 16);
        sot[8] = (byte) (psot >> 8);
        sot[9] = (byte) psot;
        out.writeBytes(sot);
        out.writeBytes(markers);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0x93});
        out.writeBytes(p.body());
    }

    private static void segments(ByteArrayOutputStream out, int marker, byte[] data, int chunk) {
        int z = 0;
        for (int at = 0; at < data.length; at += chunk) {
            int n = Math.min(chunk, data.length - at);
            int len = n + 3;
            out.writeBytes(new byte[] {(byte) (marker >> 8), (byte) marker, (byte) (len >> 8), (byte) len, (byte) z++});
            out.write(data, at, n);
        }
    }

    private static int split(byte[] j2k, List<Part> parts) {
        int first = 2;
        while (u16(j2k, first) != 0xFF90) {
            first += 2 + u16(j2k, first + 2);
        }
        int at = first;
        while (at + 12 <= j2k.length && u16(j2k, at) == 0xFF90) {
            int psot = (int) Bytes.u32(j2k, at + 6);
            int end = at + psot;
            int m = at + 12;
            while (u16(j2k, m) != 0xFF93) {
                m += 2 + u16(j2k, m + 2);
            }
            byte[] sot = Arrays.copyOfRange(j2k, at, at + 12);
            byte[] markers = Arrays.copyOfRange(j2k, at + 12, m);
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int p = m + 2;
            while (p < end) {
                if (u16(j2k, p) != 0xFF91) {
                    throw new IllegalStateException("Expected SOP at " + p);
                }
                body.write(j2k, p, 6);
                p += 6;
                int h = p;
                while (u16(j2k, h) != 0xFF92) {
                    h++;
                }
                headers.write(j2k, p, h + 2 - p);
                p = h + 2;
                int b = p;
                while (b < end && u16(j2k, b) != 0xFF91) {
                    b++;
                }
                body.write(j2k, p, b - p);
                p = b;
            }
            parts.add(new Part(sot, markers, headers.toByteArray(), body.toByteArray()));
            at = end;
        }
        return first;
    }

    private static int u16(byte[] b, int at) {
        return at + 1 < b.length ? (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF : -1;
    }
}
