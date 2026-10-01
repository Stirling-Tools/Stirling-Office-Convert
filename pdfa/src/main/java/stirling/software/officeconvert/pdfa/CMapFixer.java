package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.fontbox.cmap.CMap;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class CMapFixer {

    static final int MAX_CID = 65_535;

    private static final COSName WMODE = COSName.getPDFName("WMode");

    private static final COSName CMAP_NAME = COSName.getPDFName("CMapName");

    private static final COSName USE_CMAP = COSName.getPDFName("UseCMap");

    private static final Pattern CODESPACE = Pattern.compile("begincodespacerange(.*?)endcodespacerange", Pattern.DOTALL);

    private static final Pattern HEX = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");

    private static final Pattern CID_RANGE = Pattern.compile(
            "<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\d+)");

    private static final Pattern CID_CHAR = Pattern.compile("<([0-9A-Fa-f]+)>\\s*(\\d+)");

    private static final Set<String> PREDEFINED = Set.of("GB-EUC-H", "GB-EUC-V", "GBpc-EUC-H", "GBpc-EUC-V",
            "GBK-EUC-H", "GBK-EUC-V", "GBKp-EUC-H", "GBKp-EUC-V", "GBK2K-H", "GBK2K-V", "UniGB-UCS2-H", "UniGB-UCS2-V",
            "UniGB-UTF16-H", "UniGB-UTF16-V", "B5pc-H", "B5pc-V", "HKscs-B5-H", "HKscs-B5-V", "ETen-B5-H", "ETen-B5-V",
            "ETenms-B5-H", "ETenms-B5-V", "CNS-EUC-H", "CNS-EUC-V", "UniCNS-UCS2-H", "UniCNS-UCS2-V", "UniCNS-UTF16-H",
            "UniCNS-UTF16-V", "83pv-RKSJ-H", "90ms-RKSJ-H", "90ms-RKSJ-V", "90msp-RKSJ-H", "90msp-RKSJ-V",
            "90pv-RKSJ-H", "Add-RKSJ-H", "Add-RKSJ-V", "EUC-H", "EUC-V", "Ext-RKSJ-H", "Ext-RKSJ-V", "H", "V",
            "UniJIS-UCS2-H", "UniJIS-UCS2-V", "UniJIS-UCS2-HW-H", "UniJIS-UCS2-HW-V", "UniJIS-UTF16-H",
            "UniJIS-UTF16-V", "KSC-EUC-H", "KSC-EUC-V", "KSCms-UHC-H", "KSCms-UHC-V", "KSCms-UHC-HW-H",
            "KSCms-UHC-HW-V", "KSCpc-EUC-H", "UniKS-UCS2-H", "UniKS-UCS2-V", "UniKS-UTF16-H", "UniKS-UTF16-V");

    private final PDDocument doc;

    private final PdfALevel level;

    private CMapFixer(PDDocument doc, PdfALevel level) {
        this.doc = doc;
        this.level = level;
    }

    static int maxCid(COSBase encoding) {
        if (!(encoding instanceof COSStream s)) {
            return 0;
        }
        byte[] b = StreamFixer.read(s);
        if (b == null) {
            return 0;
        }
        String text = new String(b, StandardCharsets.ISO_8859_1);
        int max = 0;
        for (String block : blocks(text, "begincidrange", "endcidrange")) {
            Matcher m = CID_RANGE.matcher(block);
            while (m.find()) {
                long lo = Long.parseLong(m.group(1), 16);
                long hi = Long.parseLong(m.group(2), 16);
                long cid = Long.parseLong(m.group(3));
                max = (int) Math.min(Integer.MAX_VALUE, Math.max(max, cid + Math.max(0, hi - lo)));
            }
        }
        for (String block : blocks(text, "begincidchar", "endcidchar")) {
            Matcher m = CID_CHAR.matcher(block);
            while (m.find()) {
                max = (int) Math.min(Integer.MAX_VALUE, Math.max(max, Long.parseLong(m.group(2))));
            }
        }
        return max;
    }

    static boolean inline(PDDocument doc, COSDictionary font) throws IOException {
        if (!COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))
                || !(font.getDictionaryObject(COSName.ENCODING) instanceof COSStream s)
                || !(s.getDictionaryObject(USE_CMAP) instanceof COSStream)) {
            return false;
        }
        String merged = merged(s, 0);
        if (merged == null) {
            return false;
        }
        COSStream out = doc.getDocument().createCOSStream();
        for (Map.Entry<COSName, COSBase> e : s.entrySet()) {
            if (!USE_CMAP.equals(e.getKey()) && !COSName.LENGTH.equals(e.getKey()) && !COSName.FILTER.equals(e.getKey())
                    && !COSName.DECODE_PARMS.equals(e.getKey())) {
                out.setItem(e.getKey(), e.getValue());
            }
        }
        try (OutputStream o = out.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(merged.getBytes(StandardCharsets.ISO_8859_1));
        }
        font.setItem(COSName.ENCODING, out);
        return true;
    }

    private static String merged(COSStream s, int depth) {
        String text = text(s).replaceAll("/[^\\s/]+\\s+usecmap", "");
        if (depth > 8 || !text.contains("begincmap")) {
            return depth > 8 ? null : text;
        }
        if (!(s.getDictionaryObject(USE_CMAP) instanceof COSStream parent)) {
            return text;
        }
        String up = merged(parent, depth + 1);
        if (up == null) {
            return null;
        }
        StringBuilder blocks = new StringBuilder("\n");
        String[][] kinds = text.contains("begincodespacerange")
                ? new String[][] {{"begincidrange", "endcidrange"}, {"begincidchar", "endcidchar"},
                        {"beginnotdefrange", "endnotdefrange"}, {"beginnotdefchar", "endnotdefchar"}}
                : new String[][] {{"begincodespacerange", "endcodespacerange"}, {"begincidrange", "endcidrange"},
                        {"begincidchar", "endcidchar"}, {"beginnotdefrange", "endnotdefrange"},
                        {"beginnotdefchar", "endnotdefchar"}};
        for (String[] kind : kinds) {
            Matcher m = Pattern.compile("\\d+\\s+" + kind[0] + ".*?" + kind[1], Pattern.DOTALL).matcher(up);
            while (m.find()) {
                blocks.append(m.group()).append('\n');
            }
        }
        int at = text.indexOf("begincmap") + "begincmap".length();
        return text.substring(0, at) + blocks + text.substring(at);
    }

    static String run(PDDocument doc, PDType0Font font, Set<Integer> codes, PdfALevel level) throws IOException {
        return new CMapFixer(doc, level).fix(font, codes);
    }

    private String fix(PDType0Font font, Set<Integer> codes) throws IOException {
        COSDictionary dict = font.getCOSObject();
        COSBase enc = dict.getDictionaryObject(COSName.ENCODING);
        PDCIDFont cid = font.getDescendantFont();
        CMap cmap = font.getCMap();
        if (cid == null || cmap == null) {
            return null;
        }
        if (level.part() > 1 && showsCidZero(font, codes)) {
            String text = enc instanceof COSStream st ? text(st) : null;
            return flatten(font, cid, cmap, codes, text) ? "Moved glyphs shown as CID 0, which PDF/A-2 reserves for "
                    + ".notdef, to another CID" : null;
        }
        if (enc instanceof COSName n) {
            String name = n.getName();
            if (name.startsWith("Identity-") || level.part() > 1 && PREDEFINED.contains(name)) {
                return null;
            }
            COSStream embedded = predefined(name, cmap);
            if (embedded != null) {
                dict.setItem(COSName.ENCODING, embedded);
                return "Embedded the " + name + " CMap, as PDF/A-1 needs";
            }
            return flatten(font, cid, cmap, codes, null) ? "Embedded a CMap for the font " + font.getName() : null;
        }
        if (!(enc instanceof COSStream s)) {
            return null;
        }
        String note = null;
        if (s.getInt(WMODE, 0) != cmap.getWMode()) {
            s.setInt(WMODE, cmap.getWMode());
            note = "Made the writing mode of a CMap agree with its program";
        }
        String text = text(s);
        COSBase use = s.getDictionaryObject(USE_CMAP);
        boolean chained = use instanceof COSStream
                || use instanceof COSName u && !(level.part() > 1 && PREDEFINED.contains(u.getName()))
                || text.contains("usecmap") && !(level.part() > 1 && usesPredefined(text));
        if (chained || maxCid(s) > MAX_CID) {
            if (flatten(font, cid, cmap, codes, text)) {
                note = "Rewrote a CMap that refers to another CMap or has CIDs past " + MAX_CID;
            }
        }
        return note;
    }

    private static boolean showsCidZero(PDType0Font font, Set<Integer> codes) {
        for (int code : codes) {
            try {
                if (font.codeToCID(code) == 0) {
                    return true;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean usesPredefined(String text) {
        Matcher m = Pattern.compile("/([^\\s/]+)\\s+usecmap").matcher(text);
        while (m.find()) {
            if (!PREDEFINED.contains(m.group(1))) {
                return false;
            }
        }
        return true;
    }

    private COSStream predefined(String name, CMap parsed) throws IOException {
        byte[] data;
        try (InputStream in = CMap.class.getResourceAsStream("/org/apache/fontbox/cmap/" + name)) {
            if (in == null || name.contains("/") || name.contains("..")) {
                return null;
            }
            data = in.readAllBytes();
        }
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
        s.setName(CMAP_NAME, name);
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, parsed.getRegistry() == null ? "Adobe" : parsed.getRegistry());
        info.setString(COSName.ORDERING, parsed.getOrdering() == null ? "Identity" : parsed.getOrdering());
        info.setInt(COSName.SUPPLEMENT, parsed.getSupplement());
        s.setItem(COSName.CIDSYSTEMINFO, info);
        s.setInt(WMODE, parsed.getWMode());
        Matcher m = Pattern.compile("/([^\\s/]+)\\s+usecmap").matcher(new String(data, StandardCharsets.ISO_8859_1));
        if (m.find() && !m.group(1).equals(name)) {
            COSStream parent = predefined(m.group(1), parsed);
            if (parent != null) {
                s.setItem(USE_CMAP, parent);
            }
        }
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(data);
        }
        return s;
    }

    private boolean flatten(PDType0Font font, PDCIDFont cid, CMap cmap, Set<Integer> codes, String text)
            throws IOException {
        List<int[]> spaces = codespaces(text);
        int width = 0;
        for (int[] sp : spaces) {
            width = Math.max(width, sp[2]);
        }
        if (spaces.isEmpty()) {
            spaces.add(new int[] {0, 0xFFFF, 2});
            width = 2;
        }
        TreeMap<Integer, Integer> mapping = new TreeMap<>();
        Set<Integer> usedCids = new HashSet<>();
        TreeMap<Integer, Integer> lengths = new TreeMap<>();
        Map<Integer, Float> widths = new TreeMap<>();
        for (int code : codes) {
            int len = length(code, spaces);
            int c = cmap.toCID(code, len);
            mapping.put(code, c);
            lengths.put(code, len);
            usedCids.add(c);
            if (c > MAX_CID || c == 0) {
                widths.put(c, font.getWidth(code));
            }
        }
        Map<Integer, Integer> remap = new TreeMap<>();
        int free = 1;
        for (Map.Entry<Integer, Integer> e : mapping.entrySet()) {
            if (e.getValue() > MAX_CID || e.getValue() == 0 && level.part() > 1) {
                Integer to = remap.get(e.getValue());
                if (to == null) {
                    while (usedCids.contains(free)) {
                        free++;
                    }
                    to = free++;
                    remap.put(e.getValue(), to);
                }
                e.setValue(to);
            }
        }
        if (!remap.isEmpty() && !moveCids(cid, remap, widths)) {
            return false;
        }
        StringBuilder b = new StringBuilder();
        COSDictionary info = ContentGraph.dict(cid.getCOSObject().getDictionaryObject(COSName.CIDSYSTEMINFO));
        String registry = info == null ? "Adobe" : info.getString(COSName.REGISTRY, "Adobe");
        String ordering = info == null ? "Identity" : info.getString(COSName.ORDERING, "Identity");
        int supplement = info == null ? 0 : info.getInt(COSName.SUPPLEMENT, 0);
        String name = "PDFA-" + registry + "-" + ordering + "-" + Integer.toHexString(mapping.hashCode());
        b.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (")
                .append(registry).append(") /Ordering (").append(ordering).append(") /Supplement ").append(supplement)
                .append(" >> def\n/CMapName /").append(name).append(" def\n/CMapType 1 def\n/WMode ")
                .append(cmap.getWMode()).append(" def\n").append(spaces.size()).append(" begincodespacerange\n");
        for (int[] sp : spaces) {
            b.append('<').append(hex(sp[0], sp[2])).append("> <").append(hex(sp[1], sp[2])).append(">\n");
        }
        b.append("endcodespacerange\n");
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(mapping.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            List<Map.Entry<Integer, Integer>> part = entries.subList(i, Math.min(entries.size(), i + 100));
            b.append(part.size()).append(" begincidchar\n");
            for (Map.Entry<Integer, Integer> e : part) {
                b.append('<').append(hex(e.getKey(), lengths.get(e.getKey()))).append("> ").append(e.getValue())
                        .append('\n');
            }
            b.append("endcidchar\n");
        }
        b.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
        s.setName(CMAP_NAME, name);
        COSDictionary si = new COSDictionary();
        si.setString(COSName.REGISTRY, registry);
        si.setString(COSName.ORDERING, ordering);
        si.setInt(COSName.SUPPLEMENT, supplement);
        s.setItem(COSName.CIDSYSTEMINFO, si);
        s.setInt(WMODE, cmap.getWMode());
        try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(b.toString().getBytes(StandardCharsets.US_ASCII));
        }
        font.getCOSObject().setItem(COSName.ENCODING, s);
        return true;
    }

    private boolean moveCids(PDCIDFont cid, Map<Integer, Integer> remap, Map<Integer, Float> widths)
            throws IOException {
        COSDictionary d = cid.getCOSObject();
        if (!COSName.CID_FONT_TYPE2.equals(d.getCOSName(COSName.SUBTYPE))) {
            return false;
        }
        COSBase map = d.getDictionaryObject(COSName.CID_TO_GID_MAP);
        byte[] old = map instanceof COSStream s ? StreamFixer.read(s) : null;
        int max = 0;
        for (int to : remap.values()) {
            max = Math.max(max, to);
        }
        int oldMax = old == null ? MAX_CID : old.length / 2 - 1;
        byte[] out = new byte[(Math.max(Math.min(oldMax, MAX_CID), max) + 1) * 2];
        for (int c = 0; c * 2 + 1 < out.length; c++) {
            int gid = old == null ? c : c * 2 + 1 < old.length ? (old[2 * c] & 0xFF) << 8 | old[2 * c + 1] & 0xFF : 0;
            out[2 * c] = (byte) (gid >> 8);
            out[2 * c + 1] = (byte) gid;
        }
        for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
            int from = e.getKey();
            int gid = old != null && from * 2L + 1 < old.length ? (old[2 * from] & 0xFF) << 8 | old[2 * from + 1] & 0xFF : 0;
            out[2 * e.getValue()] = (byte) (gid >> 8);
            out[2 * e.getValue() + 1] = (byte) gid;
        }
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(out);
        }
        d.setItem(COSName.CID_TO_GID_MAP, s);
        COSArray w = ContentGraph.array(d.getDictionaryObject(COSName.W));
        if (w == null) {
            w = new COSArray();
            d.setItem(COSName.W, w);
        }
        for (Map.Entry<Integer, Integer> e : remap.entrySet()) {
            COSArray one = new COSArray();
            one.add(COSInteger.get(Math.round(widths.getOrDefault(e.getKey(), 0f))));
            w.add(COSInteger.get(e.getValue()));
            w.add(one);
        }
        COSDictionary fd = ContentGraph.dict(d.getDictionaryObject(COSName.FONT_DESC));
        if (fd != null && fd.getDictionaryObject(COSName.CID_SET) instanceof COSStream set) {
            byte[] bits = StreamFixer.read(set);
            byte[] grown = Arrays.copyOf(bits == null ? new byte[0] : bits, Math.max(bits == null ? 0
                    : bits.length, max / 8 + 1));
            for (int to : remap.values()) {
                grown[to / 8] |= (byte) (0x80 >> (to % 8));
            }
            COSStream fresh = doc.getDocument().createCOSStream();
            try (OutputStream o = fresh.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(grown);
            }
            fd.setItem(COSName.CID_SET, fresh);
        }
        return true;
    }

    private static List<int[]> codespaces(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher block = CODESPACE.matcher(text);
        while (block.find()) {
            Matcher m = HEX.matcher(block.group(1));
            while (m.find()) {
                if (m.group(1).length() == m.group(2).length() && m.group(1).length() <= 8) {
                    out.add(new int[] {(int) Long.parseLong(m.group(1), 16), (int) Long.parseLong(m.group(2), 16),
                            m.group(1).length() / 2});
                }
            }
        }
        return out;
    }

    private static int length(int code, List<int[]> spaces) {
        int best = 0;
        for (int[] sp : spaces) {
            if (inRange(code, sp) && (best == 0 || sp[2] < best)) {
                best = sp[2];
            }
        }
        if (best > 0) {
            return best;
        }
        return code > 0xFFFFFF ? 4 : code > 0xFFFF ? 3 : code > 0xFF ? 2 : spaces.get(0)[2];
    }

    private static boolean inRange(int code, int[] sp) {
        int n = sp[2];
        if (n < 4 && code >>> (8 * n) != 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            int shift = 8 * (n - 1 - i);
            int c = code >>> shift & 0xFF;
            if (c < (sp[0] >>> shift & 0xFF) || c > (sp[1] >>> shift & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String hex(int v, int bytes) {
        StringBuilder b = new StringBuilder();
        for (int i = bytes - 1; i >= 0; i--) {
            b.append(String.format("%02X", v >>> (8 * i) & 0xFF));
        }
        return b.toString();
    }

    private static String text(COSStream s) {
        byte[] b = StreamFixer.read(s);
        return b == null ? "" : new String(b, StandardCharsets.ISO_8859_1);
    }

    private static List<String> blocks(String text, String begin, String end) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while ((at = text.indexOf(begin, at)) >= 0) {
            int stop = text.indexOf(end, at);
            if (stop < 0) {
                break;
            }
            out.add(text.substring(at + begin.length(), stop));
            at = stop + end.length();
        }
        return out;
    }
}
