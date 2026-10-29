package stirling.software.officeconvert;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

final class CoverPicture {

    static final int COLOURS = 64;

    static final int MAX_BUCKETS = 4096;

    static final double MAX_ERROR = 6.0;

    static final double MAX_TILE_ERROR = 12.0;

    private static final int TILE = 16;

    static final double ZIPPED_JPEG = 0.85;

    private static final int BUCKETS = 1 << 15;

    private CoverPicture() {}

    static byte[] encode(BufferedImage image) throws IOException {
        byte[] png = palettePng(image);
        byte[] jpeg = PdfBook.jpeg(image);
        return png != null && (jpeg == null || png.length <= jpeg.length * ZIPPED_JPEG) ? png : jpeg;
    }

    static boolean isPng(byte[] picture) {
        return picture != null && picture.length > 8 && (picture[0] & 0xff) == 0x89 && picture[1] == 'P'
                && picture[2] == 'N' && picture[3] == 'G';
    }

    static byte[] palettePng(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        int[] count = new int[BUCKETS];
        long[] sums = new long[BUCKETS * 3];
        int buckets = 0;
        for (int p : rgb) {
            int k = bucket(p);
            if (count[k]++ == 0 && ++buckets > MAX_BUCKETS) {
                return null;
            }
            sums[k * 3] += p >> 16 & 0xff;
            sums[k * 3 + 1] += p >> 8 & 0xff;
            sums[k * 3 + 2] += p & 0xff;
        }
        Integer[] used = new Integer[buckets];
        int n = 0;
        for (int k = 0; k < BUCKETS; k++) {
            if (count[k] > 0) {
                used[n++] = k;
            }
        }
        Arrays.sort(used, (a, b) -> Integer.compare(count[b], count[a]));
        int colours = Math.min(COLOURS, buckets);
        byte[] r = new byte[colours];
        byte[] g = new byte[colours];
        byte[] b = new byte[colours];
        for (int i = 0; i < colours; i++) {
            int k = used[i];
            r[i] = (byte) Math.round((double) sums[k * 3] / count[k]);
            g[i] = (byte) Math.round((double) sums[k * 3 + 1] / count[k]);
            b[i] = (byte) Math.round((double) sums[k * 3 + 2] / count[k]);
        }
        byte[] nearest = new byte[BUCKETS];
        for (int i = 0; i < buckets; i++) {
            int k = used[i];
            nearest[k] = (byte) closest(r, g, b, (int) (sums[k * 3] / count[k]), (int) (sums[k * 3 + 1] / count[k]),
                    (int) (sums[k * 3 + 2] / count[k]));
        }
        byte[] indices = new byte[rgb.length];
        int across = (w + TILE - 1) / TILE;
        double[] tiles = new double[across * ((h + TILE - 1) / TILE)];
        double error = 0;
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            int c = nearest[bucket(p)] & 0xff;
            indices[i] = (byte) c;
            int dr = (p >> 16 & 0xff) - (r[c] & 0xff);
            int dg = (p >> 8 & 0xff) - (g[c] & 0xff);
            int db = (p & 0xff) - (b[c] & 0xff);
            int d = dr * dr + dg * dg + db * db;
            error += d;
            tiles[i / w / TILE * across + i % w / TILE] += d;
        }
        if (Math.sqrt(error / (rgb.length * 3.0)) > MAX_ERROR) {
            return null;
        }
        double tileLimit = MAX_TILE_ERROR * MAX_TILE_ERROR * TILE * TILE * 3;
        for (double t : tiles) {
            if (t > tileLimit) {
                return null;
            }
        }
        int bits = colours <= 2 ? 1 : colours <= 4 ? 2 : colours <= 16 ? 4 : 8;
        return png(w, h, bits, r, g, b, indices);
    }

    private static byte[] png(int w, int h, int bits, byte[] r, byte[] g, byte[] b, byte[] indices)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(w);
        ihdr.writeInt(h);
        ihdr.writeByte(bits);
        ihdr.writeByte(3);
        ihdr.writeByte(0);
        ihdr.writeByte(0);
        ihdr.writeByte(0);
        chunk(out, "IHDR", header.toByteArray());
        byte[] palette = new byte[r.length * 3];
        for (int i = 0; i < r.length; i++) {
            palette[i * 3] = r[i];
            palette[i * 3 + 1] = g[i];
            palette[i * 3 + 2] = b[i];
        }
        chunk(out, "PLTE", palette);
        int stride = (w * bits + 7) / 8;
        byte[] row = new byte[stride + 1];
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream z = new DeflaterOutputStream(data, deflater, 1 << 16)) {
            for (int y = 0; y < h; y++) {
                Arrays.fill(row, (byte) 0);
                for (int x = 0; x < w; x++) {
                    int bit = x * bits;
                    row[1 + (bit >> 3)] |= (byte) ((indices[y * w + x] & 0xff) << (8 - bits - (bit & 7)));
                }
                z.write(row);
            }
        } finally {
            deflater.end();
        }
        chunk(out, "IDAT", data.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(body.length);
        out.write(name);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(body);
        out.writeInt((int) crc.getValue());
    }

    private static int bucket(int p) {
        return (p >> 9 & 0x7c00) | (p >> 6 & 0x3e0) | (p >> 3 & 0x1f);
    }

    private static int closest(byte[] r, byte[] g, byte[] b, int cr, int cg, int cb) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < r.length; i++) {
            int dr = (r[i] & 0xff) - cr;
            int dg = (g[i] & 0xff) - cg;
            int db = (b[i] & 0xff) - cb;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
