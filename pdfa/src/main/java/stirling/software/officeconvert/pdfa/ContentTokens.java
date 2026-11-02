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
        ContentCache cache = ContentCache.current();
        for (COSStream s : streams) {
            try {
                byte[] decoded = cache == null ? null : cache.bytes(s);
                if (decoded == null) {
                    decoded = Decoded.bytes(s, MAX_CONTENT_BYTES - out.size(), "A content stream");
                    if (cache != null) {
                        cache.bytes(s, decoded);
                    }
                }
                if (decoded.length > MAX_CONTENT_BYTES - out.size()) {
                    throw new Decoded.TooLarge("A content stream", MAX_CONTENT_BYTES);
                }
                out.writeBytes(decoded);
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
            }
            out.write('\n');
        }
        return out.toByteArray();
    }

    static List<Object> parse(List<COSStream> streams) throws IOException {
        ContentCache cache = ContentCache.current();
        List<Object> tokens = cache == null ? null : cache.tokens(streams);
        if (tokens == null) {
            tokens = stirling.software.officeconvert.extract.ContentTokens.parse(checked(bytes(streams)));
            if (cache != null) {
                cache.tokens(streams, tokens);
            }
        }
        return tokens;
    }

    static byte[] checked(byte[] content) throws IOException {
        checkedTokens(content);
        return content;
    }

    static long checkedTokens(byte[] content) throws IOException {
        long n = tokens(content);
        if (n > MAX_TOKENS) {
            throw new Decoded.TooLarge("A content stream has more than " + MAX_TOKENS + " operators and operands");
        }
        return n;
    }

    private static final byte REGULAR = 0;

    private static final byte SPACE = 1;

    private static final byte DELIMITER = 2;

    private static final byte[] KINDS = new byte[256];

    static {
        for (char c : " \n\r\t\f\0".toCharArray()) {
            KINDS[c] = SPACE;
        }
        for (char c : "[]/(<".toCharArray()) {
            KINDS[c] = DELIMITER;
        }
    }

    static long tokens(byte[] content) {
        long n = 0;
        boolean inside = false;
        for (byte b : content) {
            byte kind = KINDS[b & 0xFF];
            if (kind == SPACE) {
                inside = false;
            } else {
                if (!inside || kind == DELIMITER) {
                    n++;
                }
                inside = true;
            }
        }
        return n;
    }

    record Salvaged(List<Object> tokens, boolean complete) {}

    static Salvaged salvage(List<COSStream> streams) throws IOException {
        ContentCache cache = ContentCache.current();
        List<Object> known = cache == null ? null : cache.tokens(streams);
        if (known != null) {
            return new Salvaged(known, true);
        }
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
            if (cache != null) {
                cache.tokens(streams, tokens);
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
        ContentCache cache = ContentCache.current();
        if (cache != null) {
            cache.changed(stream);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        new ContentStreamWriter(buf).writeTokens(tokens);
        stream.removeItem(COSName.DECODE_PARMS);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            buf.writeTo(out);
        }
    }
}
