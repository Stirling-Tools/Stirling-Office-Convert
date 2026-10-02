package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentTokens {

    static final long MAX_CONTENT_BYTES = 256L << 20;

    private static final int SALVAGE_TAIL = 1024;

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

    record Salvaged(List<Object> tokens, boolean complete) {}

    static Salvaged salvage(List<COSStream> streams) throws IOException {
        byte[] content = bytes(streams);
        PositionedParser parser = new PositionedParser(content);
        List<Object> tokens = new ArrayList<>();
        try {
            for (Object t; (t = parser.parseNextToken()) != null; ) {
                tokens.add(t);
            }
            return new Salvaged(tokens, true);
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            long at = parser.position();
            if (at < 0 || content.length - at > Math.max(SALVAGE_TAIL, content.length / 20)) {
                throw e instanceof IOException io ? io : new IOException(e);
            }
            int last = tokens.size();
            while (last > 0 && !(tokens.get(last - 1) instanceof Operator)) {
                last--;
            }
            return new Salvaged(new ArrayList<>(tokens.subList(0, last)), false);
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
