package stirling.software.officeconvert.topdf.xls;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

// POI inflates every compressed metafile of a drawing with no limit, so each one is inflated here first with a cap
final class BlipGuard {

    static final long MAX_TOTAL_BYTES = 128L << 20;

    private static final int EMF = 0xF01A;

    private static final int PICT = 0xF01C;

    private static final int[] SIGNATURES = {0x3D40, 0x2160, 0x5420};

    private long total;

    boolean safe(byte[] escher) {
        if (escher == null) {
            return true;
        }
        for (int at = 0; at + 8 <= escher.length; at++) {
            int type = u16(escher, at + 2);
            if (type < EMF || type > PICT) {
                continue;
            }
            int options = u16(escher, at);
            int header = at + 8 + ((options ^ SIGNATURES[type - EMF]) == 0x10 ? 32 : 16);
            if (header + 34 > escher.length) {
                continue;
            }
            long cbSave = u32(escher, header + 28);
            if (escher[header + 32] != 0) {
                continue;
            }
            int start = header + 34;
            int length = (int) Math.min(cbSave, escher.length - (long) start);
            long inflated = inflated(escher, start, length);
            if (inflated > PictureDecoder.MAX_METAFILE_BYTES || (total += inflated) > MAX_TOTAL_BYTES) {
                return false;
            }
        }
        return true;
    }

    private static long inflated(byte[] data, int start, int length) {
        if (length <= 0) {
            return 0;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, start, length);
            byte[] sink = new byte[1 << 16];
            long out = 0;
            while (!inflater.finished() && out <= PictureDecoder.MAX_METAFILE_BYTES) {
                int n = inflater.inflate(sink);
                if (n == 0) {
                    break;
                }
                out += n;
            }
            return out;
        } catch (DataFormatException e) {
            return 0;
        } finally {
            inflater.end();
        }
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] d, int i) {
        return (d[i] & 0xFFL) | (d[i + 1] & 0xFFL) << 8 | (d[i + 2] & 0xFFL) << 16 | (d[i + 3] & 0xFFL) << 24;
    }
}
