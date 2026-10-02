package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class Media {

    static final long MAX_TOTAL_BYTES = 512L << 20;

    private final Zip zip;

    private final Map<Key, String> names = new HashMap<>();

    private long total;

    private int next = 1;

    boolean dropped;

    Media(Zip zip) {
        this.zip = zip;
    }

    String add(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return null;
        }
        byte[] bytes = bitmap(data);
        String ext = extension(bytes);
        if (ext == null) {
            return null;
        }
        Key key = new Key(bytes);
        String name = names.get(key);
        if (name != null) {
            return name;
        }
        if (total + bytes.length > MAX_TOTAL_BYTES || !zip.fits(bytes.length)) {
            dropped = true;
            return null;
        }
        name = "image" + next++ + "." + ext;
        zip.put("word/media/" + name, bytes);
        total += bytes.length;
        names.put(key, name);
        return name;
    }

    static String extension(byte[] data) {
        return switch (PictureDecoder.sniff(data)) {
            case PNG -> "png";
            case JPEG -> "jpeg";
            case GIF -> "gif";
            case BMP -> "bmp";
            case TIFF -> "tiff";
            case EMF -> "emf";
            case WMF -> "wmf";
            case PICT -> "pict";
            default -> null;
        };
    }

    static byte[] bitmap(byte[] dib) {
        if (dib.length < 40 || le32(dib, 0) != 40 && le32(dib, 0) != 108 && le32(dib, 0) != 124) {
            return dib;
        }
        int header = le32(dib, 0);
        int bits = (dib[14] & 0xFF) | (dib[15] & 0xFF) << 8;
        int compression = le32(dib, 16);
        int used = le32(dib, 32);
        int colors = used != 0 ? used : bits <= 8 ? 1 << bits : 0;
        int masks = compression == 3 && header == 40 ? 12 : 0;
        int offset = 14 + header + masks + colors * 4;
        ByteBuffer b = ByteBuffer.allocate(14 + dib.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'B').put((byte) 'M').putInt(14 + dib.length).putInt(0).putInt(offset).put(dib);
        return b.array();
    }

    private static int le32(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }

    private record Key(byte[] data) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Arrays.equals(data, k.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
