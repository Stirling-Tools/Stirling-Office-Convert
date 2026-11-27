package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;

final class Merged {

    private Merged() {}

    static Rows[] open(Source src, PsdDocument doc, int[] channels) throws IOException {
        Rows[] rows = new Rows[channels.length];
        int rowBytes = doc.rowBytes();
        long start = doc.imageStart;
        if (start + 2 > src.size) {
            for (int i = 0; i < rows.length; i++) {
                rows[i] = Rows.raw(src.cursor(src.size, src.size, 16), rowBytes);
            }
            return rows;
        }
        Source.Cursor head = src.cursor(start, start + 2, 16);
        int compression = head.u16();
        long h = doc.height;
        switch (compression) {
            case Rows.RAW -> {
                for (int i = 0; i < rows.length; i++) {
                    long at = start + 2 + channels[i] * h * rowBytes;
                    rows[i] = Rows.raw(src.cursor(at, at + h * rowBytes, buffer(rowBytes)), rowBytes);
                }
            }
            case Rows.RLE -> {
                int entry = doc.big ? 4 : 2;
                long table = start + 2;
                long data = table + (long) doc.channels * h * entry;
                int last = 0;
                for (int c : channels) {
                    last = Math.max(last, c);
                }
                long[] offsets = new long[last + 1];
                Source.Cursor counts = src.cursor(table, data, 1 << 16);
                long sum = data;
                try {
                    for (int c = 0; c <= last; c++) {
                        offsets[c] = sum;
                        for (long y = 0; y < h; y++) {
                            sum += doc.big ? counts.u32() : counts.u16();
                        }
                    }
                } catch (IOException e) {
                    for (int c = 0; c <= last; c++) {
                        offsets[c] = offsets[c] == 0 && c > 0 ? src.size : offsets[c];
                    }
                }
                for (int i = 0; i < rows.length; i++) {
                    int c = channels[i];
                    long countStart = table + c * h * entry;
                    rows[i] = Rows.rle(src.cursor(countStart, countStart + h * entry, 1 << 12),
                            src.cursor(offsets[c], src.size, buffer(rowBytes)), doc.big, rowBytes);
                }
            }
            case Rows.ZIP, Rows.ZIP_PREDICTED -> {
                for (int i = 0; i < rows.length; i++) {
                    Rows r = Rows.zip(src.cursor(start + 2, src.size, 1 << 16), rowBytes,
                            compression == Rows.ZIP_PREDICTED, doc.depth, doc.width);
                    byte[] scratch = new byte[rowBytes];
                    for (long s = 0; s < channels[i] * h; s++) {
                        if ((s & 255) == 0) {
                            PsdPage.checkpoint();
                        }
                        r.next(scratch);
                    }
                    rows[i] = r;
                }
            }
            default -> throw new IOException("The PSD document's image data uses an unknown compression ("
                    + compression + ")");
        }
        return rows;
    }

    static int buffer(int rowBytes) {
        return Math.max(1 << 12, Math.min(1 << 16, rowBytes * 2));
    }
}
