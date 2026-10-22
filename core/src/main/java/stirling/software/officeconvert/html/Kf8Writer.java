package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import stirling.software.officeconvert.extract.PdfFiles;

final class Kf8Writer {

    static final int RECORD = 4096;

    static final int CHUNK = 8192;

    static final int MAX_RECORDS = 65_000;

    private static final String POS = "kindle:pos:fid:&&&&:off:&&&&&&&&&&";

    private static final Pattern ITEM = Pattern.compile("<item id=\"([^\"]+)\" href=\"([^\"]+)\" media-type=\"([^\"]+)\"");

    private static final Pattern ITEMREF = Pattern.compile("<itemref idref=\"([^\"]+)\"");

    private static final Pattern NAV_POINT = Pattern.compile(
            "<navLabel><text>(.*?)</text></navLabel><content src=\"([^\"]*)\"", Pattern.DOTALL);

    private static final Pattern TITLE = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.DOTALL);

    private static final Pattern CREATOR = Pattern.compile("<dc:creator>(.*?)</dc:creator>", Pattern.DOTALL);

    private static final Pattern START_TAG = Pattern.compile("<([A-Za-z][A-Za-z0-9:-]*)(\\s[^<>]*?)?(/?)>");

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    private static final Pattern SRC = Pattern.compile("src=\"([^\"]*)\"");

    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]+)\"");

    private record Chapter(String path, String html) {}

    private record Fragment(int file, long insert, long within, long layout, byte[] bytes) {}

    private record Target(String path, String id) {}

    private record TocEntry(String title, Target target) {}

    private final List<Chapter> chapters = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();
    private final Map<String, Integer> resources = new HashMap<>();
    private final List<byte[]> resourceBytes = new ArrayList<>();
    private final List<byte[]> flows = new ArrayList<>();
    private final Map<String, Integer> svgFlows = new HashMap<>();
    private final List<Target> links = new ArrayList<>();
    private String currentChapter = "";
    private final List<long[]> tocSpans = new ArrayList<>();
    private String title = "Document";
    private String author;
    private int aid;

    private Kf8Writer() {}

    static void write(Path epub, OutputStream out) throws IOException {
        Kf8Writer w = new Kf8Writer();
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            w.read(zip);
        }
        w.write(out);
    }

    private void read(ZipFile zip) throws IOException {
        String opf = text(zip, "OEBPS/content.opf");
        Matcher t = TITLE.matcher(opf);
        if (t.find()) {
            title = unescape(t.group(1)).strip();
        }
        Matcher c = CREATOR.matcher(opf);
        if (c.find()) {
            author = unescape(c.group(1)).strip();
        }
        Map<String, String> hrefs = new HashMap<>();
        Matcher items = ITEM.matcher(opf);
        while (items.find()) {
            hrefs.put(items.group(1), items.group(2));
        }
        flows.add(null);
        flows.add(read(zip, "OEBPS/styles/book.css"));
        Matcher refs = ITEMREF.matcher(opf);
        while (refs.find()) {
            String href = hrefs.get(refs.group(1));
            if (href != null) {
                PdfFiles.stopIfInterrupted();
                chapters.add(new Chapter(href, text(zip, "OEBPS/" + href)));
            }
        }
        ZipEntry ncx = zip.getEntry("OEBPS/toc.ncx");
        if (ncx != null) {
            Matcher m = NAV_POINT.matcher(text(zip, "OEBPS/toc.ncx"));
            while (m.find()) {
                toc.add(new TocEntry(unescape(m.group(1)).strip(), target("", m.group(2))));
            }
        }
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            chapters.set(i, new Chapter(ch.path(), rewrite(zip, ch)));
        }
    }

    private String rewrite(ZipFile zip, Chapter ch) throws IOException {
        currentChapter = ch.path();
        String s = ch.html().replaceFirst("<!DOCTYPE html>\\s*", "")
                .replace(" xmlns:epub=\"http://www.idpf.org/2007/ops\"", "")
                .replaceAll(" epub:type=\"[^\"]*\"", "")
                .replace("<meta charset=\"utf-8\"/>",
                        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>");
        s = s.replaceAll("<link rel=\"stylesheet\" type=\"text/css\" href=\"[^\"]*\"/>",
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"kindle:flow:0001?mime=text/css\"/>");
        StringBuilder out = new StringBuilder(s.length() + s.length() / 4);
        Matcher m = START_TAG.matcher(s);
        int bodyAt = s.indexOf("<body");
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            last = m.end();
            String attrs = m.group(2) == null ? "" : m.group(2);
            if (m.start() >= bodyAt && bodyAt >= 0) {
                attrs = links(attrs);
                attrs = pictures(zip, ch.path(), attrs, m.group(1));
                attrs = attrs + " aid=\"" + base32(aid++, 1) + "\"";
            }
            out.append('<').append(m.group(1)).append(attrs).append(m.group(3)).append('>');
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private String links(String attrs) {
        Matcher h = HREF.matcher(attrs);
        if (!h.find()) {
            return attrs;
        }
        String href = h.group(1);
        if (href.isEmpty() || href.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            return attrs;
        }
        links.add(target(currentChapter, href));
        return attrs.substring(0, h.start()) + "href=\"" + POS + "\"" + attrs.substring(h.end());
    }

    private Target target(String from, String href) {
        int hash = href.indexOf('#');
        String file = unescape(hash < 0 ? href : href.substring(0, hash));
        String id = hash < 0 ? null : href.substring(hash + 1);
        String path = file.isEmpty() ? from : resolve(from, file);
        return new Target(path, id);
    }

    private String pictures(ZipFile zip, String chapter, String attrs, String tag) throws IOException {
        if (!tag.equalsIgnoreCase("img")) {
            return attrs;
        }
        Matcher s = SRC.matcher(attrs);
        if (!s.find()) {
            return attrs;
        }
        String path = resolve(chapter, unescape(s.group(1)));
        String lower = path.toLowerCase(Locale.ROOT);
        String replacement;
        if (lower.endsWith(".svg")) {
            Integer flow = svgFlows.get(path);
            if (flow == null) {
                byte[] svg = read(zip, "OEBPS/" + path);
                if (svg == null) {
                    return attrs;
                }
                flows.add(svg);
                flow = flows.size() - 1;
                svgFlows.put(path, flow);
            }
            replacement = "kindle:flow:" + base32(flow, 4) + "?mime=image/svg+xml";
        } else {
            Integer index = resources.get(path);
            if (index == null) {
                byte[] bytes = read(zip, "OEBPS/" + path);
                if (bytes == null) {
                    return attrs;
                }
                resourceBytes.add(bytes);
                index = resourceBytes.size();
                resources.put(path, index);
            }
            String mime = lower.endsWith(".png") ? "image/png" : lower.endsWith(".gif") ? "image/gif" : "image/jpeg";
            replacement = "kindle:embed:" + base32(index, 4) + "?mime=" + mime;
        }
        return attrs.substring(0, s.start()) + "src=\"" + replacement + "\"" + attrs.substring(s.end());
    }

    private void write(OutputStream out) throws IOException {
        ByteArrayOutputStream flow0 = new ByteArrayOutputStream();
        List<Fragment> fragments = new ArrayList<>();
        Kf8Index skeletons = new Kf8Index(List.of(List.of(new Kf8Index.Tag(1, 1, 3), new Kf8Index.Tag(6, 2, 12))));
        Kf8Index chunks = new Kf8Index(List.of(List.of(new Kf8Index.Tag(2, 1, 1), new Kf8Index.Tag(3, 1, 2),
                new Kf8Index.Tag(4, 1, 4), new Kf8Index.Tag(6, 2, 8))));
        List<Integer> firstFragment = new ArrayList<>();
        for (int f = 0; f < chapters.size(); f++) {
            PdfFiles.stopIfInterrupted();
            String html = chapters.get(f).html();
            int open = html.indexOf("<body");
            int openEnd = open < 0 ? -1 : html.indexOf('>', open) + 1;
            int close = html.lastIndexOf("</body>");
            if (open < 0 || close < openEnd) {
                throw new IOException("A chapter of the book has no body");
            }
            byte[] skeleton = (html.substring(0, openEnd) + html.substring(close)).getBytes(StandardCharsets.UTF_8);
            long start = flow0.size();
            long insert = start + html.substring(0, openEnd).getBytes(StandardCharsets.UTF_8).length;
            String bodyAid = aidOf(html.substring(open, openEnd));
            flow0.writeBytes(skeleton);
            firstFragment.add(fragments.size());
            long within = 0;
            int count = 0;
            for (String piece : split(html.substring(openEnd, close))) {
                byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
                Fragment fr = new Fragment(f, insert + within, within, flow0.size(), bytes);
                chunks.add(String.format("%010d", fr.insert()), Map.of(
                        2, new long[] {chunks.text("P-//*[@aid='" + bodyAid + "']")},
                        3, new long[] {f},
                        4, new long[] {fragments.size()},
                        6, new long[] {within, bytes.length}));
                fragments.add(fr);
                flow0.writeBytes(bytes);
                within += bytes.length;
                count++;
            }
            skeletons.add(String.format("SKEL%010d", f), Map.of(
                    1, new long[] {count, count},
                    6, new long[] {start, skeleton.length, start, skeleton.length}));
        }
        byte[] text0 = flow0.toByteArray();
        Map<String, Place> places = places(fragments);
        Map<String, Integer> chapterStarts = new HashMap<>();
        for (int f = 0; f < chapters.size(); f++) {
            if (firstFragment.get(f) < fragments.size()) {
                chapterStarts.putIfAbsent(chapters.get(f).path(), firstFragment.get(f));
            }
        }
        resolveLinks(text0, fragments, places, chapterStarts);
        Kf8Index ncx = ncx(fragments, places, chapterStarts, text0.length);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        text.writeBytes(text0);
        List<long[]> flowTable = new ArrayList<>();
        flowTable.add(new long[] {0, text0.length});
        for (int i = 1; i < flows.size(); i++) {
            long from = text.size();
            text.writeBytes(flows.get(i) == null ? new byte[0] : flows.get(i));
            flowTable.add(new long[] {from, text.size()});
        }
        byte[] all = text.toByteArray();
        List<byte[]> records = new ArrayList<>();
        records.add(null);
        List<byte[]> trailing = Tbs.sequences(tocSpans, all.length, RECORD);
        for (int i = 0, r = 0; i < all.length; i += RECORD, r++) {
            PdfFiles.stopIfInterrupted();
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            record.writeBytes(textRecord(all, i));
            record.writeBytes(Tbs.trailingEntry(trailing.get(r)));
            records.add(record.toByteArray());
        }
        int textRecords = records.size() - 1;
        records.add(new byte[] {0});
        int firstNonBook = records.size() - 1;
        int chunkIndex = records.size();
        records.addAll(chunks.records());
        int skeletonIndex = records.size();
        records.addAll(skeletons.records());
        int ncxIndex = -1;
        if (!ncx.isEmpty()) {
            ncxIndex = records.size();
            records.addAll(ncx.records());
        }
        int firstResource = records.size();
        records.addAll(resourceBytes);
        int fdst = records.size();
        records.add(fdst(flowTable));
        int flis = records.size();
        records.add(FLIS.clone());
        int fcis = records.size();
        records.add(fcis(all.length));
        records.add(new byte[] {(byte) 0xE9, (byte) 0x8E, 0x0D, 0x0A});
        if (records.size() > MAX_RECORDS) {
            throw new IOException("The book is too large for the AZW3 format");
        }
        records.set(0, recordZero(all.length, textRecords, firstNonBook, firstResource, fdst, flowTable.size(), fcis,
                flis, ncxIndex, chunkIndex, skeletonIndex));
        pdb(records, out);
    }

    private List<String> split(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int bytes = 0;
        int i = 0;
        while (i < body.length()) {
            int lt = body.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            int gt = body.indexOf('>', lt);
            if (gt < 0) {
                break;
            }
            bytes += utf8Length(body, i, gt + 1);
            boolean closing = body.charAt(lt + 1) == '/';
            boolean selfClosing = body.charAt(gt - 1) == '/';
            if (closing) {
                depth--;
            } else if (!selfClosing && body.charAt(lt + 1) != '!' && body.charAt(lt + 1) != '?') {
                depth++;
            }
            i = gt + 1;
            if (depth <= 0 && bytes >= CHUNK) {
                out.add(body.substring(start, i));
                start = i;
                bytes = 0;
                depth = 0;
            }
        }
        if (start < body.length()) {
            out.add(body.substring(start));
        }
        return out;
    }

    private static int utf8Length(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;
        }
        return n;
    }

    private record Place(int fragment, long offset) {}

    private Map<String, Place> places(List<Fragment> fragments) {
        Map<String, Place> out = new HashMap<>();
        for (int k = 0; k < fragments.size(); k++) {
            Fragment fr = fragments.get(k);
            String block = new String(fr.bytes(), StandardCharsets.ISO_8859_1);
            Matcher m = ID.matcher(block);
            String path = chapters.get(fr.file()).path();
            while (m.find()) {
                int lt = block.lastIndexOf('<', m.start());
                String id = new String(m.group(1).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                out.putIfAbsent(path + "#" + id, new Place(k, Math.max(0, lt)));
            }
        }
        return out;
    }

    private static Place locate(Target t, Map<String, Place> places, Map<String, Integer> chapterStarts) {
        if (t.id() != null) {
            Place p = places.get(t.path() + "#" + t.id());
            if (p != null) {
                return p;
            }
        }
        Integer start = chapterStarts.get(t.path());
        return new Place(start == null ? 0 : start, 0);
    }

    private void resolveLinks(byte[] text, List<Fragment> fragments, Map<String, Place> places,
            Map<String, Integer> chapterStarts) {
        byte[] marker = POS.getBytes(StandardCharsets.US_ASCII);
        int k = 0;
        for (Fragment fr : fragments) {
            byte[] b = fr.bytes();
            for (int at = indexOf(b, marker, 0); at >= 0 && k < links.size(); at = indexOf(b, marker, at + 1)) {
                Place p = locate(links.get(k++), places, chapterStarts);
                byte[] value = ("kindle:pos:fid:" + base32(p.fragment(), 4) + ":off:" + base32(p.offset(), 10))
                        .getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(value, 0, b, at, value.length);
                System.arraycopy(value, 0, text, (int) (fr.layout() + at), value.length);
            }
        }
    }

    private Kf8Index ncx(List<Fragment> fragments, Map<String, Place> places, Map<String, Integer> chapterStarts,
            long length) {
        Kf8Index index = new Kf8Index(List.of(
                List.of(new Kf8Index.Tag(1, 1, 1), new Kf8Index.Tag(2, 1, 2), new Kf8Index.Tag(3, 1, 4),
                        new Kf8Index.Tag(4, 1, 8), new Kf8Index.Tag(21, 1, 16), new Kf8Index.Tag(22, 1, 32),
                        new Kf8Index.Tag(23, 1, 64), new Kf8Index.Tag(6, 2, 128)),
                List.of(new Kf8Index.Tag(69, 1, 1), new Kf8Index.Tag(70, 1, 4), new Kf8Index.Tag(71, 1, 8),
                        new Kf8Index.Tag(72, 1, 16), new Kf8Index.Tag(73, 1, 32))));
        if (fragments.isEmpty()) {
            return index;
        }
        List<Place> at = new ArrayList<>();
        for (TocEntry e : toc) {
            at.add(locate(e.target(), places, chapterStarts));
        }
        for (int i = 0; i < toc.size(); i++) {
            Place p = at.get(i);
            long pos = fragments.get(p.fragment()).layout() + p.offset();
            long next = i + 1 < toc.size()
                    ? fragments.get(at.get(i + 1).fragment()).layout() + at.get(i + 1).offset() : length;
            tocSpans.add(new long[] {pos, Math.max(0, next - pos)});
            index.add(String.format("%02x", i), Map.of(
                    1, new long[] {pos},
                    2, new long[] {Math.max(0, next - pos)},
                    3, new long[] {index.text(toc.get(i).title())},
                    4, new long[] {0},
                    6, new long[] {p.fragment(), p.offset()}));
        }
        return index;
    }

    private byte[] textRecord(byte[] all, int from) {
        int to = Math.min(all.length, from + RECORD);
        byte[] compressed = PalmDoc.compress(all, from, to);
        int extra = 0;
        while (to + extra < all.length && (all[to + extra] & 0xC0) == 0x80 && extra < 3) {
            extra++;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + extra + 1);
        out.writeBytes(compressed);
        out.write(all, to, extra);
        out.write(extra);
        return out.toByteArray();
    }

    private static final byte[] FLIS = {'F', 'L', 'I', 'S', 0, 0, 0, 8, 0, 0x41, 0, 0, 0, 0, 0, 0, (byte) 0xFF,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 1, 0, 3, 0, 0, 0, 3, 0, 0, 0, 1, (byte) 0xFF, (byte) 0xFF,
        (byte) 0xFF, (byte) 0xFF};

    private static byte[] fcis(long textLength) {
        byte[] r = new byte[44];
        byte[] head = {'F', 'C', 'I', 'S', 0, 0, 0, 0x14, 0, 0, 0, 0x10, 0, 0, 0, 2, 0, 0, 0, 0};
        System.arraycopy(head, 0, r, 0, head.length);
        put32(r, 20, textLength);
        byte[] tail = {0, 0, 0, 0, 0, 0, 0, 0x28, 0, 0, 0, 0, 0, 0, 0, 0x28, 0, 0, 0, 8, 0, 1, 0, 1, 0, 0, 0, 0};
        byte[] out = new byte[24 + tail.length];
        System.arraycopy(r, 0, out, 0, 24);
        System.arraycopy(tail, 0, out, 24, tail.length);
        return out;
    }

    private static byte[] fdst(List<long[]> table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("FDST".getBytes(StandardCharsets.US_ASCII));
        Kf8Index.u32(out, 12);
        Kf8Index.u32(out, table.size());
        for (long[] t : table) {
            Kf8Index.u32(out, t[0]);
            Kf8Index.u32(out, t[1]);
        }
        return out.toByteArray();
    }

    private byte[] recordZero(long textLength, int textRecords, int firstNonBook, int firstResource, int fdst,
            int fdstCount, int fcis, int flis, int ncx, int chunks, int skeletons) {
        byte[] h = new byte[16 + 264];
        put16(h, 0, 2);
        put32(h, 4, textLength);
        put16(h, 8, textRecords);
        put16(h, 10, RECORD);
        h[16] = 'M';
        h[17] = 'O';
        h[18] = 'B';
        h[19] = 'I';
        put32(h, 20, 264);
        put32(h, 24, 2);
        put32(h, 28, 65001);
        put32(h, 32, title.hashCode() & 0xFFFFFFFFL);
        put32(h, 36, 8);
        for (int off = 40; off <= 76; off += 4) {
            put32(h, off, 0xFFFFFFFFL);
        }
        put32(h, 80, firstNonBook);
        put32(h, 104, 8);
        put32(h, 108, firstResource);
        put32(h, 128, 0x50);
        put32(h, 164, 0xFFFFFFFFL);
        put32(h, 168, 0xFFFFFFFFL);
        put32(h, 192, fdst);
        put32(h, 196, fdstCount);
        put32(h, 200, fcis);
        put32(h, 204, 1);
        put32(h, 208, flis);
        put32(h, 212, 1);
        put32(h, 224, 0xFFFFFFFFL);
        put32(h, 232, 0xFFFFFFFFL);
        put32(h, 236, 0xFFFFFFFFL);
        put32(h, 240, 3);
        put32(h, 244, ncx < 0 ? 0xFFFFFFFFL : ncx);
        put32(h, 248, chunks);
        put32(h, 252, skeletons);
        put32(h, 256, 0xFFFFFFFFL);
        put32(h, 260, 0xFFFFFFFFL);
        byte[] exth = exth();
        byte[] name = title.getBytes(StandardCharsets.UTF_8);
        put32(h, 84, h.length + exth.length);
        put32(h, 88, name.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h);
        out.writeBytes(exth);
        out.writeBytes(name);
        out.writeBytes(new byte[2]);
        while (out.size() % 4 != 0) {
            out.write(0);
        }
        out.writeBytes(new byte[CHUNK]);
        return out.toByteArray();
    }

    private byte[] exth() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int count = 0;
        if (author != null && !author.isEmpty()) {
            count += exth(body, 100, author.getBytes(StandardCharsets.UTF_8));
        }
        count += exth(body, 503, title.getBytes(StandardCharsets.UTF_8));
        count += exth(body, 524, "und".getBytes(StandardCharsets.US_ASCII));
        count += exth(body, 501, "EBOK".getBytes(StandardCharsets.US_ASCII));
        count += exth(body, 125, new byte[] {0, 0, (byte) (resourceBytes.size() >>> 8), (byte) resourceBytes.size()});
        count += exth(body, 131, new byte[4]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("EXTH".getBytes(StandardCharsets.US_ASCII));
        Kf8Index.u32(out, 12 + body.size());
        Kf8Index.u32(out, count);
        out.writeBytes(body.toByteArray());
        while (out.size() % 4 != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static int exth(ByteArrayOutputStream out, int type, byte[] value) {
        Kf8Index.u32(out, type);
        Kf8Index.u32(out, value.length + 8L);
        out.writeBytes(value);
        return 1;
    }

    private void pdb(List<byte[]> records, OutputStream out) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        byte[] name = new byte[32];
        byte[] ascii = title.replaceAll("[^A-Za-z0-9]+", "_").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, name, 0, Math.min(31, ascii.length));
        h.writeBytes(name);
        Kf8Index.u16(h, 0);
        Kf8Index.u16(h, 0);
        long now = System.currentTimeMillis() / 1000;
        Kf8Index.u32(h, now);
        Kf8Index.u32(h, now);
        Kf8Index.u32(h, 0);
        Kf8Index.u32(h, 0);
        Kf8Index.u32(h, 0);
        Kf8Index.u32(h, 0);
        h.writeBytes("BOOKMOBI".getBytes(StandardCharsets.US_ASCII));
        Kf8Index.u32(h, 2L * records.size() - 1);
        Kf8Index.u32(h, 0);
        Kf8Index.u16(h, records.size());
        long offset = 78 + 8L * records.size() + 2;
        for (int i = 0; i < records.size(); i++) {
            Kf8Index.u32(h, offset);
            Kf8Index.u32(h, 2L * i);
            offset += records.get(i).length;
        }
        h.writeBytes(new byte[2]);
        out.write(h.toByteArray());
        for (byte[] r : records) {
            out.write(r);
        }
    }

    private static String aidOf(String tag) {
        Matcher m = Pattern.compile("aid=\"([^\"]+)\"").matcher(tag);
        return m.find() ? m.group(1) : "0";
    }

    private static int indexOf(byte[] b, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= b.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (b[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static String base32(long v, int width) {
        String digits = Long.toString(v, 32).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = digits.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(digits).toString();
    }

    private static String resolve(String from, String ref) {
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        List<String> steps = new ArrayList<>();
        for (String s : (dir + ref).split("/")) {
            if (s.equals("..")) {
                if (!steps.isEmpty()) {
                    steps.remove(steps.size() - 1);
                }
            } else if (!s.isEmpty() && !s.equals(".")) {
                steps.add(s);
            }
        }
        return String.join("/", steps);
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    private static String text(ZipFile zip, String name) throws IOException {
        byte[] b = read(zip, name);
        if (b == null) {
            throw new IOException("The book is missing " + name);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }

    static void put32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
