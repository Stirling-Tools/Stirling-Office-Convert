package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.ICOSParser;
import org.apache.pdfbox.io.RandomAccessRead;

import stirling.software.officeconvert.extract.PdfFiles;

final class Truncated {

    static final int CHUNK = 1 << 20;

    private static final int OVERLAP = 64;

    private static final byte[] STREAM = "stream".getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] END_STREAM = "endstream".getBytes(StandardCharsets.ISO_8859_1);

    private static final Set<COSName> DROPPED = Set.of(COSName.LENGTH, COSName.LENGTH1, COSName.FILTER,
            COSName.DECODE_PARMS, COSName.getPDFName("DL"));

    private Truncated() {}

    static Map<COSObjectKey, Long> locate(ICOSParser parser, List<COSObjectKey> keys) throws IOException {
        Map<COSObjectKey, Long> found = new HashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        Pattern header = Pattern.compile("(?<![0-9])(" + keys.stream().map(k -> Long.toString(k.getNumber()))
                .distinct().collect(Collectors.joining("|")) + ")\\s+(\\d{1,5})\\s+obj(?![A-Za-z0-9])");
        Set<COSObjectKey> wanted = Set.copyOf(keys);
        try (RandomAccessRead view = parser.createRandomAccessReadView(0, Long.MAX_VALUE / 2)) {
            byte[] buffer = new byte[CHUNK + OVERLAP];
            int kept = 0;
            long start = 0;
            while (true) {
                PdfFiles.stopIfInterrupted();
                int read = view.read(buffer, kept, CHUNK);
                if (read <= 0) {
                    break;
                }
                int length = kept + read;
                Matcher m = header.matcher(new String(buffer, 0, length, StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    if (m.end() <= kept && start > 0) {
                        continue;
                    }
                    COSObjectKey key = new COSObjectKey(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)));
                    if (wanted.contains(key)) {
                        found.put(key, start + m.start());
                    }
                }
                kept = Math.min(OVERLAP, length);
                System.arraycopy(buffer, length - kept, buffer, 0, kept);
                start += length - kept;
            }
        }
        return found;
    }

    static COSStream read(ICOSParser parser, long offset, long limit) throws IOException {
        int window = (int) Math.min(Integer.MAX_VALUE - 16, limit + (1 << 16));
        Bytes read = new Bytes(1 << 16);
        try (RandomAccessRead view = parser.createRandomAccessReadView(offset, window)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while (read.size() < window
                    && (n = view.read(buffer, 0, Math.min(buffer.length, window - read.size()))) > 0) {
                read.write(buffer, 0, n);
            }
        }
        byte[] data = read.toByteArray();
        int open = indexOf(data, new byte[] {'<', '<'}, 0, Math.min(data.length, 1 << 10));
        if (open < 0) {
            return null;
        }
        COSDictionary dict;
        int after;
        byte[] head = java.util.Arrays.copyOfRange(data, open, Math.min(data.length, open + (1 << 16)));
        try (Salvage.DictionaryParser p = new Salvage.DictionaryParser(head)) {
            dict = p.read();
            after = open + (int) p.position();
        }
        while (after < data.length && Lexical.whitespace(data[after] & 0xFF)) {
            after++;
        }
        if (!startsWith(data, after, STREAM)) {
            return null;
        }
        after += STREAM.length;
        if (after < data.length && data[after] == '\r') {
            after++;
        }
        if (after < data.length && data[after] == '\n') {
            after++;
        }
        int length = data.length - after;
        if (length <= 0 || length > limit || indexOf(data, END_STREAM, after, data.length) >= 0) {
            return null;
        }
        COSStream stream = new COSStream();
        for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
            stream.setItem(e.getKey(), e.getValue());
        }
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(data, after, length);
        }
        return stream;
    }

    static byte[] decoded(COSStream stream, long limit) throws IOException {
        byte[] raw;
        try (var in = stream.createRawInputStream()) {
            raw = in.readAllBytes();
        }
        COSBase filter = stream.getDictionaryObject(COSName.FILTER);
        if (filter == null) {
            return raw;
        }
        boolean flate = COSName.FLATE_DECODE.equals(filter) || filter instanceof org.apache.pdfbox.cos.COSArray a
                && a.size() == 1 && COSName.FLATE_DECODE.equals(a.getObject(0));
        if (!flate || stream.getDictionaryObject(COSName.DECODE_PARMS) != null) {
            return null;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(raw);
            Bytes out = new Bytes(raw.length * 3);
            byte[] buffer = new byte[1 << 16];
            while (!inflater.finished() && !inflater.needsInput()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsDictionary() || inflater.needsInput())) {
                    break;
                }
                if (out.size() + (long) n > limit) {
                    return null;
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    static COSStream rebuilt(COSStream damaged, byte[] font) throws IOException {
        COSStream stream = new COSStream();
        for (Map.Entry<COSName, COSBase> e : damaged.entrySet()) {
            if (!DROPPED.contains(e.getKey())) {
                stream.setItem(e.getKey(), e.getValue());
            }
        }
        stream.setItem(COSName.LENGTH1, COSInteger.get(font.length));
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(font);
        }
        return stream;
    }

    private static boolean startsWith(byte[] data, int at, byte[] word) {
        if (at + word.length > data.length) {
            return false;
        }
        for (int i = 0; i < word.length; i++) {
            if (data[at + i] != word[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] word, int from, int end) {
        for (int i = from; i + word.length <= end; i++) {
            if (startsWith(data, i, word)) {
                return i;
            }
        }
        return -1;
    }
}
