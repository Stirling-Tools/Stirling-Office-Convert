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

final class Salvage {

    static final long MAX_BYTES = 256L << 20;

    static final int MAX_OBJECTS = 2_000_000;

    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page(?![A-Za-z0-9])");

    private static final Pattern CATALOG = Pattern.compile("/Type\\s*/Catalog(?![A-Za-z0-9])");

    private static final Pattern PAGES_REF = Pattern.compile("/Pages\\s+(\\d+)\\s+(\\d+)\\s+R");

    record Rebuilt(int pages) {}

    private Salvage() {}

    static Rebuilt rebuild(Path in, Path out) throws IOException {
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
        List<Integer> pages = new ArrayList<>();
        int catalog = -1;
        List<Map.Entry<Integer, Integer>> byOffset = new ArrayList<>(offsets.entrySet());
        byOffset.sort(Map.Entry.comparingByValue());
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
            if (PAGE.matcher(head).find() && !head.contains("/Kids")) {
                pages.add(number);
            } else if (CATALOG.matcher(head).find()) {
                catalog = number;
            }
        }
        if (pages.isEmpty()) {
            return null;
        }
        pages.sort((a, b) -> Integer.compare(offsets.get(a), offsets.get(b)));
        int next = offsets.lastKey() + 1;
        int pagesNumber = next++;
        int catalogNumber = catalog;
        if (catalog >= 0) {
            Matcher m = PAGES_REF.matcher(objectText(text, offsets, catalog));
            if (m.find()) {
                pagesNumber = Integer.parseInt(m.group(1));
            } else {
                catalogNumber = -1;
            }
        }
        StringBuilder added = new StringBuilder();
        TreeMap<Integer, Long> extra = new TreeMap<>();
        long position = data.length + 1;
        StringBuilder kids = new StringBuilder();
        for (int p : pages) {
            kids.append(p).append(" 0 R ");
        }
        String pagesObject = pagesNumber + " 0 obj\n<</Type/Pages/Kids[" + kids + "]/Count " + pages.size()
                + ">>\nendobj\n";
        extra.put(pagesNumber, position);
        added.append(pagesObject);
        position += pagesObject.length();
        if (catalogNumber < 0) {
            catalogNumber = next++;
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
            int gen = o == null || extra.containsKey(n) ? 0 : generations.getOrDefault(n, 0);
            table.append(o == null ? "0000000000 65535 f\r\n" : String.format("%010d %05d n\r\n", o, gen));
        }
        table.append("trailer\n<</Size ").append(size).append("/Root ").append(catalogNumber)
                .append(" 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n");
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(data);
            os.write('\n');
            os.write(added.toString().getBytes(StandardCharsets.ISO_8859_1));
            os.write(table.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return new Rebuilt(pages.size());
    }

    private static String objectText(String text, TreeMap<Integer, Integer> offsets, int number) {
        int start = offsets.get(number);
        int end = text.indexOf("endobj", start);
        return text.substring(start, end < 0 ? Math.min(text.length(), start + 4096) : end);
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
