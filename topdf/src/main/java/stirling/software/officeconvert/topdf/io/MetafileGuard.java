package stirling.software.officeconvert.topdf.io;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class MetafileGuard {

    private static final int EMR_COMMENT = 70;

    private static final int EMF_PLUS = 0x2B464D45;

    private static final int PLUS_OBJECT = 0x4008;

    private static final int PLUS_BRUSH = 1;

    private static final int PLUS_IMAGE = 5;

    private static final int TEXTURE_FILL = 2;

    private static final int MAX_DEPTH = 3;

    private final long perBitmap;

    private final long total;

    private long used;

    private MetafileGuard(long perBitmap, long total) {
        this.perBitmap = perBitmap;
        this.total = total;
    }

    static void check(byte[] data, boolean emf, long perBitmap, long total) throws IOException {
        MetafileGuard guard = new MetafileGuard(perBitmap, total);
        if (emf) {
            guard.emf(data, 0);
        } else {
            guard.wmf(data);
        }
    }

    private static final class Continued {
        ByteArrayOutputStream data;
        long expected;
        int type;
    }

    private void emf(byte[] d, int depth) throws IOException {
        Continued continued = new Continued();
        int pos = 0;
        while (pos + 8 <= d.length) {
            long size = u32(d, pos + 4);
            if (size < 8 || size > d.length - pos) {
                break;
            }
            int len = (int) size;
            switch (s32(d, pos)) {
                case 76, 77, 114, 116 -> dib(d, pos, len, 84);
                case 78 -> {
                    dib(d, pos, len, 84);
                    dib(d, pos, len, 112);
                }
                case 79 -> {
                    dib(d, pos, len, 96);
                    dib(d, pos, len, 124);
                }
                case 80, 81 -> dib(d, pos, len, 48);
                case 93, 94 -> dib(d, pos, len, 16);
                case EMR_COMMENT -> {
                    if (len >= 16 && s32(d, pos + 12) == EMF_PLUS) {
                        plus(d, pos + 16, pos + len, depth, continued);
                    }
                }
                default -> {
                }
            }
            pos += len;
        }
        if (continued.data != null) {
            byte[] partial = continued.data.toByteArray();
            object(continued.type, partial, 0, partial.length, depth);
        }
    }

    private void plus(byte[] d, int start, int end, int depth, Continued continued) throws IOException {
        int pos = start;
        while (pos + 12 <= end) {
            int type = u16(d, pos);
            int flags = u16(d, pos + 2);
            long size = u32(d, pos + 4);
            long dataSize = u32(d, pos + 8);
            if (size < 12 || size > end - pos || dataSize > size - 12) {
                return;
            }
            int objectType = (flags >> 8) & 0x7F;
            if (type == PLUS_OBJECT && (objectType == PLUS_IMAGE || objectType == PLUS_BRUSH)) {
                int data = pos + 12;
                int n = (int) dataSize;
                if ((flags & 0x8000) == 0) {
                    object(objectType, d, data, data + n, depth);
                } else if (n >= 4) {
                    if (continued.data == null) {
                        continued.data = new ByteArrayOutputStream();
                        continued.expected = Math.min(u32(d, data), d.length);
                        continued.type = objectType;
                    }
                    continued.data.write(d, data + 4, n - 4);
                    if (continued.data.size() >= continued.expected) {
                        byte[] whole = continued.data.toByteArray();
                        continued.data = null;
                        object(continued.type, whole, 0, whole.length, depth);
                    }
                }
            }
            pos += (int) size;
        }
    }

    private void object(int type, byte[] d, int start, int end, int depth) throws IOException {
        if (type == PLUS_IMAGE) {
            image(d, start, end, depth);
        } else if (end - start >= 16 && s32(d, start + 4) == TEXTURE_FILL) {
            int at = start + 16 + ((s32(d, start + 8) & 2) != 0 ? 24 : 0);
            image(d, at, end, depth);
        }
    }

    private void image(byte[] d, int start, int end, int depth) throws IOException {
        if (end - start < 8) {
            return;
        }
        int kind = s32(d, start + 4);
        if (kind == 1 && end - start >= 28) {
            spend(s32(d, start + 8), s32(d, start + 12));
            if (s32(d, start + 24) == 1) {
                embedded(d, start + 28, end);
            }
        } else if (kind == 2 && end - start >= 16) {
            int from = start + 16;
            int to = (int) Math.min(end, from + u32(d, start + 12));
            if (depth >= MAX_DEPTH) {
                throw new IOException("The metafile nests other metafiles too deeply");
            }
            byte[] inner = Arrays.copyOfRange(d, from, to);
            switch (PictureDecoder.sniff(inner)) {
                case EMF -> emf(inner, depth + 1);
                case WMF -> wmf(inner);
                default -> embedded(inner, 0, inner.length);
            }
        }
    }

    private void wmf(byte[] d) throws IOException {
        int pos = 0;
        if (d.length >= 22 && s32(d, 0) == 0x9AC6CDD7) {
            pos = 22;
        }
        if (d.length - pos < 18) {
            return;
        }
        pos += u16(d, pos + 2) * 2;
        while (pos + 6 <= d.length) {
            long words = u32(d, pos);
            int function = u16(d, pos + 4);
            if (words < 3 || words * 2 > d.length - pos) {
                return;
            }
            int len = (int) (words * 2);
            boolean hasBitmap = words != (function >> 8) + 3;
            switch (function) {
                case 0x0000 -> {
                    return;
                }
                case 0x0940 -> {
                    if (hasBitmap) {
                        wmfDib(d, pos, len, 22);
                    }
                }
                case 0x0B41 -> {
                    if (hasBitmap) {
                        wmfDib(d, pos, len, 26);
                    }
                }
                case 0x0F43 -> wmfDib(d, pos, len, 28);
                case 0x0D33 -> wmfDib(d, pos, len, 24);
                case 0x0142 -> wmfDib(d, pos, len, 10);
                case 0x0922 -> bitmap16(d, pos, len, 22);
                case 0x0B23 -> bitmap16(d, pos, len, 26);
                case 0x01F9 -> bitmap16(d, pos, len, 6);
                default -> {
                }
            }
            pos += len;
        }
    }

    private void dib(byte[] d, int rec, int len, int field) throws IOException {
        if (field + 16 > len) {
            return;
        }
        long offBmi = u32(d, rec + field);
        long cbBmi = u32(d, rec + field + 4);
        long offBits = u32(d, rec + field + 8);
        long cbBits = u32(d, rec + field + 12);
        if (offBmi == 0 || cbBmi < 12 || offBmi > len - 12) {
            return;
        }
        int compression = header(d, rec + (int) offBmi, rec + len);
        if ((compression == 4 || compression == 5) && offBits > 0 && offBits < len && cbBits > 0) {
            embedded(d, rec + (int) offBits, rec + (int) Math.min(len, offBits + cbBits));
        }
    }

    private void wmfDib(byte[] d, int rec, int len, int at) throws IOException {
        if (at + 12 > len) {
            return;
        }
        int compression = header(d, rec + at, rec + len);
        if ((compression == 4 || compression == 5) && at + 40 <= len) {
            long biSize = u32(d, rec + at);
            embedded(d, rec + at + (int) Math.min(biSize, len - at), rec + len);
        }
    }

    private int header(byte[] d, int at, int end) throws IOException {
        if (at + 12 > end) {
            return -1;
        }
        long biSize = u32(d, at);
        if (biSize == 12) {
            spend(u16(d, at + 4), u16(d, at + 6));
            return 0;
        }
        if (biSize >= 40 && at + 20 <= end) {
            spend(s32(d, at + 4), Math.abs((long) s32(d, at + 8)));
            return s32(d, at + 16);
        }
        return -1;
    }

    private void bitmap16(byte[] d, int rec, int len, int at) throws IOException {
        if (at + 6 <= len) {
            spend(s16(d, rec + at + 2), s16(d, rec + at + 4));
        }
    }

    private void embedded(byte[] d, int from, int to) throws IOException {
        if (from < 0 || to > d.length || to - from < 8) {
            return;
        }
        byte[] data = Arrays.copyOfRange(d, from, to);
        if (markup(data)) {
            throw new IOException("The metafile embeds a markup picture (such as SVG), which is never drawn");
        }
        Dimension size;
        try {
            size = PictureDecoder.pixelSize(data);
        } catch (IOException e) {
            return;
        }
        spend(size.width, size.height);
    }

    static boolean markup(byte[] data) {
        int i = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            i = 3;
        } else if (data.length >= 2 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE
                || (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF)) {
            i = 2;
        }
        while (i < data.length && i < 4096
                && (data[i] == 32 || data[i] == 9 || data[i] == 10 || data[i] == 13 || data[i] == 0)) {
            i++;
        }
        return i < data.length && data[i] == '<';
    }

    private void spend(long w, long h) throws IOException {
        if (w < 0 || h < 0) {
            throw new IOException("The metafile holds a bitmap with a negative size");
        }
        long pixels = w * h;
        if (pixels > perBitmap) {
            throw new IOException("The metafile holds a bitmap of " + w + "x" + h + " pixels, over the budget");
        }
        used += pixels;
        if (used > total) {
            throw new IOException("The metafile holds more bitmap pixels than the budget allows");
        }
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    private static int s16(byte[] d, int i) {
        return (short) u16(d, i);
    }

    private static int s32(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16 | (d[i + 3] & 0xFF) << 24;
    }

    private static long u32(byte[] d, int i) {
        return s32(d, i) & 0xFFFFFFFFL;
    }
}
