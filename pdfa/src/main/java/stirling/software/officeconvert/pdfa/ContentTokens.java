package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentTokens {

    static final long MAX_CONTENT_BYTES = 256L << 20;

    private ContentTokens() {}

    static byte[] bytes(List<COSStream> streams) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (COSStream s : streams) {
            try (InputStream in = s.createInputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0 && out.size() <= MAX_CONTENT_BYTES; ) {
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                PdfFiles.stopIfInterrupted();
            }
            if (out.size() > MAX_CONTENT_BYTES) {
                throw new IOException("A content stream is larger than " + (MAX_CONTENT_BYTES >> 20) + " MB");
            }
            out.write('\n');
        }
        return out.toByteArray();
    }

    static List<Object> parse(List<COSStream> streams) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(bytes(streams));
        try {
            return parser.parse();
        } finally {
            parser.close();
        }
    }

    static void write(COSStream stream, List<?> tokens) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new ContentStreamWriter(buf).writeTokens(tokens);
        stream.removeItem(COSName.DECODE_PARMS);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            buf.writeTo(out);
        }
    }
}
