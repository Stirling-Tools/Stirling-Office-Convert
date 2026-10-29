package stirling.software.officeconvert.optimize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.Encoding;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.font.encoding.MacOSRomanEncoding;
import org.apache.pdfbox.pdmodel.font.encoding.StandardEncoding;

import stirling.software.officeconvert.extract.BrotliBudget;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.fonts.CffSubset;
import stirling.software.officeconvert.fonts.CodeReader;
import stirling.software.officeconvert.fonts.Type1Subset;
import stirling.software.officeconvert.fonts.Type1ToCff;

final class Fonts {

    static final int MAX_CODES_PER_FONT = 70_000;

    private static final COSName TYPE1C = COSName.getPDFName("Type1C");

    private static final COSName CID_FONT_TYPE0C = COSName.getPDFName("CIDFontType0C");

    private static final COSName DL = COSName.getPDFName("DL");

    private static final List<COSName> PROGRAM_KEYS = List.of(COSName.FONT_FILE2, COSName.FONT_FILE,
            COSName.FONT_FILE3);

    private static final int[] SYMBOL_RANGES = {0, 0xF000, 0xF100, 0xF200};

    private static final Map<String, Integer> MAC_ROMAN = new HashMap<>();

    static {
        MacOSRomanEncoding.INSTANCE.getCodeToNameMap().forEach((code, name) -> MAC_ROMAN.putIfAbsent(name, code));
    }

    private record User(int font, Roles.Loc descriptor, List<Integer> chain) {}

    private record Program(COSName key, List<User> users) {}

    private record Chain(int program, COSName key, Roles.Loc descriptor, List<Integer> objects) {}

    private record Step(Roles.Loc at, COSBase value) {}

    private final Index index;

    private final Source source;

    private final Recorder recorder;

    private final Map<Integer, List<Cleanup.Removal>> removals;

    private final BrotliBudget budget;

    private final Map<Integer, Set<Integer>> codes = new HashMap<>();

    private final Map<Integer, CodeReader> readers = new HashMap<>();

    private final Set<Integer> overflowing = new HashSet<>();

    private final Map<Integer, Program> programs = new HashMap<>();

    private Fonts(Index index, Source source, Recorder recorder, Map<Integer, List<Cleanup.Removal>> removals,
            BrotliBudget budget) {
        this.index = index;
        this.source = source;
        this.recorder = recorder;
        this.removals = removals;
        this.budget = budget;
    }

    static Fonts none() {
        return new Fonts(null, null, null, Map.of(), null);
    }

    static Fonts of(Roles roles, Resolver resolver, Source source, Index index, Roles.Loc dr, Recorder recorder,
            Map<Integer, List<Cleanup.Removal>> removals, BrotliBudget budget) throws IOException {
        if (!roles.fontPrograms) {
            return none();
        }
        Fonts fonts = new Fonts(index, source, recorder, removals, budget);
        try {
            Map<Integer, Integer> accounted = fonts.usage(roles, resolver, dr);
            if (accounted != null) {
                fonts.plan(accounted);
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            fonts.programs.clear();
        }
        fonts.codes.keySet().retainAll(fonts.users());
        fonts.readers.clear();
        return fonts;
    }

    private Set<Integer> users() {
        Set<Integer> ids = new HashSet<>();
        for (Program p : programs.values()) {
            for (User u : p.users) {
                ids.add(u.font);
            }
        }
        return ids;
    }

    private Map<Integer, Integer> usage(Roles roles, Resolver resolver, Roles.Loc dr) throws IOException {
        Map<Roles.Loc, Map<COSName, Integer>> subs = new HashMap<>();
        Set<Roles.Loc> known = new HashSet<>();
        int count = 0;
        for (Roles.Context c : roles.contexts) {
            if ((++count & 63) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            Resolver.Found r = c.resources() == null ? null : resolver.find(c.resources());
            COSDictionary res = r != null && r.value() instanceof COSDictionary d ? d : null;
            Cleanup.Sub sub = null;
            if (res != null) {
                known.add(r.loc());
                sub = Cleanup.sub(resolver, index, r.loc(), res, COSName.FONT);
            }
            Map<COSName, Integer> names = sub == null ? Map.of() : names(sub);
            byte[] data = Cleanup.content(source, index, c.streams(), budget);
            if (data == null) {
                return null;
            }
            Set<COSName> states = new HashSet<>();
            boolean read = Content.text(data, (name, text) -> {
                Integer id = names.get(name);
                return id != null && record(id, text);
            }, states);
            if (!read || setsFont(res, states)) {
                return null;
            }
            if (sub != null) {
                subs.putIfAbsent(sub.loc(), names);
            }
        }
        List<Roles.Loc> outside = new ArrayList<>();
        if (dr != null) {
            outside.add(dr);
        }
        outside.addAll(roles.resourceDicts);
        for (Roles.Loc loc : outside) {
            Resolver.Found r = resolver.find(loc);
            if (r == null || !(r.value() instanceof COSDictionary res) || known.contains(r.loc()) && loc != dr) {
                continue;
            }
            Cleanup.Sub sub = Cleanup.sub(resolver, index, r.loc(), res, COSName.FONT);
            if (sub != null) {
                subs.remove(sub.loc());
            }
        }
        Map<Integer, Integer> accounted = new HashMap<>();
        for (Map<COSName, Integer> names : subs.values()) {
            for (int id : names.values()) {
                accounted.merge(id, 1, Integer::sum);
            }
        }
        return accounted;
    }

    private Map<COSName, Integer> names(Cleanup.Sub sub) {
        Set<COSName> removed = new HashSet<>();
        for (Cleanup.Removal r : removals.getOrDefault(sub.loc().holder(), List.of())) {
            if (Arrays.equals(r.path(), sub.loc().path())) {
                removed.addAll(r.keys());
            }
        }
        Map<COSName, Integer> names = new HashMap<>();
        for (Map.Entry<COSName, COSBase> e : sub.dict().entrySet()) {
            if (!removed.contains(e.getKey()) && e.getValue() instanceof COSObject o && o.getKey() != null) {
                names.put(e.getKey(), index.id(o.getKey()));
            }
        }
        return names;
    }

    private static boolean setsFont(COSDictionary res, Set<COSName> states) {
        if (states.isEmpty()) {
            return false;
        }
        if (res == null || !(res.getDictionaryObject(COSName.EXT_G_STATE) instanceof COSDictionary all)) {
            return true;
        }
        for (COSName name : states) {
            if (!(all.getDictionaryObject(name) instanceof COSDictionary gs) || gs.containsKey(COSName.FONT)) {
                return true;
            }
        }
        return false;
    }

    private boolean record(int id, byte[] text) {
        Set<Integer> set = codes.computeIfAbsent(id, k -> new HashSet<>());
        if (set.size() >= MAX_CODES_PER_FONT) {
            overflowing.add(id);
            return true;
        }
        CodeReader reader = readers.get(id);
        if (reader == null) {
            if (!(source(id) instanceof COSDictionary font) || font instanceof COSStream) {
                return false;
            }
            reader = CodeReader.of(font);
            readers.put(id, reader);
        }
        if (!reader.multiByte()) {
            for (byte b : text) {
                set.add(b & 0xFF);
            }
            return true;
        }
        ByteArrayInputStream in = new ByteArrayInputStream(text);
        while (in.available() > 0) {
            try {
                set.add(reader.read(in));
            } catch (IOException e) {
                return true;
            }
        }
        return true;
    }

    private COSBase source(int id) {
        try {
            return source.load(index.key(id));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void plan(Map<Integer, Integer> accounted) throws IOException {
        Map<Integer, Integer> total = new HashMap<>();
        for (int id : accounted.keySet()) {
            total.put(id, 0);
        }
        references(total.keySet(), (from, to) -> total.merge(to, 1, Integer::sum));
        Map<Integer, List<User>> byProgram = new LinkedHashMap<>();
        Map<Integer, COSName> keys = new HashMap<>();
        Set<Integer> mixed = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : accounted.entrySet()) {
            PdfFiles.stopIfInterrupted();
            int id = e.getKey();
            Set<Integer> used = codes.get(id);
            if (!e.getValue().equals(total.get(id)) || used == null || used.isEmpty() || overflowing.contains(id)) {
                continue;
            }
            Chain found = chain(id);
            if (found == null) {
                continue;
            }
            int program = found.program();
            COSName key = found.key();
            User user = new User(id, found.descriptor(), List.copyOf(found.objects()));
            COSName before = keys.putIfAbsent(program, key);
            if (before != null && !before.equals(key)) {
                mixed.add(program);
            }
            byProgram.computeIfAbsent(program, k -> new ArrayList<>()).add(user);
        }
        byProgram.keySet().removeAll(mixed);
        Set<Integer> watched = new HashSet<>(byProgram.keySet());
        for (List<User> users : byProgram.values()) {
            for (User u : users) {
                watched.addAll(u.chain.subList(1, u.chain.size()));
            }
        }
        Map<Integer, List<Integer>> referrers = new HashMap<>();
        references(watched, (from, to) -> referrers.computeIfAbsent(to, k -> new ArrayList<>()).add(from));
        for (Map.Entry<Integer, List<User>> e : byProgram.entrySet()) {
            Set<Integer> allowed = new HashSet<>();
            Set<Integer> inner = new HashSet<>();
            inner.add(e.getKey());
            for (User u : e.getValue()) {
                allowed.addAll(u.chain);
                inner.addAll(u.chain.subList(1, u.chain.size()));
            }
            boolean closed = true;
            for (int id : inner) {
                for (int from : referrers.getOrDefault(id, List.of())) {
                    closed &= allowed.contains(from);
                }
            }
            if (closed) {
                programs.put(e.getKey(), new Program(keys.get(e.getKey()), e.getValue()));
            }
        }
    }

    private interface Reference {
        void found(int from, int to);
    }

    private void references(Set<Integer> targets, Reference sink) throws IOException {
        int n = index.size();
        for (int i = 0; i < n; i++) {
            if ((i & 4095) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            int holes = index.holeCount(i);
            for (int k = 0; k < holes; k++) {
                int to = index.holeRef(i, k);
                if (to >= 0 && targets.contains(to)) {
                    sink.found(i, to);
                }
            }
        }
    }

    private Chain chain(int font) throws IOException {
        List<Integer> chain = new ArrayList<>();
        chain.add(font);
        if (!(source(font) instanceof COSDictionary top) || top instanceof COSStream) {
            return null;
        }
        Roles.Loc at = new Roles.Loc(font, new Object[0]);
        COSDictionary owner = top;
        COSName subtype = top.getCOSName(COSName.SUBTYPE);
        if (COSName.TYPE0.equals(subtype)) {
            Step step = step(at, top, COSName.DESCENDANT_FONTS, chain);
            if (step == null || !(step.value() instanceof COSArray kids) || kids.size() != 1) {
                return null;
            }
            Roles.Loc kidsAt = step.at();
            COSBase kid = kids.get(0);
            if (kid instanceof COSObject o && o.getKey() != null) {
                int id = index.id(o.getKey());
                chain.add(id);
                at = new Roles.Loc(id, new Object[0]);
                kid = source(id);
            } else {
                at = kidsAt.child(0);
            }
            if (!(kid instanceof COSDictionary d) || kid instanceof COSStream) {
                return null;
            }
            owner = d;
        } else if (!COSName.TRUE_TYPE.equals(subtype) && !COSName.TYPE1.equals(subtype)) {
            return null;
        }
        Step step = step(at, owner, COSName.FONT_DESC, chain);
        if (step == null || !(step.value() instanceof COSDictionary fd) || fd instanceof COSStream) {
            return null;
        }
        COSName key = null;
        int program = -1;
        for (COSName k : PROGRAM_KEYS) {
            if (fd.getItem(k) instanceof COSObject o && o.getKey() != null) {
                if (key != null) {
                    return null;
                }
                key = k;
                program = index.id(o.getKey());
            } else if (fd.containsKey(k)) {
                return null;
            }
        }
        if (key == null || index.kind(program) != Index.STREAM) {
            return null;
        }
        return new Chain(program, key, step.at(), chain);
    }

    private Step step(Roles.Loc at, COSDictionary d, COSName key, List<Integer> chain) {
        COSBase v = d.getItem(key);
        if (v instanceof COSObject o && o.getKey() != null) {
            int id = index.id(o.getKey());
            chain.add(id);
            return new Step(new Roles.Loc(id, new Object[0]), source(id));
        }
        return v == null ? null : new Step(at.child(key), v);
    }

    COSStream rebuild(int id, COSStream stream, PdfOptimizer.Effort effort) throws IOException {
        Program p = programs.remove(id);
        if (p == null) {
            return null;
        }
        try {
            return build(p, stream, effort);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            PdfFiles.stopIfInterrupted();
            return null;
        } finally {
            for (User u : p.users) {
                codes.remove(u.font);
            }
        }
    }

    private COSStream build(Program p, COSStream stream, PdfOptimizer.Effort effort) throws IOException {
        byte[] data = Recoder.decoded(stream, budget);
        if (data == null) {
            return null;
        }
        List<PDFont> fonts = new ArrayList<>();
        for (User u : p.users) {
            if (!(source(u.font) instanceof COSDictionary d)) {
                return null;
            }
            fonts.add(PDFontFactory.createFont(d));
        }
        Map<COSName, COSBase> set = new LinkedHashMap<>();
        byte[] out;
        boolean cff = false;
        if (COSName.FONT_FILE2.equals(p.key)) {
            out = trueType(p, fonts, data);
            if (out != null) {
                set.put(COSName.LENGTH1, COSInteger.get(out.length));
            }
        } else if (COSName.FONT_FILE3.equals(p.key)) {
            out = cff(p, fonts, data, stream.getCOSName(COSName.SUBTYPE));
        } else {
            Type1 t = type1(p, fonts, data, stream, effort);
            out = t == null ? null : t.data;
            cff = t != null && t.cff;
            if (t != null && !cff) {
                set.put(COSName.LENGTH1, COSInteger.get(t.lengths[0]));
                set.put(COSName.LENGTH2, COSInteger.get(t.lengths[1]));
                set.put(COSName.LENGTH3, COSInteger.get(t.lengths[2]));
            }
        }
        if (out == null || !smaller(out, data, effort)) {
            return null;
        }
        COSStream rebuilt = new COSStream();
        Set<COSName> drop = Set.of(COSName.FILTER, COSName.DECODE_PARMS, DL, COSName.LENGTH, COSName.LENGTH1,
                COSName.LENGTH2, COSName.LENGTH3);
        for (Map.Entry<COSName, COSBase> e : stream.entrySet()) {
            if (!drop.contains(e.getKey()) && !(cff && COSName.SUBTYPE.equals(e.getKey()))) {
                rebuilt.setItem(e.getKey(), e.getValue());
            }
        }
        if (cff) {
            rebuilt.setItem(COSName.SUBTYPE, TYPE1C);
        }
        set.forEach(rebuilt::setItem);
        try (OutputStream o = rebuilt.createRawOutputStream()) {
            o.write(out);
        }
        if (cff) {
            rename(p.users);
        }
        return rebuilt;
    }

    private static boolean smaller(byte[] rebuilt, byte[] original, PdfOptimizer.Effort effort) {
        return Deflate.baseline(rebuilt, effort).length < Deflate.baseline(original, effort).length;
    }

    private void rename(List<User> users) throws IOException {
        Map<Integer, List<Object[]>> byHolder = new LinkedHashMap<>();
        for (User u : users) {
            List<Object[]> paths = byHolder.computeIfAbsent(u.descriptor.holder(), k -> new ArrayList<>());
            if (paths.stream().noneMatch(path -> Arrays.equals(path, u.descriptor.path()))) {
                paths.add(u.descriptor.path());
            }
        }
        for (Map.Entry<Integer, List<Object[]>> e : byHolder.entrySet()) {
            int holder = e.getKey();
            COSBase fresh = source.load(index.key(holder));
            for (Object[] path : e.getValue()) {
                COSBase v = fresh;
                for (Object step : path) {
                    v = step instanceof COSName k && v instanceof COSDictionary d ? d.getItem(k)
                            : step instanceof Integer i && v instanceof COSArray a && i < a.size() ? a.get(i) : null;
                }
                if (v instanceof COSDictionary fd) {
                    COSBase program = fd.getItem(COSName.FONT_FILE);
                    fd.removeItem(COSName.FONT_FILE);
                    fd.setItem(COSName.FONT_FILE3, program);
                }
            }
            List<Cleanup.Removal> drop = removals.get(holder);
            if (drop != null) {
                Cleanup.apply(fresh, drop);
            }
            recorder.record(holder, fresh, null);
        }
    }

    private byte[] trueType(Program p, List<PDFont> fonts, byte[] data) throws IOException {
        BitSet keep = new BitSet();
        boolean simple = false;
        for (int i = 0; i < fonts.size(); i++) {
            PDFont f = fonts.get(i);
            Set<Integer> used = codes.get(p.users.get(i).font);
            if (f instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType2 c) {
                for (int code : used) {
                    add(keep, c.codeToGID(code));
                    add(keep, c.codeToCID(code));
                }
            } else if (f instanceof PDTrueTypeFont t) {
                simple = true;
                simpleGlyphs(t, used, keep);
            } else {
                return null;
            }
        }
        return Sfnt.subset(data, keep, simple);
    }

    private static void add(BitSet keep, int gid) {
        if (gid >= 0 && gid < 0x10000) {
            keep.set(gid);
        }
    }

    private static void simpleGlyphs(PDTrueTypeFont font, Set<Integer> used, BitSet keep) throws IOException {
        TrueTypeFont ttf = font.getTrueTypeFont();
        CmapTable table = ttf.getCmap();
        CmapSubtable[] subs = table == null ? new CmapSubtable[0] : table.getCmaps();
        Encoding enc = font.getEncoding();
        for (int code : used) {
            Set<Integer> keys = new HashSet<>();
            for (int base : SYMBOL_RANGES) {
                keys.add(base + code);
            }
            String name = enc == null ? null : enc.getName(code);
            if (name != null && !".notdef".equals(name)) {
                String u = GlyphList.getAdobeGlyphList().toUnicode(name);
                if (u != null && !u.isEmpty()) {
                    keys.add(u.codePointAt(0));
                }
                Integer mac = MAC_ROMAN.get(name);
                if (mac != null) {
                    keys.add(mac);
                }
                add(keep, ttf.nameToGID(name));
            }
            for (CmapSubtable s : subs) {
                for (int k : keys) {
                    add(keep, s.getGlyphId(k));
                }
            }
            add(keep, code);
            add(keep, font.codeToGID(code));
        }
    }

    private byte[] cff(Program p, List<PDFont> fonts, byte[] data, COSName subtype) throws IOException {
        Set<Integer> gids = new TreeSet<>();
        boolean simple = TYPE1C.equals(subtype);
        if (!simple && !CID_FONT_TYPE0C.equals(subtype)) {
            return null;
        }
        CFFType1Font first = null;
        for (int i = 0; i < fonts.size(); i++) {
            PDFont f = fonts.get(i);
            Set<Integer> used = codes.get(p.users.get(i).font);
            if (simple && f instanceof PDType1CFont c && c.getCFFType1Font() != null) {
                CFFType1Font font = c.getCFFType1Font();
                first = first == null ? font : first;
                for (int code : used) {
                    gids.add(font.nameToGID(c.codeToName(code)));
                    String builtin = font.getEncoding() == null ? null : font.getEncoding().getName(code);
                    if (builtin != null) {
                        gids.add(font.nameToGID(builtin));
                    }
                }
            } else if (!simple && f instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFontType0 cid
                    && cid.getCFFFont() != null) {
                for (int code : used) {
                    gids.add(cid.codeToGID(code));
                }
            } else {
                return null;
            }
        }
        if (simple) {
            List<Integer> accents = CffSubset.seacComponents(data, gids);
            if (accents == null) {
                return null;
            }
            for (int code : accents) {
                String name = StandardEncoding.INSTANCE.getName(code);
                if (name != null) {
                    gids.add(first.nameToGID(name));
                }
            }
        }
        return CffSubset.hollow(data, gids);
    }

    private record Type1(byte[] data, boolean cff, int[] lengths) {}

    private Type1 type1(Program p, List<PDFont> fonts, byte[] data, COSStream stream, PdfOptimizer.Effort effort)
            throws IOException {
        Map<Integer, String> names = new TreeMap<>();
        Set<String> used = new HashSet<>();
        boolean consistent = true;
        Type1Font font = null;
        for (int i = 0; i < fonts.size(); i++) {
            if (!(fonts.get(i) instanceof PDType1Font t1) || !t1.isEmbedded() || t1.getType1Font() == null) {
                return null;
            }
            font = font == null ? t1.getType1Font() : font;
            org.apache.fontbox.encoding.Encoding builtin = t1.getType1Font().getEncoding();
            for (int code : codes.get(p.users.get(i).font)) {
                String name = t1.codeToName(code);
                used.add(name);
                String before = names.putIfAbsent(code, name);
                consistent &= before == null || before.equals(name);
                String own = builtin == null ? null : builtin.getName(code);
                if (own != null) {
                    used.add(own);
                }
            }
        }
        Type1 best = null;
        Type1Subset.Program hollow = Type1Subset.hollow(data, stream.getInt(COSName.LENGTH1),
                stream.getInt(COSName.LENGTH2), used);
        if (hollow != null) {
            best = new Type1(hollow.data(), false, new int[] {hollow.length1(), hollow.length2(), hollow.length3()});
        }
        byte[] converted = consistent ? Type1ToCff.convert(font, names, used) : null;
        if (converted != null && (best == null || Deflate.baseline(converted, effort).length
                <= Deflate.baseline(best.data, effort).length)) {
            best = new Type1(converted, true, null);
        }
        return best;
    }
}
