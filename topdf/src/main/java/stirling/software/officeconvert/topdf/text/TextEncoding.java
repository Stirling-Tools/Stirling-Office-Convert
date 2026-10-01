package stirling.software.officeconvert.topdf.text;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

record TextEncoding(Charset charset, int bom) {

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");

    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

    static TextEncoding detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(4);
        }
        TextEncoding marked = fromBom(head);
        if (marked != null) {
            return marked;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return strictUtf8(in) ? new TextEncoding(StandardCharsets.UTF_8, 0) : new TextEncoding(WINDOWS_1252, 0);
        }
    }

    static TextEncoding fromBom(byte[] h) {
        int n = h.length;
        if (n >= 4 && h[0] == 0 && h[1] == 0 && (h[2] & 0xFF) == 0xFE && (h[3] & 0xFF) == 0xFF) {
            return new TextEncoding(UTF_32BE, 4);
        }
        if (n >= 4 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFE && h[2] == 0 && h[3] == 0) {
            return new TextEncoding(UTF_32LE, 4);
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xEF && (h[1] & 0xFF) == 0xBB && (h[2] & 0xFF) == 0xBF) {
            return new TextEncoding(StandardCharsets.UTF_8, 3);
        }
        if (n >= 2 && (h[0] & 0xFF) == 0xFE && (h[1] & 0xFF) == 0xFF) {
            return new TextEncoding(StandardCharsets.UTF_16BE, 2);
        }
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFE) {
            return new TextEncoding(StandardCharsets.UTF_16LE, 2);
        }
        return null;
    }

    static boolean strictUtf8(InputStream in) throws IOException {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer bytes = ByteBuffer.allocate(1 << 16);
        CharBuffer chars = CharBuffer.allocate(1 << 16);
        boolean end = false;
        while (true) {
            int r = end ? -1 : in.read(bytes.array(), bytes.position(), bytes.remaining());
            if (r < 0) {
                end = true;
            } else {
                bytes.position(bytes.position() + r);
            }
            bytes.flip();
            CoderResult result = d.decode(bytes, chars, end);
            if (result.isError()) {
                return false;
            }
            chars.clear();
            bytes.compact();
            if (end) {
                return !d.flush(chars).isError() && bytes.position() == 0;
            }
        }
    }

    Reader open(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            in.skipNBytes(bom);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        CharsetDecoder d = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new InputStreamReader(in, d);
    }
}
