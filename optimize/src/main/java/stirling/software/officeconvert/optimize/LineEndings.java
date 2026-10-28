package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.extract.PdfFiles;

final class LineEndings {

    private static final Pattern LENGTH = Pattern.compile("/Length\\s+(\\d+)(?:\\s+(\\d+)\\s+R)?");

    private static final Pattern NUMBER_OBJECT = Pattern
            .compile("(?<![0-9])(\\d+)\\s+(\\d+)\\s+obj\\s*(\\d+)\\s*endobj");

    private LineEndings() {}

    static int restore(Path in, Path out) throws IOException {
        if (Files.size(in) > Salvage.MAX_BYTES || !converted(in)) {
            return 0;
        }
        byte[] data = Files.readAllBytes(in);
        String text = new String(data, StandardCharsets.ISO_8859_1);
        Map<Long, Long> numbers = new HashMap<>();
        Matcher m = NUMBER_OBJECT.matcher(text);
        while (m.find()) {
            try {
                numbers.put(Long.parseLong(m.group(1)), Long.parseLong(m.group(3)));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        Bytes fixed = new Bytes(data.length);
        int copied = 0;
        int restored = 0;
        int steps = 0;
        int floor = 0;
        long budget = 2L * data.length;
        int at = 0;
        while ((at = text.indexOf("stream\r\n", at)) >= 0) {
            if ((steps++ & 255) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            int start = at + 8;
            int from = floor;
            floor = at;
            if (at >= 3 && text.startsWith("end", at - 3)) {
                at = start;
                continue;
            }
            int dict = lastObject(text, from, at);
            long length = dict < 0 ? -1 : length(text, dict, at, numbers);
            at = start;
            if (length <= 0 || length > data.length || ends(data, start + (int) Math.min(length, data.length))) {
                continue;
            }
            int i = start;
            long size = 0;
            while (size < length && i < data.length && i - start < budget) {
                if ((size & 0xFFFFF) == 0) {
                    PdfFiles.stopIfInterrupted();
                }
                i += data[i] == '\r' && i + 1 < data.length && data[i + 1] == '\n' ? 2 : 1;
                size++;
            }
            budget -= i - start;
            if (budget <= 0) {
                break;
            }
            if (size != length || !ends(data, i)) {
                continue;
            }
            fixed.write(data, copied, start - copied);
            for (int k = start; k < i; k++) {
                if (data[k] != '\r' || k + 1 >= i || data[k + 1] != '\n') {
                    fixed.write(data[k]);
                }
            }
            copied = i;
            at = i;
            floor = i;
            restored++;
        }
        if (restored == 0) {
            return 0;
        }
        fixed.write(data, copied, data.length - copied);
        try (OutputStream os = Files.newOutputStream(out)) {
            fixed.writeTo(os);
        }
        return restored;
    }

    private static long length(String text, int from, int to, Map<Long, Long> numbers) {
        Matcher m = LENGTH.matcher(text).region(from, to);
        long found = -1;
        while (m.find()) {
            try {
                found = m.group(2) == null ? Long.parseLong(m.group(1))
                        : numbers.getOrDefault(Long.parseLong(m.group(1)), -1L);
            } catch (NumberFormatException e) {
                found = -1;
            }
        }
        return found;
    }

    private static int lastObject(String text, int from, int to) {
        for (int p = to - 3; p >= from; p--) {
            if (text.startsWith("obj", p)) {
                return p;
            }
        }
        return -1;
    }

    private static boolean ends(byte[] data, int at) {
        int i = at;
        if (i < data.length && data[i] == '\r') {
            i++;
        }
        if (i < data.length && data[i] == '\n') {
            i++;
        }
        return i + 9 <= data.length && new String(data, i, 9, StandardCharsets.ISO_8859_1).equals("endstream");
    }

    private static boolean converted(Path in) throws IOException {
        boolean crlf = false;
        int previous = -1;
        byte[] buffer = new byte[1 << 16];
        try (InputStream is = Files.newInputStream(in)) {
            int n;
            while ((n = is.read(buffer)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        if (previous != '\r') {
                            return false;
                        }
                        crlf = true;
                    }
                    previous = buffer[i];
                }
                PdfFiles.stopIfInterrupted();
            }
        }
        return crlf;
    }
}
