package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.BrotliBudget;
import stirling.software.officeconvert.extract.PdfFiles;

final class Cleanup {

    static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");

    private static final COSName BEADS = COSName.getPDFName("B");

    private static final COSName GO_TO = COSName.getPDFName("GoTo");

    private static final Set<COSName> DEFAULT_SPACES = Set.of(COSName.getPDFName("DefaultGray"),
            COSName.getPDFName("DefaultRGB"), COSName.getPDFName("DefaultCMYK"));

    record Removal(Object[] path, Set<COSName> keys) {}

    record Sub(Roles.Loc loc, COSDictionary dict) {}

    private Cleanup() {}

    static Map<Integer, List<Removal>> unusedResources(Roles roles, Resolver resolver, Source source, Index index,
            Roles.Loc dr, BrotliBudget budget) throws IOException {
        Map<List<Integer>, Content.Usage> scanned = new HashMap<>();
        Map<Roles.Loc, Set<COSName>> used = new HashMap<>();
        Map<Roles.Loc, Content.Category> categories = new HashMap<>();
        Map<Roles.Loc, COSDictionary> subs = new HashMap<>();
        Set<Roles.Loc> unsafe = new HashSet<>();
        Set<Roles.Loc> known = new HashSet<>();
        int count = 0;
        for (Roles.Context c : roles.contexts) {
            if ((++count & 255) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            List<Integer> streams = java.util.Arrays.stream(c.streams()).boxed().toList();
            Content.Usage usage = scanned.get(streams);
            if (usage == null) {
                usage = scan(source, index, c.streams(), budget);
                scanned.put(streams, usage);
            }
            Resolver.Found r = c.resources() == null ? null : resolver.find(c.resources());
            if (r == null || !(r.value() instanceof COSDictionary res)) {
                if (!usage.complete() || usage.any()) {
                    return Map.of();
                }
                continue;
            }
            known.add(r.loc());
            for (Content.Category category : Content.Category.values()) {
                Sub sub = sub(resolver, index, r.loc(), res, category.key);
                if (sub == null) {
                    continue;
                }
                subs.put(sub.loc, sub.dict);
                Content.Category previous = categories.putIfAbsent(sub.loc, category);
                if (previous != null && previous != category || !usage.complete()) {
                    unsafe.add(sub.loc);
                }
                used.computeIfAbsent(sub.loc, k -> new HashSet<>())
                        .addAll(usage.names().getOrDefault(category, Set.of()));
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
            for (Content.Category category : Content.Category.values()) {
                Sub sub = sub(resolver, index, r.loc(), res, category.key);
                if (sub != null) {
                    unsafe.add(sub.loc);
                }
            }
        }
        Map<Integer, List<Removal>> removals = new HashMap<>();
        for (Map.Entry<Roles.Loc, Set<COSName>> e : used.entrySet()) {
            Roles.Loc loc = e.getKey();
            if (unsafe.contains(loc)) {
                continue;
            }
            boolean colourSpaces = categories.get(loc) == Content.Category.COLOR_SPACE;
            Set<COSName> drop = new HashSet<>();
            for (COSName key : subs.get(loc).keySet()) {
                if (!e.getValue().contains(key) && !(colourSpaces && DEFAULT_SPACES.contains(key))) {
                    drop.add(key);
                }
            }
            if (!drop.isEmpty()) {
                removals.computeIfAbsent(loc.holder(), k -> new ArrayList<>()).add(new Removal(loc.path(), drop));
            }
        }
        return removals;
    }

    static Sub sub(Resolver resolver, Index index, Roles.Loc at, COSDictionary res, COSName key)
            throws IOException {
        COSBase v = res.getItem(key);
        if (v instanceof COSObject o && o.getKey() != null) {
            int id = index.id(o.getKey());
            return resolver.load(id) instanceof COSDictionary d && !(d instanceof COSStream)
                    ? new Sub(new Roles.Loc(id, new Object[0]), d) : null;
        }
        return v instanceof COSDictionary d && !(d instanceof COSStream) ? new Sub(at.child(key), d) : null;
    }

    static Map<Integer, List<Removal>> withoutBeads(Map<Integer, List<Removal>> removals, Roles roles) {
        if (roles.beaded.isEmpty()) {
            return removals;
        }
        Map<Integer, List<Removal>> all = new HashMap<>(removals);
        for (int page : roles.beaded) {
            all.computeIfAbsent(page, k -> new ArrayList<>()).add(new Removal(new Object[0], Set.of(BEADS)));
        }
        return all;
    }

    static Map<Integer, List<Removal>> withoutDeadDestinations(Map<Integer, List<Removal>> removals, Roles roles,
            Index index, Source source, int root) throws IOException {
        Set<Integer> inTree = pagesInTree(index, source, root);
        if (inTree.isEmpty()) {
            return removals;
        }
        Set<Integer> orphans = new HashSet<>(roles.pageObjects());
        orphans.removeAll(inTree);
        if (orphans.isEmpty()) {
            return removals;
        }
        Map<Integer, List<Removal>> all = new HashMap<>(removals);
        for (int id = 0; id < index.size(); id++) {
            if ((id & 4095) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            if (orphans.contains(id) || !refersTo(index, id, orphans)
                    || !(source.load(index.key(id)) instanceof COSDictionary d) || d instanceof COSStream) {
                continue;
            }
            Set<COSName> dead = new HashSet<>();
            if (deadTarget(d.getItem(COSName.DEST), index, orphans)) {
                dead.add(COSName.DEST);
            }
            if (d.getDictionaryObject(COSName.A) instanceof COSDictionary action && !(d.getItem(COSName.A)
                    instanceof COSObject) && GO_TO.equals(action.getCOSName(COSName.S))
                    && deadTarget(action.getItem(COSName.D), index, orphans)) {
                dead.add(COSName.A);
            }
            if (id == root && deadTarget(d.getItem(COSName.OPEN_ACTION), index, orphans)) {
                dead.add(COSName.OPEN_ACTION);
            }
            if (!dead.isEmpty()) {
                all.computeIfAbsent(id, k -> new ArrayList<>()).add(new Removal(new Object[0], dead));
            }
        }
        return all;
    }

    private static boolean refersTo(Index index, int id, Set<Integer> targets) {
        for (int k = 0; k < index.holeCount(id); k++) {
            if (targets.contains(index.holeRef(id, k))) {
                return true;
            }
        }
        return false;
    }

    private static boolean deadTarget(COSBase destination, Index index, Set<Integer> orphans) {
        return destination instanceof COSArray array && array.size() > 0
                && array.get(0) instanceof COSObject page && page.getKey() != null
                && orphans.contains(index.find(page.getKey()));
    }

    private static Set<Integer> pagesInTree(Index index, Source source, int root) throws IOException {
        Set<Integer> found = new HashSet<>();
        if (!(source.load(index.key(root)) instanceof COSDictionary catalog)
                || !(catalog.getItem(COSName.PAGES) instanceof COSObject top)
                || top.getKey() == null) {
            return found;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(index.find(top.getKey()));
        while (!queue.isEmpty()) {
            int node = queue.poll();
            if (node < 0 || !seen.add(node) || !(source.load(index.key(node)) instanceof COSDictionary d)) {
                continue;
            }
            if (d.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
                for (COSBase kid : kids) {
                    if (kid instanceof COSObject ref && ref.getKey() != null) {
                        queue.add(index.find(ref.getKey()));
                    }
                }
            } else {
                found.add(node);
            }
        }
        return found;
    }

    static void apply(COSBase holder, List<Removal> removals) {
        for (Removal r : removals) {
            COSBase v = holder;
            for (Object step : r.path()) {
                if (step instanceof COSName key && v instanceof COSDictionary d) {
                    v = d.getItem(key);
                } else if (step instanceof Integer i && v instanceof COSArray a && i < a.size()) {
                    v = a.get(i);
                } else {
                    v = null;
                }
            }
            if (v instanceof COSDictionary d) {
                for (COSName key : r.keys()) {
                    d.removeItem(key);
                }
            }
        }
    }

    private static Content.Usage scan(Source source, Index index, int[] streams, BrotliBudget budget)
            throws IOException {
        byte[] data = content(source, index, streams, budget);
        return data == null ? Content.Usage.unknown() : Content.scan(data);
    }

    static byte[] content(Source source, Index index, int[] streams, BrotliBudget budget) throws IOException {
        if (streams.length == 1) {
            return decoded(source.load(index.key(streams[0])), budget);
        }
        Bytes joined = new Bytes(1 << 12);
        for (int stream : streams) {
            byte[] data = decoded(source.load(index.key(stream)), budget);
            if (data == null || joined.size() + (long) data.length + 1 > Recoder.MAX_DECODED_BYTES) {
                return null;
            }
            joined.write(data);
            joined.write('\n');
        }
        return joined.toByteArray();
    }

    private static byte[] decoded(COSBase s, BrotliBudget budget) {
        if (!(s instanceof COSStream stream)) {
            return null;
        }
        try {
            return Recoder.decoded(stream, budget);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
