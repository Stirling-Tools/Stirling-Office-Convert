package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;

final class Salvage {

    static final long MAX_BYTES = 256L << 20;

    static final int MAX_OBJECTS = 2_000_000;

    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![A-Za-z0-9])");

    private static final Pattern CATALOG = Pattern.compile("/Type\\s*/Catalog(?![A-Za-z0-9])");

    private static final Pattern PAGES_REF = Pattern.compile("/Pages\\s+(\\d+)\\s+(\\d+)\\s+R");

    private static final Pattern OBJECT_STREAM = Pattern.compile("/Type\\s*/ObjStm(?![A-Za-z0-9])");

    private static final Pattern COUNT = Pattern.compile("/N\\s+(\\d+)");

    private static final Pattern FIRST = Pattern.compile("/First\\s+(\\d+)");

    private static final int MAX_UNPACKED = 64 << 20;

    record Rebuilt(int pages, int unpacked) {}

    private Salvage() {}

    static Rebuilt rebuild(Path in, Path out) throws IOException {
        return rebuild(in, out, false);
    }

    static Rebuilt rebuildEmpty(Path in, Path out, long rootOffset) throws IOException {
        if (rootOffset < 0 || Files.size(in) > MAX_BYTES) {
            return null;
        }
        try (var file = Files.newByteChannel(in)) {
            if (rootOffset >= file.size()) {
                return null;
            }
            file.position(rootOffset);
            var bytes = java.nio.ByteBuffer.allocate((int) Math.min(1 << 16, file.size() - rootOffset));
            while (bytes.hasRemaining() && file.read(bytes) >= 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
            if (!declaresPages(new String(bytes.array(), 0, bytes.position(), StandardCharsets.ISO_8859_1))) {
                return null;
            }
        }
        return rebuild(in, out, true);
    }

    static String unsupported(Path in) throws IOException {
        if (Files.size(in) > MAX_BYTES) {
            return null;
        }
        String text = new String(Files.readAllBytes(in), StandardCharsets.ISO_8859_1);
        return text.contains("/BrotliDecode") ? "its objects are compressed with BrotliDecode, which is not supported"
                : null;
    }

    private static Rebuilt rebuild(Path in, Path out, boolean emptyOnly) throws IOException {
        stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
        if (Files.size(in) > MAX_BYTES) {
            return null;
        }
        byte[] data = Files.readAllBytes(in);
        String text = new String(data, StandardCharsets.ISO_8859_1);
        if (text.contains("/Encrypt")) {
            return null;
        }
        Map<Integer, Integer> generations = new java.util.HashMap<>();
        TreeMap<Integer, Integer> offsets = objects(data, generations);
        if (offsets.isEmpty()) {
            return null;
        }
        Map<Integer, String> bodies = new java.util.HashMap<>();
        Map<Integer, Long> order = new java.util.HashMap<>();
        List<Map.Entry<Integer, Integer>> byOffset = new ArrayList<>(offsets.entrySet());
        byOffset.sort(Map.Entry.comparingByValue());
        TreeMap<Integer, String> packed = new TreeMap<>();
        for (int k = 0; k < byOffset.size(); k++) {
            if ((k & 1023) == 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
            int number = byOffset.get(k).getKey();
            int start = byOffset.get(k).getValue();
            int end = k + 1 < byOffset.size() ? byOffset.get(k + 1).getValue() : data.length;
            String body = text.substring(start, Math.min(end, start + (1 << 16)));
            int stream = body.indexOf("stream");
            String head = stream >= 0 ? body.substring(0, stream) : body;
            bodies.put(number, head);
            order.put(number, (long) start << 20);
            if (stream >= 0 && OBJECT_STREAM.matcher(head).find()) {
                unpack(data, start, Math.min(end, data.length), head, packed, order);
            }
        }
        packed.keySet().removeAll(offsets.keySet());
        if (offsets.size() + packed.size() > MAX_OBJECTS) {
            return null;
        }
        bodies.putAll(packed);
        List<Integer> pages = new ArrayList<>();
        int catalog = -1;
        for (Map.Entry<Integer, String> e : bodies.entrySet()) {
            String head = e.getValue();
            if (PAGE.matcher(head).find() && !head.contains("/Kids")) {
                pages.add(e.getKey());
            } else if (CATALOG.matcher(head).find()
                    && (catalog < 0 || order.get(e.getKey()) > order.get(catalog))) {
                catalog = e.getKey();
            }
        }
        if (pages.isEmpty()) {
            return null;
        }
        pages.sort((a, b) -> Long.compare(order.get(a), order.get(b)));
        int highest = Math.max(offsets.lastKey(), packed.isEmpty() ? 0 : packed.lastKey());
        int next = highest + 1;
        int pagesNumber = next++;
        int catalogNumber = catalog;
        int catalogGeneration = packed.containsKey(catalog) ? 0 : generations.getOrDefault(catalog, 0);
        int pagesGeneration = 0;
        if (catalog >= 0) {
            Matcher m = PAGES_REF.matcher(packed.containsKey(catalog) ? packed.get(catalog)
                    : objectText(text, offsets, catalog));
            if (m.find()) {
                try {
                    int number = Integer.parseInt(m.group(1));
                    int generation = Integer.parseInt(m.group(2));
                    if (number > 0 && number < 8_000_000 && generation >= 0 && generation < 65535
                            && number != catalog && !pages.contains(number)) {
                        pagesNumber = number;
                        pagesGeneration = generation;
                    } else {
                        catalogNumber = -1;
                    }
                } catch (NumberFormatException e) {
                    catalogNumber = -1;
                }
            } else {
                catalogNumber = -1;
            }
        }
        String inheritedFrom = packed.containsKey(pagesNumber) && pagesGeneration == 0 ? packed.get(pagesNumber)
                : generations.getOrDefault(pagesNumber, -1) == pagesGeneration && offsets.containsKey(pagesNumber)
                        ? text.substring(offsets.get(pagesNumber), Math.min(text.length(),
                                offsets.get(pagesNumber) + (1 << 16)))
                        : null;
        if (emptyOnly && !declaresPages(inheritedFrom)) {
            return null;
        }
        StringBuilder added = new StringBuilder();
        TreeMap<Integer, Long> extra = new TreeMap<>();
        long position = data.length + 1;
        for (Map.Entry<Integer, String> e : packed.entrySet()) {
            if (e.getKey() == pagesNumber) {
                continue;
            }
            String object = e.getKey() + " 0 obj\n" + e.getValue() + "\nendobj\n";
            extra.put(e.getKey(), position);
            added.append(object);
            position += object.length();
        }
        StringBuilder kids = new StringBuilder();
        for (int p : pages) {
            kids.append(p).append(' ').append(packed.containsKey(p) ? 0 : generations.getOrDefault(p, 0))
                    .append(" R ");
        }
        String pagesObject = pagesNumber + " " + pagesGeneration + " obj\n<</Type/Pages"
                + inherited(inheritedFrom) + "/Kids[" + kids + "]/Count " + pages.size() + ">>\nendobj\n";
        extra.put(pagesNumber, position);
        added.append(pagesObject);
        position += pagesObject.length();
        if (catalogNumber < 0) {
            catalogNumber = next++;
            catalogGeneration = 0;
            String catalogObject = catalogNumber + " 0 obj\n<</Type/Catalog/Pages " + pagesNumber + " 0 R>>\nendobj\n";
            extra.put(catalogNumber, position);
            added.append(catalogObject);
            position += catalogObject.length();
        }
        TreeMap<Integer, Long> all = new TreeMap<>();
        offsets.forEach((n, o) -> all.put(n, (long) o));
        all.putAll(extra);
        int size = Math.max(all.lastKey(), next - 1) + 1;
        long xref = position;
        StringBuilder table = new StringBuilder("xref\n0 " + size + "\n0000000000 65535 f\r\n");
        for (int n = 1; n < size; n++) {
            if ((n & 1023) == 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
            Long o = all.get(n);
            int gen = o == null ? 0 : extra.containsKey(n) ? n == pagesNumber ? pagesGeneration : 0
                    : generations.getOrDefault(n, 0);
            if (o == null) {
                table.append("0000000000 65535 f\r\n");
            } else {
                padded(table, o, 10);
                table.append(' ');
                padded(table, gen, 5);
                table.append(" n\r\n");
            }
        }
        table.append("trailer\n<</Size ").append(size).append("/Root ").append(catalogNumber)
                .append(' ').append(catalogGeneration).append(" R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(data);
            os.write('\n');
            os.write(added.toString().getBytes(StandardCharsets.ISO_8859_1));
            os.write(table.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return new Rebuilt(pages.size(), packed.size());
    }

    private static void unpack(byte[] data, int start, int end, String head, Map<Integer, String> packed,
            Map<Integer, Long> order) {
        Matcher n = COUNT.matcher(head);
        Matcher first = FIRST.matcher(head);
        if (!n.find() || !first.find() || head.contains("/DecodeParms") || head.contains("/DP")) {
            return;
        }
        String filter = head.contains("/Filter") ? head.substring(head.indexOf("/Filter")) : "";
        boolean flate = filter.startsWith("/Filter/FlateDecode") || filter.startsWith("/Filter /FlateDecode")
                || filter.matches("(?s)/Filter\\s*\\[\\s*/FlateDecode\\s*\\].*");
        if (!filter.isEmpty() && !flate) {
            return;
        }
        int keyword = indexOf(data, "stream", start + head.length(), end);
        if (keyword < 0) {
            return;
        }
        int from = keyword + 6;
        if (from < end && data[from] == '\r') {
            from++;
        }
        if (from < end && data[from] == '\n') {
            from++;
        }
        int stop = indexOf(data, "endstream", from, end);
        byte[] raw = java.util.Arrays.copyOfRange(data, from, stop < 0 ? end : stop);
        byte[] decoded;
        boolean complete;
        if (flate) {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            inflater.setInput(raw);
            try {
                while (!inflater.finished() && !inflater.needsInput() && plain.size() < MAX_UNPACKED) {
                    int got = inflater.inflate(buffer);
                    if (got == 0 && (inflater.needsDictionary() || inflater.needsInput())) {
                        break;
                    }
                    plain.write(buffer, 0, got);
                }
                complete = inflater.finished();
            } catch (java.util.zip.DataFormatException e) {
                complete = false;
            } finally {
                inflater.end();
            }
            decoded = plain.toByteArray();
        } else {
            decoded = raw;
            complete = true;
        }
        int count;
        int offset;
        try {
            count = Integer.parseInt(n.group(1));
            offset = Integer.parseInt(first.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        if (count <= 0 || count > MAX_OBJECTS || offset <= 0 || offset > decoded.length) {
            return;
        }
        String content = new String(decoded, StandardCharsets.ISO_8859_1);
        String[] index = content.substring(0, offset).trim().split("\\s+");
        List<int[]> members = new ArrayList<>();
        for (int i = 0; i + 1 < index.length && members.size() < count; i += 2) {
            try {
                int number = Integer.parseInt(index[i]);
                int at = Integer.parseInt(index[i + 1]);
                if (number <= 0 || number >= 8_000_000 || at < 0 || offset + (long) at > content.length()) {
                    break;
                }
                members.add(new int[] {number, offset + at});
            } catch (NumberFormatException e) {
                break;
            }
        }
        for (int i = 0; i < members.size(); i++) {
            int at = members.get(i)[1];
            boolean last = i + 1 == members.size();
            if (last && !complete) {
                break;
            }
            int to = last ? content.length() : members.get(i + 1)[1];
            if (to <= at) {
                continue;
            }
            String body = content.substring(at, to).strip();
            if (!body.isEmpty() && packed.putIfAbsent(members.get(i)[0], body) == null) {
                order.put(members.get(i)[0], ((long) start << 20) + 1 + i);
            }
        }
    }

    private static int indexOf(byte[] data, String word, int from, int end) {
        byte[] w = word.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = Math.max(0, from); i + w.length <= end; i++) {
            for (int k = 0; k < w.length; k++) {
                if (data[i + k] != w[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String objectText(String text, TreeMap<Integer, Integer> offsets, int number) {
        int start = offsets.get(number);
        int end = text.indexOf("endobj", start);
        return text.substring(start, end < 0 ? Math.min(text.length(), start + 4096) : end);
    }

    private static void padded(StringBuilder out, long value, int width) {
        String digits = Long.toString(value);
        for (int i = digits.length(); i < width; i++) {
            out.append('0');
        }
        out.append(digits);
    }

    private static boolean declaresPages(String text) {
        if (text == null) {
            return false;
        }
        int end = Math.min(text.length(), 1 << 16);
        int start = text.indexOf("<<");
        if (start < 0 || start >= end) {
            return false;
        }
        byte[] data = text.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
        try (DictionaryParser parser = new DictionaryParser(data)) {
            return parser.read().getInt(COSName.COUNT, 0) > 0;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static String inherited(String text) {
        if (text == null) {
            return "";
        }
        int end = Math.min(text.length(), 1 << 16);
        int start = text.indexOf("<<");
        if (start < 0 || start >= end) {
            return "";
        }
        byte[] data = text.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
        try (DictionaryParser parser = new DictionaryParser(data)) {
            COSDictionary source = parser.read();
            COSDictionary values = new COSDictionary();
            for (COSName name : new COSName[] {COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE}) {
                values.setItem(name, source.getItem(name));
            }
            Bytes out = new Bytes(256);
            new Syntax((ref, syntax) -> {
                syntax.word(Long.toString(ref.getKey().getNumber()));
                syntax.word(Integer.toString(ref.getKey().getGeneration()));
                syntax.word("R");
            }).into(out).dictionary(values, null);
            String dictionary = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
            return dictionary.substring(2, dictionary.length() - 2);
        } catch (IOException | RuntimeException invalid) {
            return "";
        }
    }

    private static final class DictionaryParser extends PDFStreamParser implements AutoCloseable {

        DictionaryParser(byte[] data) {
            super(data);
            document = new COSDocument();
        }

        COSDictionary read() throws IOException {
            return parseCOSDictionary(false);
        }

        @Override
        public void close() throws IOException {
            try {
                document.close();
            } finally {
                super.close();
            }
        }
    }

    private static TreeMap<Integer, Integer> objects(byte[] d, Map<Integer, Integer> generations) throws IOException {
        TreeMap<Integer, Integer> found = new TreeMap<>();
        for (int i = 0; i + 3 <= d.length; i++) {
            if ((i & 4095) == 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
            if (d[i] != 'o' || d[i + 1] != 'b' || d[i + 2] != 'j') {
                continue;
            }
            if (i + 3 < d.length && Lexical.regular(d[i + 3] & 0xFF)) {
                continue;
            }
            int p = i - 1;
            if (p < 0 || !Lexical.whitespace(d[p] & 0xFF)) {
                continue;
            }
            while (p >= 0 && Lexical.whitespace(d[p] & 0xFF)) {
                p--;
            }
            int genEnd = p;
            while (p >= 0 && d[p] >= '0' && d[p] <= '9') {
                p--;
            }
            if (p == genEnd || p < 0 || !Lexical.whitespace(d[p] & 0xFF) || genEnd - p > 5) {
                continue;
            }
            int genStart = p + 1;
            while (p >= 0 && Lexical.whitespace(d[p] & 0xFF)) {
                p--;
            }
            int numEnd = p;
            while (p >= 0 && d[p] >= '0' && d[p] <= '9') {
                p--;
            }
            if (p == numEnd || numEnd - p > 9 || p >= 0 && Lexical.regular(d[p] & 0xFF)) {
                continue;
            }
            int number = Integer.parseInt(new String(d, p + 1, numEnd - p, StandardCharsets.ISO_8859_1));
            int gen = Integer.parseInt(new String(d, genStart, genEnd - genStart + 1, StandardCharsets.ISO_8859_1));
            if (number > 0 && number < 8_000_000 && gen < 65535) {
                found.put(number, p + 1);
                generations.put(number, gen);
                if (found.size() > MAX_OBJECTS) {
                    return new TreeMap<>();
                }
            }
        }
        return found;
    }
}
