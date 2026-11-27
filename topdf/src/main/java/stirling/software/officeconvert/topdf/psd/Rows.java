package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

abstract class Rows {

    static final int RAW = 0;

    static final int RLE = 1;

    static final int ZIP = 2;

    static final int ZIP_PREDICTED = 3;

    final int rowBytes;

    boolean damaged;

    Rows(int rowBytes) {
        this.rowBytes = rowBytes;
    }

    abstract void next(byte[] row) throws IOException;

    void close() {}

    void skip(int rows, byte[] scratch) throws IOException {
        for (int i = 0; i < rows; i++) {
            next(scratch);
        }
    }

    static Rows raw(Source.Cursor data, int rowBytes) {
        return new Rows(rowBytes) {
            @Override
            void next(byte[] row) throws IOException {
                int n = data.read(row, 0, rowBytes);
                if (n < rowBytes) {
                    Arrays.fill(row, n, rowBytes, (byte) 0);
                    damaged = true;
                }
            }
        };
    }

    static Rows rle(Source.Cursor counts, Source.Cursor data, boolean big, int rowBytes) {
        return new Rows(rowBytes) {
            @Override
            void next(byte[] row) throws IOException {
                long count;
                try {
                    count = big ? counts.u32() : counts.u16();
                } catch (IOException e) {
                    count = 0;
                    damaged = true;
                }
                long start = data.position();
                long limit = start + count;
                int out = 0;
                try {
                    while (data.position() < limit && out < rowBytes) {
                        int n = (byte) data.u8();
                        if (n >= 0) {
                            int want = n + 1;
                            if (want > limit - data.position()) {
                                want = (int) (limit - data.position());
                                damaged = true;
                            }
                            int len = Math.min(want, rowBytes - out);
                            int got = data.read(row, out, len);
                            out += got;
                            if (got < len) {
                                break;
                            }
                            if (len < want) {
                                data.skip(Math.min(want - len, data.remaining()));
                            }
                        } else if (n != -128) {
                            if (data.position() >= limit) {
                                damaged = true;
                                break;
                            }
                            int v = data.u8();
                            int len = Math.min(1 - n, rowBytes - out);
                            Arrays.fill(row, out, out + len, (byte) v);
                            out += len;
                        }
                    }
                } catch (IOException e) {
                    damaged = true;
                }
                if (out < rowBytes) {
                    Arrays.fill(row, out, rowBytes, (byte) 0);
                    damaged = true;
                }
                long used = data.position() - start;
                if (used != count) {
                    data.seek(Math.min(data.end(), start + count));
                }
            }
        };
    }

    static Rows zip(Source.Cursor data, int rowBytes, boolean predicted, int depth, int width) {
        Inflater inflater = new Inflater();
        InputStream in = new InflaterInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                return data.remaining() > 0 ? data.u8() : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (data.remaining() <= 0) {
                    return -1;
                }
                int n = data.read(b, off, (int) Math.min(len, data.remaining()));
                return n == 0 ? -1 : n;
            }
        }, inflater, 1 << 14);
        return new Rows(rowBytes) {
            private final byte[] planes = predicted && depth == 32 ? new byte[rowBytes] : null;

            @Override
            void close() {
                inflater.end();
            }

            @Override
            void next(byte[] row) throws IOException {
                int n = 0;
                try {
                    n = in.readNBytes(row, 0, rowBytes);
                } catch (IOException e) {
                    n = 0;
                }
                if (n < rowBytes) {
                    Arrays.fill(row, n, rowBytes, (byte) 0);
                    damaged = true;
                }
                if (predicted) {
                    undoPrediction(row, planes, rowBytes, depth, width);
                }
            }
        };
    }

    static void undoPrediction(byte[] row, byte[] planes, int rowBytes, int depth, int width) {
        if (depth == 8) {
            for (int i = 1; i < rowBytes; i++) {
                row[i] += row[i - 1];
            }
        } else if (depth == 16) {
            int prev = 0;
            for (int i = 0; i + 1 < rowBytes; i += 2) {
                int v = ((row[i] & 0xFF) << 8 | row[i + 1] & 0xFF) + prev & 0xFFFF;
                row[i] = (byte) (v >> 8);
                row[i + 1] = (byte) v;
                prev = v;
            }
        } else if (depth == 32) {
            for (int i = 1; i < rowBytes; i++) {
                row[i] += row[i - 1];
            }
            System.arraycopy(row, 0, planes, 0, rowBytes);
            for (int x = 0; x < width; x++) {
                row[4 * x] = planes[x];
                row[4 * x + 1] = planes[width + x];
                row[4 * x + 2] = planes[2 * width + x];
                row[4 * x + 3] = planes[3 * width + x];
            }
        }
    }
}
