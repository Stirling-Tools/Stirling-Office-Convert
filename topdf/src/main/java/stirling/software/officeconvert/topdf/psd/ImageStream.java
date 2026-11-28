package stirling.software.officeconvert.topdf.psd;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class ImageStream implements Closeable {

    private final COSStream stream;

    private final OutputStream out;

    private final Deflater deflater;

    private final byte[] buffer = new byte[1 << 16];

    private final byte[] line;

    private byte[] above;

    private final boolean predict;

    private boolean closed;

    ImageStream(PDDocument doc, int width, int height, int components, int bits, COSBase colorSpace, int level)
            throws IOException {
        int stride = (int) (((long) width * components * bits + 7) / 8);
        this.predict = bits >= 8;
        this.line = new byte[stride + 1];
        this.above = new byte[stride];
        this.deflater = new Deflater(level);
        this.stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, width);
        stream.setInt(COSName.HEIGHT, height);
        stream.setInt(COSName.BITS_PER_COMPONENT, bits);
        if (colorSpace != null) {
            stream.setItem(COSName.COLORSPACE, colorSpace);
        }
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        if (predict) {
            COSDictionary parms = new COSDictionary();
            parms.setInt(COSName.PREDICTOR, 15);
            parms.setInt(COSName.COLORS, components);
            parms.setInt(COSName.BITS_PER_COMPONENT, bits);
            parms.setInt(COSName.COLUMNS, width);
            stream.setItem(COSName.DECODE_PARMS, parms);
        }
        this.out = stream.createRawOutputStream();
    }

    COSStream stream() {
        return stream;
    }

    void row(byte[] row) throws IOException {
        int n = line.length - 1;
        if (predict) {
            line[0] = 2;
            for (int i = 0; i < n; i++) {
                line[i + 1] = (byte) (row[i] - above[i]);
            }
            System.arraycopy(row, 0, above, 0, n);
            deflater.setInput(line, 0, n + 1);
        } else {
            deflater.setInput(row, 0, n);
        }
        while (!deflater.needsInput()) {
            int got = deflater.deflate(buffer);
            if (got > 0) {
                out.write(buffer, 0, got);
            }
        }
    }

    void finish() throws IOException {
        deflater.finish();
        while (!deflater.finished()) {
            int got = deflater.deflate(buffer);
            if (got > 0) {
                out.write(buffer, 0, got);
            }
        }
        close();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } finally {
            deflater.end();
        }
    }
}
