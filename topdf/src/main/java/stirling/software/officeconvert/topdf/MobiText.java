package stirling.software.officeconvert.topdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class MobiText {

    static final long MAX_TEXT_BYTES = 64L << 20;

    static final int MAX_RECORD_TEXT = 1 << 20;

    static final String DAMAGED = "The eBook is damaged: a text record does not decompress";

    private MobiText() {}

    static byte[] read(PalmDatabase pdb, int base, MobiHeader h) throws IOException {
        Huffman huffman = switch (h.compression()) {
            case MobiHeader.NONE, MobiHeader.PALMDOC -> null;
            case MobiHeader.HUFF_CDIC -> Huffman.load(pdb, base + h.huffRecord(), h.huffCount());
            default -> throw new IOException("The eBook uses text compression type " + h.compression()
                    + ", which is not supported");
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i <= h.textRecords(); i++) {
            OfficeToPdf.stopIfInterrupted();
            if (!pdb.has(base + i)) {
                break;
            }
            byte[] record = pdb.record(base + i);
            int end = Math.max(0, record.length - trailing(record, h.extraFlags()));
            byte[] plain = switch (h.compression()) {
                case MobiHeader.NONE -> Arrays.copyOf(record, end);
                case MobiHeader.PALMDOC -> palmDoc(record, end);
                default -> huffman.unpack(record, end);
            };
            if (out.size() + (long) plain.length > MAX_TEXT_BYTES) {
                throw new IOException("The eBook's text is larger than " + (MAX_TEXT_BYTES >> 20) + " MB");
            }
            out.write(plain);
        }
        byte[] text = out.toByteArray();
        long length = h.textLength();
        return length > 0 && length < text.length ? Arrays.copyOf(text, (int) length) : text;
    }

    static int trailing(byte[] record, int flags) {
        int size = record.length;
        int num = 0;
        for (int f = flags >>> 1; f != 0; f >>>= 1) {
            if ((f & 1) != 0) {
                num += entrySize(record, size - num);
                if (num >= size) {
                    return size;
                }
            }
        }
        if ((flags & 1) != 0 && size - num - 1 >= 0) {
            num += (record[size - num - 1] & 0x3) + 1;
        }
        return Math.min(num, size);
    }

    private static int entrySize(byte[] record, int end) {
        int result = 0;
        int shift = 0;
        int p = end;
        while (p > 0) {
            int v = record[--p] & 0xFF;
            result |= (v & 0x7F) << shift;
            shift += 7;
            if ((v & 0x80) != 0 || shift >= 28) {
                break;
            }
        }
        return result;
    }

    static byte[] palmDoc(byte[] in, int end) throws IOException {
        byte[] out = new byte[Math.max(16, Math.min(end * 8, MAX_RECORD_TEXT))];
        int o = 0;
        int i = 0;
        while (i < end) {
            int c = in[i++] & 0xFF;
            if (o + 11 > out.length) {
                if (out.length >= MAX_RECORD_TEXT) {
                    throw new IOException(DAMAGED);
                }
                out = Arrays.copyOf(out, Math.min(MAX_RECORD_TEXT, out.length * 2));
            }
            if (c >= 1 && c <= 8) {
                if (i + c > end) {
                    throw new IOException(DAMAGED);
                }
                System.arraycopy(in, i, out, o, c);
                i += c;
                o += c;
            } else if (c < 0x80) {
                out[o++] = (byte) c;
            } else if (c < 0xC0) {
                if (i >= end) {
                    throw new IOException(DAMAGED);
                }
                int m = c << 8 | (in[i++] & 0xFF);
                int distance = (m >> 3) & 0x7FF;
                int length = (m & 7) + 3;
                if (distance == 0 || distance > o) {
                    throw new IOException(DAMAGED);
                }
                for (int k = 0; k < length; k++) {
                    out[o] = out[o - distance];
                    o++;
                }
            } else {
                out[o++] = ' ';
                out[o++] = (byte) (c ^ 0x80);
            }
        }
        return Arrays.copyOf(out, o);
    }
}
