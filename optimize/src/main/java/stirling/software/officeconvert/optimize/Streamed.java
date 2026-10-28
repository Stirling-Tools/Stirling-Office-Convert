package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class Streamed {

    record Result(int spilled, byte[] digest, Map<COSName, COSBase> replace) {}

    private static final int MAX_ROW_BYTES = (int) Math.max(1024,
            Math.min(8 << 20, Runtime.getRuntime().maxMemory() / (Rewrite.MAX_THREADS * 64L)));

    private static final long MAX_DECODED_BYTES = Math.min(512L << 20, 4 * Recoder.MAX_DECODED_BYTES);

    private Streamed() {}

    static Result recode(COSStream s, Recoder.Kind kind, Predictors.Layout image, int rows, Spill spill,
            MessageDigest sha, PdfOptimizer.Effort effort) throws IOException {
        return recode(s, kind, image, rows, spill, sha, effort, MAX_ROW_BYTES, MAX_DECODED_BYTES);
    }

    static Result recode(COSStream s, Recoder.Kind kind, Predictors.Layout image, int rows, Spill spill,
            MessageDigest sha, PdfOptimizer.Effort effort, int maxRowBytes, long maxDecodedBytes) throws IOException {
        PdfFiles.stopIfInterrupted();
        maxRowBytes = Math.min(maxRowBytes, MAX_ROW_BYTES);
        maxDecodedBytes = Math.min(maxDecodedBytes, MAX_DECODED_BYTES);
        if (kind == Recoder.Kind.KEEP || s.containsKey(COSName.F)) {
            return null;
        }
        List<COSName> filters = new ArrayList<>();
        List<COSBase> parms = new ArrayList<>();
        if (!Recoder.chain(s, filters, parms) || filters.size() > 1 || filters.size() == 1
                && !COSName.FLATE_DECODE.equals(filters.get(0)) && !"Fl".equals(filters.get(0).getName())) {
            return null;
        }
        COSDictionary predictorParms = filters.isEmpty() ? null
                : Recoder.resolve(parms.get(0)) instanceof COSDictionary d ? d : null;
        Predictors.Layout given = null;
        int predictor = predictorParms == null ? 1 : predictorParms.getInt(COSName.PREDICTOR, 1);
        if (predictor > 1) {
            if (predictor < 10 || predictor > 15) {
                return null;
            }
            try {
                given = Predictors.Layout.of(predictorParms);
            } catch (Filters.Undecodable e) {
                return null;
            }
        }
        Predictors.Layout target = image != null && rows > 0 ? image : null;
        if (maxDecodedBytes < 1 || given != null && (given.rowBytes() < 1 || given.rowBytes() > maxRowBytes
                || given.rowBytes() > maxDecodedBytes) || target != null && (target.rowBytes() < 1
                || target.rowBytes() > maxRowBytes || (long) target.rowBytes() * rows > maxDecodedBytes)) {
            return null;
        }
        long raw = s.getLength();
        Spill.Writer w = spill.writer(sha);
        Deflater deflater = new Deflater(6);
        Inflater inflater = filters.isEmpty() ? null : new Inflater();
        boolean clean;
        try (InputStream in = s.createRawInputStream();
                OutputStream z = new DeflaterOutputStream(w, deflater, 1 << 16)) {
            Rows out = new Rows(z, target, maxDecodedBytes);
            byte[] buf = new byte[1 << 16];
            byte[] chunk = new byte[1 << 16];
            RowsIn rowsIn = given == null ? null : new RowsIn(given);
            int n;
            while ((n = in.read(buf)) > 0) {
                PdfFiles.stopIfInterrupted();
                if (inflater == null) {
                    out.write(buf, 0, n);
                    continue;
                }
                inflater.setInput(buf, 0, n);
                while (!inflater.needsInput() && !inflater.finished()) {
                    PdfFiles.stopIfInterrupted();
                    int k = inflater.inflate(chunk);
                    if (k == 0 && (inflater.needsDictionary() || !inflater.needsInput() && !inflater.finished())) {
                        break;
                    }
                    if (rowsIn != null) {
                        rowsIn.decode(chunk, k, out);
                    } else {
                        out.write(chunk, 0, k);
                    }
                }
                if (inflater.finished()) {
                    break;
                }
            }
            clean = (inflater == null || inflater.finished()) && (rowsIn == null || rowsIn.clean())
                    && out.clean() && (target == null || out.written == (long) target.rowBytes() * rows);
        } catch (DataFormatException | IOException e) {
            PdfFiles.stopIfInterrupted();
            clean = false;
        } finally {
            deflater.end();
            if (inflater != null) {
                inflater.end();
            }
        }
        PdfFiles.stopIfInterrupted();
        if (!clean || w.length() >= raw) {
            sha.reset();
            return null;
        }
        int index = w.finish();
        Map<COSName, COSBase> replace = new LinkedHashMap<>();
        replace.put(COSName.FILTER, COSName.FLATE_DECODE);
        replace.put(COSName.DECODE_PARMS, target == null ? null : Recoder.predictorParms(target));
        replace.put(COSName.getPDFName("DL"), null);
        replace.put(COSName.LENGTH, COSInteger.get(w.length()));
        return new Result(index, sha.digest(), replace);
    }

    private static final class RowsIn {

        private final int row;

        private final int bpp;

        private byte[] prev;

        private byte[] cur;

        private int fill;

        RowsIn(Predictors.Layout layout) {
            row = layout.rowBytes() + 1;
            bpp = layout.pixelBytes();
            prev = new byte[row - 1];
            cur = new byte[row];
        }

        void decode(byte[] data, int n, Rows out) throws IOException {
            for (int i = 0; i < n; ) {
                int take = Math.min(n - i, row - fill);
                System.arraycopy(data, i, cur, fill, take);
                fill += take;
                i += take;
                if (fill == row) {
                    int tag = cur[0] & 0xFF;
                    if (tag > 4) {
                        throw new IOException("The PNG row filter is not defined");
                    }
                    byte[] line = new byte[row - 1];
                    for (int x = 0; x < row - 1; x++) {
                        if ((x & 4095) == 0) {
                            PdfFiles.stopIfInterrupted();
                        }
                        int v = cur[x + 1] & 0xFF;
                        int a = x >= bpp ? line[x - bpp] & 0xFF : 0;
                        int b = prev[x] & 0xFF;
                        int c = x >= bpp ? prev[x - bpp] & 0xFF : 0;
                        line[x] = (byte) switch (tag) {
                            case 1 -> v + a;
                            case 2 -> v + b;
                            case 3 -> v + (a + b >>> 1);
                            case 4 -> v + Predictors.paeth(a, b, c);
                            default -> v;
                        };
                    }
                    out.write(line, 0, line.length);
                    prev = line;
                    fill = 0;
                }
            }
        }

        boolean clean() {
            return fill == 0;
        }
    }

    private static final class Rows {

        private final OutputStream out;

        private final Predictors.Layout layout;

        private final byte[] row;

        private byte[] prev;

        private int fill;

        private boolean first = true;

        private final long limit;

        private long written;

        Rows(OutputStream out, Predictors.Layout layout, long limit) {
            this.out = out;
            this.layout = layout;
            this.limit = limit;
            this.row = layout == null ? null : new byte[layout.rowBytes()];
            this.prev = layout == null ? null : new byte[layout.rowBytes()];
        }

        void write(byte[] data, int off, int len) throws IOException {
            PdfFiles.stopIfInterrupted();
            if (len > limit - written) {
                throw new IOException("The decoded stream exceeds the streaming rewrite limit");
            }
            written += len;
            if (layout == null) {
                out.write(data, off, len);
                return;
            }
            while (len > 0) {
                int take = Math.min(len, row.length - fill);
                System.arraycopy(data, off, row, fill, take);
                fill += take;
                off += take;
                len -= take;
                if (fill == row.length) {
                    PdfFiles.stopIfInterrupted();
                    byte[] two = new byte[row.length * 2];
                    System.arraycopy(prev, 0, two, 0, row.length);
                    System.arraycopy(row, 0, two, row.length, row.length);
                    byte[] encoded = Predictors.encodePng(two, layout, Predictors.ADAPTIVE);
                    if (first) {
                        out.write(Predictors.encodePng(row, layout, Predictors.ADAPTIVE));
                        first = false;
                    } else {
                        out.write(encoded, row.length + 1, row.length + 1);
                    }
                    System.arraycopy(row, 0, prev, 0, row.length);
                    fill = 0;
                }
            }
        }

        boolean clean() {
            return fill == 0;
        }
    }
}
