package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentTokens {

    static final long MAX_CONTENT_BYTES = 64L << 20;

    static final long MAX_TOKENS = 8_000_000;

    private static final int SALVAGE_TAIL = 1024;

    private ContentTokens() {}

    static byte[] bytes(List<COSStream> streams) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (COSStream s : streams) {
            try {
                Decoded.copy(s, out, MAX_CONTENT_BYTES - out.size(), "A content stream");
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
            }
            out.write('\n');
        }
        return out.toByteArray();
    }

    static List<Object> parse(List<COSStream> streams) throws IOException {
        return stirling.software.officeconvert.extract.ContentTokens.parse(checked(bytes(streams)));
    }

    static byte[] checked(byte[] content) throws IOException {
        if (tokens(content) > MAX_TOKENS) {
            throw new Decoded.TooLarge("A content stream has more than " + MAX_TOKENS + " operators and operands");
        }
        return content;
    }

    static long tokens(byte[] content) {
        long n = 0;
        boolean inside = false;
        for (byte b : content) {
            boolean space = b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
            boolean delimiter = b == '[' || b == ']' || b == '/' || b == '(' || b == '<';
            if (!space && (!inside || delimiter)) {
                n++;
            }
            inside = !space;
        }
        return n;
    }

    record Salvaged(List<Object> tokens, boolean complete) {}

    static Salvaged salvage(List<COSStream> streams) throws IOException {
        byte[] content = checked(bytes(streams));
        PositionedParser parser = new PositionedParser(content);
        List<Object> tokens = new ArrayList<>();
        try {
            for (Object t; (t = parser.parseNextToken()) != null; ) {
                tokens.add(t);
                if ((tokens.size() & 0xFFFF) == 0) {
                    PdfFiles.stopIfInterrupted();
                }
            }
            return new Salvaged(tokens, true);
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            if (e instanceof IOException io) {
                Decoded.rethrowFatal(io);
            }
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

    static void replace(ContentGraph.Node n, List<?> tokens) throws IOException {
        if (n.kind() == ContentGraph.Kind.PAGE) {
            replacePage(n.owner(), tokens);
        } else {
            write(n.streams().get(0), tokens);
        }
    }

    static void replacePage(COSDictionary page, List<?> tokens) throws IOException {
        COSStream s = new COSStream();
        write(s, tokens);
        page.setItem(COSName.CONTENTS, s);
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
