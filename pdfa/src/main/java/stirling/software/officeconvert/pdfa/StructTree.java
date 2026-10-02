package stirling.software.officeconvert.pdfa;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;

import stirling.software.officeconvert.extract.PdfFiles;

final class StructTree {

    record Mcr(COSDictionary owner, int mcid, COSDictionary element) {}

    record Objr(COSDictionary object, COSDictionary element) {}

    private static final COSName ROLE_MAP = COSName.getPDFName("RoleMap");

    private static final COSName MCR = COSName.getPDFName("MCR");

    private static final COSName OBJR = COSName.getPDFName("OBJR");

    private static final COSName STM = COSName.getPDFName("Stm");

    private static final COSName OBJ = COSName.getPDFName("Obj");

    private static final COSName ALT = COSName.getPDFName("Alt");

    private static final COSName ACTUAL_TEXT = COSName.getPDFName("ActualText");

    private static final COSName PARENT_TREE_NEXT_KEY = COSName.getPDFName("ParentTreeNextKey");

    private static final int MAX_ELEMENTS = 2_000_000;

    final List<Mcr> mcrs = new ArrayList<>();

    final List<Objr> objrs = new ArrayList<>();

    final List<COSDictionary> figuresWithoutAlt = new ArrayList<>();

    final Map<String, Integer> languages = new HashMap<>();

    private final COSDictionary root;

    private final COSDictionary roles;

    private StructTree(COSDictionary root) {
        this.root = root;
        this.roles = ContentGraph.dict(root.getDictionaryObject(ROLE_MAP));
    }

    static StructTree read(COSDictionary root) throws InterruptedIOException {
        StructTree t = new StructTree(root);
        t.walk();
        return t;
    }

    String type(COSDictionary element) {
        String t = element.getDictionaryObject(COSName.S) instanceof COSName n ? n.getName() : "";
        Set<String> seen = new HashSet<>();
        while (!Tagging.STANDARD.contains(t) && roles != null && seen.add(t)
                && roles.getDictionaryObject(COSName.getPDFName(t)) instanceof COSName next) {
            t = next.getName();
        }
        return t;
    }

    private void walk() throws InterruptedIOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {root.getDictionaryObject(COSName.K), null, null});
        while (!stack.isEmpty() && seen.size() < MAX_ELEMENTS) {
            Object[] top = stack.pop();
            COSBase b = (COSBase) top[0];
            COSDictionary parent = (COSDictionary) top[1];
            COSDictionary page = (COSDictionary) top[2];
            if ((seen.size() & 0xFFF) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            if (b instanceof COSArray a) {
                if (!seen.add(a)) {
                    continue;
                }
                for (int i = a.size() - 1; i >= 0; i--) {
                    stack.push(new Object[] {a.getObject(i), parent, page});
                }
                continue;
            }
            if (b instanceof COSNumber n && parent != null) {
                if (page != null) {
                    mcrs.add(new Mcr(page, n.intValue(), parent));
                }
                continue;
            }
            COSDictionary d = ContentGraph.dict(b);
            if (d == null || !seen.add(d)) {
                continue;
            }
            COSDictionary pg = ContentGraph.dict(d.getDictionaryObject(COSName.PG));
            COSDictionary here = pg != null ? pg : page;
            if (MCR.equals(d.getCOSName(COSName.TYPE))) {
                COSDictionary owner = d.getDictionaryObject(STM) instanceof COSStream s ? s : here;
                if (parent != null && owner != null && d.getDictionaryObject(COSName.MCID) instanceof COSNumber n) {
                    mcrs.add(new Mcr(owner, n.intValue(), parent));
                }
                continue;
            }
            if (OBJR.equals(d.getCOSName(COSName.TYPE))) {
                COSDictionary obj = ContentGraph.dict(d.getDictionaryObject(OBJ));
                if (parent != null && obj != null) {
                    objrs.add(new Objr(obj, parent));
                }
                continue;
            }
            if (d.getDictionaryObject(COSName.S) == null) {
                continue;
            }
            String type = type(d);
            if ("Figure".equals(type) && !text(d, ALT) && !text(d, ACTUAL_TEXT)) {
                figuresWithoutAlt.add(d);
            }
            if (d.getDictionaryObject(COSName.LANG) instanceof COSString s && !s.getString().isBlank()) {
                languages.merge(s.getString().strip(), 1, Integer::sum);
            }
            COSBase k = d.getDictionaryObject(COSName.K);
            if (k != null) {
                stack.push(new Object[] {k, d, here});
            }
        }
    }

    private static boolean text(COSDictionary d, COSName key) {
        return d.getDictionaryObject(key) instanceof COSString s && !s.getString().isBlank();
    }

    String rootLanguage() {
        COSBase k = root.getDictionaryObject(COSName.K);
        COSDictionary first = k instanceof COSArray a && a.size() == 1 ? ContentGraph.dict(a.getObject(0))
                : ContentGraph.dict(k);
        if (first != null && first.getDictionaryObject(COSName.LANG) instanceof COSString s && !s.getString().isBlank()) {
            return s.getString().strip();
        }
        return languages.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    Map<COSDictionary, Set<Integer>> mcidsByOwner() {
        Map<COSDictionary, Set<Integer>> out = new IdentityHashMap<>();
        for (Mcr m : mcrs) {
            out.computeIfAbsent(m.owner(), k -> new HashSet<>()).add(m.mcid());
        }
        return out;
    }

    boolean parentTreeMatches() throws InterruptedIOException {
        COSDictionary pt = ContentGraph.dict(root.getDictionaryObject(COSName.PARENT_TREE));
        if (pt == null) {
            return mcrs.isEmpty() && objrs.isEmpty();
        }
        Map<Integer, COSBase> tree = new HashMap<>();
        numbers(pt, tree, new Visits(), 0);
        for (Mcr m : mcrs) {
            if (!(m.owner().getDictionaryObject(COSName.STRUCT_PARENTS) instanceof COSNumber key)
                    || !(tree.get(key.intValue()) instanceof COSArray a) || m.mcid() < 0 || m.mcid() >= a.size()
                    || ContentGraph.dict(a.get(m.mcid())) != m.element()) {
                return false;
            }
        }
        for (Objr o : objrs) {
            if (!(o.object().getDictionaryObject(COSName.STRUCT_PARENT) instanceof COSNumber key)
                    || ContentGraph.dict(tree.get(key.intValue())) != o.element()) {
                return false;
            }
        }
        return true;
    }

    private static void numbers(COSDictionary node, Map<Integer, COSBase> out, Visits visits, int depth)
            throws InterruptedIOException {
        if (depth > 64 || !visits.first(node)) {
            return;
        }
        COSArray nums = ContentGraph.array(node.getDictionaryObject(COSName.NUMS));
        for (int i = 0; nums != null && i + 1 < nums.size(); i += 2) {
            if (nums.getObject(i) instanceof COSNumber n) {
                out.put(n.intValue(), nums.getObject(i + 1));
            }
        }
        COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
        for (int i = 0; kids != null && i < kids.size(); i++) {
            COSDictionary kid = ContentGraph.dict(kids.getObject(i));
            if (kid != null) {
                numbers(kid, out, visits, depth + 1);
            }
        }
    }

    void rebuildParentTree() {
        int next = 0;
        Set<COSDictionary> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Mcr m : mcrs) {
            owners.add(m.owner());
        }
        Set<COSDictionary> keyed = Collections.newSetFromMap(new IdentityHashMap<>());
        keyed.addAll(owners);
        for (Objr o : objrs) {
            keyed.add(o.object());
        }
        for (COSDictionary d : keyed) {
            COSName key = owners.contains(d) ? COSName.STRUCT_PARENTS : COSName.STRUCT_PARENT;
            if (d.getDictionaryObject(key) instanceof COSNumber n) {
                next = Math.max(next, n.intValue() + 1);
            }
        }
        Map<COSDictionary, Integer> keys = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        for (COSDictionary d : keyed) {
            COSName key = owners.contains(d) ? COSName.STRUCT_PARENTS : COSName.STRUCT_PARENT;
            int k = d.getDictionaryObject(key) instanceof COSNumber n && used.add(n.intValue()) ? n.intValue() : next++;
            d.setInt(key, k);
            used.add(k);
            keys.put(d, k);
        }
        TreeMap<Integer, COSBase> values = new TreeMap<>();
        Map<COSDictionary, TreeMap<Integer, COSDictionary>> arrays = new IdentityHashMap<>();
        for (Mcr m : mcrs) {
            if (m.mcid() >= 0 && m.mcid() < 1_000_000) {
                arrays.computeIfAbsent(m.owner(), k -> new TreeMap<>()).putIfAbsent(m.mcid(), m.element());
            }
        }
        for (Map.Entry<COSDictionary, TreeMap<Integer, COSDictionary>> e : arrays.entrySet()) {
            COSArray a = new COSArray();
            for (int i = 0; i <= e.getValue().lastKey(); i++) {
                COSDictionary el = e.getValue().get(i);
                a.add(el == null ? COSNull.NULL : el);
            }
            values.put(keys.get(e.getKey()), a);
        }
        for (Objr o : objrs) {
            values.putIfAbsent(keys.get(o.object()), o.element());
        }
        COSArray nums = new COSArray();
        for (Map.Entry<Integer, COSBase> e : values.entrySet()) {
            nums.add(COSInteger.get(e.getKey()));
            nums.add(e.getValue());
        }
        COSDictionary tree = new COSDictionary();
        tree.setItem(COSName.NUMS, nums);
        root.setItem(COSName.PARENT_TREE, tree);
        root.setInt(PARENT_TREE_NEXT_KEY, next);
    }
}
