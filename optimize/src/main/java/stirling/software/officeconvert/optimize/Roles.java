package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

final class Roles {

    record Loc(int holder, Object[] path) {

        Loc child(Object step) {
            Object[] p = Arrays.copyOf(path, path.length + 1);
            p[path.length] = step;
            return new Loc(holder, p);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Loc l && l.holder == holder && Arrays.equals(l.path, path);
        }

        @Override
        public int hashCode() {
            return holder * 31 + Arrays.hashCode(path);
        }
    }

    record Context(int[] streams, Loc resources) {}

    private record Page(Loc kids, Loc contents, Loc resources, boolean leaf, boolean typed) {}

    private record Type3(Loc charProcs, Loc resources) {}

    private static final Object[] HERE = new Object[0];

    private static final COSName PATTERN_TYPE = COSName.getPDFName("PatternType");

    private static final COSName CHAR_PROCS = COSName.getPDFName("CharProcs");

    private static final COSName TYPE3 = COSName.getPDFName("Type3");

    private static final COSName BYTE_RANGE = COSName.getPDFName("ByteRange");

    private static final COSName PG = COSName.getPDFName("Pg");

    private static final COSName FT = COSName.getPDFName("FT");

    private static final Set<COSName> IDENTITY_TYPES = Set.of(COSName.CATALOG, COSName.PAGES, COSName.PAGE,
            COSName.ANNOT, COSName.STRUCT_ELEM, COSName.getPDFName("StructTreeRoot"), COSName.getPDFName("OBJR"),
            COSName.getPDFName("MCR"), COSName.OUTLINES, COSName.SIG, COSName.OCG, COSName.getPDFName("OCMD"),
            COSName.getPDFName("Thread"), COSName.getPDFName("Bead"), COSName.getPDFName("Template"),
            COSName.getPDFName("ObjStm"), COSName.XREF, COSName.getPDFName("DocTimeStamp"));

    private static final Set<COSName> IDENTITY_KEYS = Set.of(COSName.PARENT, COSName.P, COSName.KIDS, PG, FT,
            COSName.T, COSName.RECT, BYTE_RANGE);

    private static final Pattern PDFA_PART = Pattern.compile("pdfaid:part(?:\\s*=\\s*[\"']\\s*|\\s*>\\s*)(\\d)");

    private final Index index;

    private final Map<Integer, Page> pages = new LinkedHashMap<>();

    private final Map<Integer, Loc> streamResources = new HashMap<>();

    private final Set<Integer> forms = new HashSet<>();

    private final List<Type3> type3 = new ArrayList<>();

    private final List<Loc> appearances = new ArrayList<>();

    final Set<Integer> indirect = new HashSet<>();

    final List<Loc> resourceDicts = new ArrayList<>();

    final List<Context> contexts = new ArrayList<>();

    final Set<Integer> content = new HashSet<>();

    final Set<Integer> trueType = new HashSet<>();

    boolean fontPrograms;

    boolean signed;

    Roles(Index index) {
        this.index = index;
    }

    void collect(int id, COSBase object) {
        if (object instanceof COSArray) {
            index.flag(id, Index.VALUE_LIKE);
            index.group(id, "~array");
        }
        if (!(object instanceof COSDictionary top)) {
            walk(id, object);
            return;
        }
        if (valueLike(top)) {
            index.flag(id, Index.VALUE_LIKE);
        }
        COSName type = top.getCOSName(COSName.TYPE);
        COSName subtype = top.getCOSName(COSName.SUBTYPE);
        index.group(id, (type == null ? "~" : type.getName()) + "/" + (subtype == null ? "" : subtype.getName()));
        if (top instanceof COSStream s) {
            if (COSName.METADATA.equals(type)) {
                index.flag(id, Index.METADATA);
            } else if (COSName.IMAGE.equals(subtype)) {
                index.flag(id, Index.IMAGE);
            } else if (COSName.FORM.equals(subtype) || s.getItem(PATTERN_TYPE) instanceof org.apache.pdfbox.cos.COSInteger
                    pt && pt.intValue() == 1) {
                forms.add(id);
            }
            Loc res = ref(new Loc(id, HERE), s, COSName.RESOURCES);
            if (res != null) {
                streamResources.put(id, res);
            }
        } else if (COSName.PAGE.equals(type) || COSName.PAGES.equals(type) || top.containsKey(COSName.KIDS)
                && !top.containsKey(COSName.NAMES) && !top.containsKey(COSName.NUMS)) {
            page(id, top);
        }
        walk(id, object);
    }

    void page(int id, COSDictionary node) {
        Loc here = new Loc(id, HERE);
        COSName type = node.getCOSName(COSName.TYPE);
        pages.put(id, new Page(ref(here, node, COSName.KIDS), ref(here, node, COSName.CONTENTS),
                ref(here, node, COSName.RESOURCES), COSName.PAGE.equals(type) || !node.containsKey(COSName.KIDS),
                COSName.PAGE.equals(type) && !node.containsKey(COSName.KIDS)));
    }

    List<Integer> pageObjects() {
        List<Integer> found = new ArrayList<>();
        for (Map.Entry<Integer, Page> e : pages.entrySet()) {
            if (e.getValue().typed) {
                found.add(e.getKey());
            }
        }
        return found;
    }

    private Loc ref(Loc at, COSDictionary d, COSName key) {
        COSBase v = d.getItem(key);
        if (v instanceof COSObject o && o.getKey() != null) {
            return new Loc(index.id(o.getKey()), HERE);
        }
        return v == null || v instanceof org.apache.pdfbox.cos.COSNull ? null : at.child(key);
    }

    private void walk(int id, COSBase object) {
        ArrayDeque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {object, new Loc(id, HERE), 0});
        while (!stack.isEmpty()) {
            Object[] f = stack.pop();
            COSBase b = (COSBase) f[0];
            Loc at = (Loc) f[1];
            int depth = (Integer) f[2];
            if (b instanceof COSDictionary d) {
                dictionary(at, d);
                if (depth < 64) {
                    for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
                        if (e.getValue() instanceof COSDictionary || e.getValue() instanceof COSArray) {
                            stack.push(new Object[] {e.getValue(), at.child(e.getKey()), depth + 1});
                        }
                    }
                }
            } else if (b instanceof COSArray a && depth < 64) {
                for (int i = 0; i < a.size(); i++) {
                    COSBase v = a.get(i);
                    if (v instanceof COSDictionary || v instanceof COSArray) {
                        stack.push(new Object[] {v, at.child(i), depth + 1});
                    }
                }
            }
        }
    }

    private void dictionary(Loc at, COSDictionary d) {
        if (d.getItem(COSName.KIDS) instanceof COSArray kids) {
            for (int i = 0; i < kids.size(); i++) {
                if (kids.get(i) instanceof COSObject o && o.getKey() != null) {
                    indirect.add(index.id(o.getKey()));
                }
            }
        }
        if (d.getItem(COSName.FONT_DESC) instanceof COSObject o && o.getKey() != null) {
            indirect.add(index.id(o.getKey()));
        }
        if (d.getItem(COSName.FONT_FILE2) instanceof COSObject o && o.getKey() != null) {
            trueType.add(index.id(o.getKey()));
            fontPrograms = true;
        }
        fontPrograms |= d.getItem(COSName.FONT_FILE) instanceof COSObject
                || d.getItem(COSName.FONT_FILE3) instanceof COSObject;
        Loc res = ref(at, d, COSName.RESOURCES);
        if (res != null) {
            resourceDicts.add(res);
        }
        if (COSName.SIG.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(BYTE_RANGE)
                && d.containsKey(COSName.CONTENTS)) {
            signed = true;
        }
        if (TYPE3.equals(d.getCOSName(COSName.SUBTYPE)) && d.containsKey(CHAR_PROCS)) {
            type3.add(new Type3(ref(at, d, CHAR_PROCS), res));
        }
        Loc ap = ref(at, d, COSName.AP);
        if (ap != null) {
            if (ap.path.length == 0) {
                indirect.add(ap.holder);
            }
            appearances.add(ap);
        }
    }

    void resolve(Resolver r, int root) throws IOException {
        Loc pagesLoc = new Loc(root, new Object[] {COSName.PAGES});
        Resolver.Found top = r.find(pagesLoc);
        if (top != null && top.loc().path.length == 0) {
            walkPages(r, top.loc().holder);
        }
        for (int f : forms) {
            context(f, streamResources.get(f));
        }
        for (Type3 t : type3) {
            Resolver.Found procs = t.charProcs == null ? null : r.find(t.charProcs);
            if (procs != null && procs.value() instanceof COSDictionary d) {
                for (COSBase v : d.getValues()) {
                    if (v instanceof COSObject o && o.getKey() != null) {
                        context(index.id(o.getKey()), t.resources);
                    }
                }
            }
        }
        for (Loc ap : appearances) {
            Resolver.Found found = r.find(ap);
            if (found == null || !(found.value() instanceof COSDictionary d)) {
                continue;
            }
            for (COSName key : List.of(COSName.N, COSName.R, COSName.D)) {
                COSBase v = d.getItem(key);
                if (v instanceof COSObject o && o.getKey() != null) {
                    int id = index.id(o.getKey());
                    if (index.kind(id) == Index.STREAM) {
                        appearance(id);
                        continue;
                    }
                    indirect.add(id);
                    v = r.load(id);
                }
                if (v instanceof COSDictionary states && !(v instanceof COSStream)) {
                    for (COSBase state : states.getValues()) {
                        if (state instanceof COSObject so && so.getKey() != null) {
                            int sid = index.id(so.getKey());
                            if (index.kind(sid) == Index.STREAM) {
                                appearance(sid);
                            }
                        }
                    }
                }
            }
        }
    }

    private void appearance(int stream) {
        if (!content.contains(stream)) {
            context(stream, streamResources.get(stream));
        }
    }

    private void context(int stream, Loc resources) {
        if (index.kind(stream) != Index.STREAM) {
            return;
        }
        content.add(stream);
        contexts.add(new Context(new int[] {stream}, resources));
    }

    private void walkPages(Resolver r, int top) throws IOException {
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> nodes = new ArrayDeque<>();
        ArrayDeque<Loc> inherited = new ArrayDeque<>();
        Loc none = new Loc(-1, HERE);
        nodes.push(top);
        inherited.push(none);
        while (!nodes.isEmpty()) {
            int node = nodes.pop();
            Loc parent = inherited.pop();
            Page p = pages.get(node);
            if (p == null || !seen.add(node)) {
                continue;
            }
            Loc res = p.resources != null ? p.resources : parent;
            if (!p.leaf) {
                Resolver.Found kids = p.kids == null ? null : r.find(p.kids);
                if (kids != null && kids.value() instanceof COSArray a) {
                    for (int i = a.size() - 1; i >= 0; i--) {
                        if (a.get(i) instanceof COSObject o && o.getKey() != null) {
                            nodes.push(index.id(o.getKey()));
                            inherited.push(res);
                        }
                    }
                }
                continue;
            }
            Loc pageRes = res == none ? null : res;
            Resolver.Found contents = p.contents == null ? null : r.find(p.contents);
            if (contents == null) {
                continue;
            }
            if (contents.loc().path.length == 0 && index.kind(contents.loc().holder) == Index.STREAM) {
                context(contents.loc().holder, pageRes);
            } else if (contents.value() instanceof COSArray a) {
                int[] streams = new int[a.size()];
                boolean whole = true;
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i) instanceof COSObject o && o.getKey() != null
                            && index.kind(index.id(o.getKey())) == Index.STREAM) {
                        streams[i] = index.id(o.getKey());
                        content.add(streams[i]);
                    } else {
                        whole = false;
                    }
                }
                if (whole && streams.length > 0) {
                    contexts.add(new Context(streams, pageRes));
                }
            }
        }
    }

    static boolean valueLike(COSBase b) {
        if (b instanceof COSArray) {
            return true;
        }
        if (!(b instanceof COSDictionary d)) {
            return false;
        }
        if (b instanceof COSStream) {
            return true;
        }
        COSName type = d.getCOSName(COSName.TYPE);
        if (type != null && IDENTITY_TYPES.contains(type)) {
            return false;
        }
        for (COSName key : IDENTITY_KEYS) {
            if (d.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    static int pdfaPart(COSDictionary catalog) {
        if (!(catalog.getDictionaryObject(COSName.METADATA) instanceof COSStream s)) {
            return 0;
        }
        try (InputStream in = s.createInputStream()) {
            String xmp = new String(in.readNBytes(4 << 20), StandardCharsets.UTF_8);
            Matcher m = PDFA_PART.matcher(xmp);
            return m.find() ? m.group(1).charAt(0) - '0' : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static Predictors.Layout layout(COSStream s) {
        if (!COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
            return null;
        }
        int width = s.getInt(COSName.WIDTH, s.getInt(COSName.W, -1));
        if (width < 1 || width > 1 << 24) {
            return null;
        }
        if (s.getBoolean(COSName.IMAGE_MASK, s.getBoolean(COSName.IM, false))) {
            return new Predictors.Layout(1, 1, width);
        }
        int bpc = s.getInt(COSName.BITS_PER_COMPONENT, s.getInt(COSName.BPC, -1));
        int colors = components(s.getDictionaryObject(COSName.COLORSPACE, COSName.CS), 0);
        if (colors < 1 || bpc != 1 && bpc != 2 && bpc != 4 && bpc != 8 && bpc != 16) {
            return null;
        }
        return new Predictors.Layout(colors, bpc, width);
    }

    private static int components(COSBase cs, int depth) {
        if (depth > 4) {
            return -1;
        }
        if (cs instanceof COSName n) {
            return switch (n.getName()) {
                case "DeviceGray", "CalGray", "G", "Indexed", "I", "Separation" -> 1;
                case "DeviceRGB", "CalRGB", "RGB", "Lab" -> 3;
                case "DeviceCMYK", "CMYK" -> 4;
                default -> -1;
            };
        }
        if (!(cs instanceof COSArray a) || a.size() == 0 || !(a.getObject(0) instanceof COSName family)) {
            return -1;
        }
        return switch (family.getName()) {
            case "ICCBased" -> a.size() > 1 && a.getObject(1) instanceof COSStream icc ? icc.getInt(COSName.N, -1) : -1;
            case "DeviceN" -> a.size() > 1 && a.getObject(1) instanceof COSArray names ? names.size() : -1;
            case "Indexed", "I", "Separation", "CalGray" -> 1;
            case "CalRGB", "Lab" -> 3;
            default -> a.size() == 1 ? components(family, depth + 1) : -1;
        };
    }
}
