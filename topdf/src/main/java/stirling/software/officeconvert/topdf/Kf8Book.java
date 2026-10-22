package stirling.software.officeconvert.topdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Kf8Book {

    static final int MAX_FLOWS = 10_000;

    static final String DAMAGED = "The eBook is damaged: its KF8 (AZW3) text cannot be put together";

    private static final Pattern POS = Pattern.compile("kindle:pos:fid:([0-9A-Va-v]{1,8}):off:([0-9A-Va-v]{1,12})");

    private static final Pattern EMBED = Pattern.compile("kindle:embed:([0-9A-Va-v]{1,8})(?:\\?mime=[^\"')\\s]*)?");

    private static final Pattern FLOW = Pattern.compile("kindle:flow:([0-9A-Va-v]{1,8})(?:\\?mime=([^\"')\\s]*))?");

    private static final Pattern AID = Pattern.compile("(<[A-Za-z][^<>]*?)\\s(?:aid|AID)\\s*=\\s*(['\"])([^'\"<>]*)\\2");

    private static final Pattern HAS_ID = Pattern.compile("\\s(?:id|ID)\\s*=");

    private static final Pattern ID = Pattern.compile("<[^>]+\\s(?:id|ID)\\s*=\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern NAME = Pattern.compile("<[^>]+\\s(?:name|NAME)\\s*=\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern AID_TARGET = Pattern.compile("<[^>]+\\s(?:aid|AID)\\s*=\\s*['\"]([^'\"]+)['\"]");

    private record Fragment(long insert, long length) {}

    private Kf8Book() {}

    static Ebook read(PalmDatabase pdb, int base, MobiHeader first) throws IOException {
        MobiHeader k = MobiHeader.parse(pdb.record(base), true);
        if (k.encryption() != 0) {
            throw new IOException(MobiBook.DRM);
        }
        byte[] text = MobiText.read(pdb, base, k);
        List<byte[]> flows = flows(pdb, base, k, text);
        byte[] flow0 = flows.get(0);
        List<Fragment> fragments = new ArrayList<>();
        List<byte[]> parts = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        try {
            if (k.skeletons() >= 0 && k.fragments() >= 0) {
                for (MobiIndex.Entry e : MobiIndex.read(pdb, base + k.fragments())) {
                    fragments.add(new Fragment(Long.parseLong(e.label().strip()), e.value(6, 1)));
                }
                assemble(MobiIndex.read(pdb, base + k.skeletons()), fragments, flow0, parts, starts);
            }
        } catch (NumberFormatException e) {
            throw new IOException(DAMAGED, e);
        }
        if (parts.isEmpty()) {
            parts.add(flow0);
            starts.add(0L);
        }
        int resources = resources(pdb, base, k, first);
        Map<String, byte[]> pages = new HashMap<>();
        Map<Integer, PartAnchors> anchors = new HashMap<>();
        List<String> chapters = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            OfficeToPdf.stopIfInterrupted();
            String name = String.format("part%04d.html", i);
            chapters.add(name);
            pages.put(name, page(new String(parts.get(i), StandardCharsets.UTF_8), fragments, parts, starts, anchors)
                    .getBytes(StandardCharsets.UTF_8));
        }
        HtmlAssets assets = Ebook.assets(reference -> {
            byte[] page = pages.get(reference);
            if (page != null) {
                return page;
            }
            if (reference.startsWith("flows/")) {
                int dot = reference.indexOf('.');
                try {
                    int n = Integer.parseInt(reference.substring(6, dot < 0 ? reference.length() : dot));
                    return n >= 1 && n < flows.size() ? resourcesIn(flows.get(n)) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            int record = MobiBook.picture(reference, resources);
            return record >= 0 && pdb.picture(record) ? pdb.record(record) : null;
        });
        int coverOffset = k.number(MobiHeader.EXTH_COVER) >= 0 ? k.number(MobiHeader.EXTH_COVER)
                : first.number(MobiHeader.EXTH_COVER);
        String cover = coverOffset >= 0 && resources >= 0 && pdb.picture(resources + coverOffset)
                ? "images/" + (coverOffset + 1) : null;
        String title = k.title() != null ? k.title() : first.title();
        String author = k.text(MobiHeader.EXTH_AUTHOR) != null ? k.text(MobiHeader.EXTH_AUTHOR)
                : first.text(MobiHeader.EXTH_AUTHOR);
        return new Ebook(EpubBook.assemble(assets, chapters, title, author, cover), assets);
    }

    private static int resources(PalmDatabase pdb, int base, MobiHeader k, MobiHeader first) {
        int own = k.firstImage() < 0 ? -1 : base + k.firstImage();
        if (own >= 0 && (pdb.picture(own) || pdb.startsWith(own, "FONT") || pdb.startsWith(own, "RESC"))) {
            return own;
        }
        return base > 0 && first.firstImage() >= 0 ? first.firstImage() : own;
    }

    private static List<byte[]> flows(PalmDatabase pdb, int base, MobiHeader k, byte[] text) throws IOException {
        List<byte[]> flows = new ArrayList<>();
        if (k.fdst() < 0 || k.fdstCount() <= 1 || !pdb.startsWith(base + k.fdst(), "FDST")) {
            flows.add(text);
            return flows;
        }
        byte[] fdst = pdb.record(base + k.fdst());
        int offset = PalmDatabase.index(fdst, 4);
        int count = PalmDatabase.index(fdst, 8);
        if (offset < 12 || count < 1 || count > MAX_FLOWS || offset + 8L * count > fdst.length) {
            throw new IOException(DAMAGED);
        }
        for (int i = 0; i < count; i++) {
            long start = PalmDatabase.u32(fdst, offset + 8 * i);
            long end = PalmDatabase.u32(fdst, offset + 8 * i + 4);
            if (start > end || end > text.length) {
                throw new IOException(DAMAGED);
            }
            flows.add(Arrays.copyOfRange(text, (int) start, (int) end));
        }
        return flows;
    }

    private static void assemble(List<MobiIndex.Entry> skeletons, List<Fragment> fragments, byte[] flow,
            List<byte[]> parts, List<Long> starts) throws IOException {
        int next = 0;
        for (MobiIndex.Entry skeleton : skeletons) {
            long count = skeleton.value(1, 0);
            long start = skeleton.value(6, 0);
            long length = skeleton.value(6, 1);
            if (start + length > flow.length || next + count > fragments.size()) {
                throw new IOException(DAMAGED);
            }
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            byte[] part = Arrays.copyOfRange(flow, (int) start, (int) (start + length));
            long cursor = start + length;
            for (long j = 0; j < count; j++) {
                OfficeToPdf.stopIfInterrupted();
                Fragment f = fragments.get(next++);
                long at = f.insert() - start;
                if (at < 0 || at > part.length || cursor + f.length() > flow.length) {
                    throw new IOException(DAMAGED);
                }
                joined.reset();
                joined.write(part, 0, (int) at);
                joined.write(flow, (int) cursor, (int) f.length());
                joined.write(part, (int) at, part.length - (int) at);
                part = joined.toByteArray();
                cursor += f.length();
                if (part.length > MobiText.MAX_TEXT_BYTES) {
                    throw new IOException(DAMAGED);
                }
            }
            parts.add(part);
            starts.add(start);
        }
    }

    private static String page(String html, List<Fragment> fragments, List<byte[]> parts, List<Long> starts,
            Map<Integer, PartAnchors> anchors) {
        Matcher m = POS.matcher(html);
        StringBuilder out = new StringBuilder(html.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(target(base32(m.group(1)), base32(m.group(2)),
                    fragments, parts, starts, anchors)));
        }
        m.appendTail(out);
        String s = resourcesIn(out.toString());
        Matcher a = AID.matcher(s);
        StringBuilder ids = new StringBuilder(s.length());
        while (a.find()) {
            String head = a.group(1);
            String replacement = HAS_ID.matcher(head).find() ? head
                    : head + " id=\"aid-" + MobiBook.escape(a.group(3)) + "\"";
            a.appendReplacement(ids, Matcher.quoteReplacement(replacement));
        }
        a.appendTail(ids);
        return ids.toString();
    }

    private static byte[] resourcesIn(byte[] css) {
        return resourcesIn(new String(css, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private static String resourcesIn(String s) {
        Matcher e = EMBED.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (e.find()) {
            e.appendReplacement(out, "/images/" + base32(e.group(1)));
        }
        e.appendTail(out);
        Matcher f = FLOW.matcher(out.toString());
        StringBuilder flows = new StringBuilder(out.length());
        while (f.find()) {
            String mime = f.group(2) == null ? "" : f.group(2);
            String ext = mime.contains("css") ? ".css" : mime.contains("svg") ? ".svg" : "";
            f.appendReplacement(flows, "/flows/" + base32(f.group(1)) + ext);
        }
        f.appendTail(flows);
        return flows.toString();
    }

    private static String target(long fid, long offset, List<Fragment> fragments, List<byte[]> parts,
            List<Long> starts, Map<Integer, PartAnchors> anchors) {
        if (fid < 0 || fid >= fragments.size()) {
            return "#";
        }
        long pos = fragments.get((int) fid).insert() + offset;
        for (int i = parts.size() - 1; i >= 0; i--) {
            long start = starts.get(i);
            if (pos >= start && pos < start + parts.get(i).length) {
                String id = anchors.computeIfAbsent(i, n -> PartAnchors.of(parts.get(n))).idBefore((int) (pos - start));
                return String.format("part%04d.html", i) + (id == null ? "" : "#" + id);
            }
        }
        return "#";
    }

    private record Anchors(int[] ends, String[] values) {

        static Anchors of(String block, Pattern p) {
            Matcher m = p.matcher(block);
            List<Integer> ends = new ArrayList<>();
            List<String> values = new ArrayList<>();
            while (m.find()) {
                ends.add(m.end());
                values.add(m.group(1));
            }
            return new Anchors(ends.stream().mapToInt(Integer::intValue).toArray(), values.toArray(String[]::new));
        }

        String lastBefore(int end) {
            int i = Arrays.binarySearch(ends, end);
            int at = i >= 0 ? i : -i - 2;
            return at >= 0 ? values[at] : null;
        }
    }

    private record PartAnchors(String block, Anchors ids, Anchors names, Anchors aids) {

        static PartAnchors of(byte[] part) {
            String block = new String(part, StandardCharsets.ISO_8859_1);
            return new PartAnchors(block, Anchors.of(block, ID), Anchors.of(block, NAME), Anchors.of(block, AID_TARGET));
        }

        String idBefore(int at) {
            int gt = block.indexOf('>', at);
            int lt = block.indexOf('<', at);
            int end = at;
            if (gt >= 0 && (lt == at || lt < 0 || gt < lt)) {
                end = gt + 1;
            }
            String id = ids.lastBefore(end);
            if (id == null) {
                id = names.lastBefore(end);
            }
            if (id == null) {
                String aid = aids.lastBefore(end);
                id = aid == null ? null : "aid-" + aid;
            }
            return id == null ? null : new String(id.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
    }

    static long base32(String digits) {
        long v = 0;
        for (char c : digits.toCharArray()) {
            v = v * 32 + Character.digit(Character.toUpperCase(c), 32);
        }
        return v;
    }
}
