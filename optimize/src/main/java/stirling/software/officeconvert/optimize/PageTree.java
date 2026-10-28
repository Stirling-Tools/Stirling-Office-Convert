package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class PageTree {

    static final int MAX_DEPTH = 1024;

    private static final COSName[] INHERITED = {COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX,
            COSName.ROTATE};

    private final Index index;

    private final Source source;

    private final Recorder recorder;

    private final Roles roles;

    private final Map<Integer, COSDictionary> edited = new LinkedHashMap<>();

    private final Set<Integer> created = new HashSet<>();

    private final Map<Integer, List<Integer>> kids = new HashMap<>();

    private final Set<Integer> rewired = new HashSet<>();

    private final Set<Integer> moved = new HashSet<>();

    private final Set<Integer> nodes = new HashSet<>();

    private final Set<Integer> leaves = new LinkedHashSet<>();

    private final List<int[]> slots = new ArrayList<>();

    private final Set<String> notes = new LinkedHashSet<>();

    private int next;

    private boolean cut;

    private int counts;

    private int boxes;

    private PageTree(Index index, Source source, Recorder recorder, Roles roles) {
        this.index = index;
        this.source = source;
        this.recorder = recorder;
        this.roles = roles;
        for (int i = 0; i < index.size(); i++) {
            next = (int) Math.max(next, index.key(i).getNumber() + 1);
        }
    }

    static List<String> repair(Index index, Source source, Recorder recorder, Roles roles, int root)
            throws IOException {
        PageTree tree = new PageTree(index, source, recorder, roles);
        source.keepContainers(true);
        try {
            tree.run(root);
        } finally {
            source.keepContainers(false);
        }
        return List.copyOf(tree.notes);
    }

    static int census(COSDocument document, boolean strict) throws IOException {
        COSDictionary catalog = document.getTrailer().getCOSDictionary(COSName.ROOT);
        COSBase pages = catalog == null ? null : catalog.getItem(COSName.PAGES);
        if (!(pages instanceof COSObject ref) || !(ref.getObject() instanceof COSDictionary top)
                || top instanceof COSStream) {
            if (strict) {
                throw new IOException("its page tree has no root");
            }
            return 0;
        }
        int[] found = {0};
        try {
            count(top, null, strict, new HashSet<>(), 0, found);
        } catch (IOException | RuntimeException e) {
            if (strict) {
                throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
        }
        return found[0];
    }

    private static int count(COSDictionary node, COSBase box, boolean strict, Set<COSDictionary> seen, int depth,
            int[] found) throws IOException {
        PdfFiles.stopIfInterrupted();
        COSBase own = node.getDictionaryObject(COSName.MEDIA_BOX);
        COSBase inherited = own != null ? own : box;
        if (!(node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids)
                || COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) {
            if (strict && box(inherited) == null) {
                throw new IOException("page " + (found[0] + 1) + " has no usable page size");
            }
            found[0]++;
            return 1;
        }
        if (!seen.add(node) || depth >= MAX_DEPTH) {
            throw new IOException("its page tree loops or is too deep");
        }
        int total = 0;
        for (int i = 0; i < kids.size(); i++) {
            COSBase kid = kids.get(i);
            COSBase value = kid instanceof COSObject o ? o.getObject() : null;
            if (!(value instanceof COSDictionary d) || value instanceof COSStream) {
                if (strict) {
                    throw new IOException("its page tree has an entry that is not a page");
                }
                continue;
            }
            total += count(d, inherited, strict, seen, depth + 1, found);
        }
        if (strict && node.getInt(COSName.COUNT, -1) != total) {
            throw new IOException("its page count says " + node.getInt(COSName.COUNT, -1) + " but it has " + total);
        }
        return total;
    }

    private void run(int root) throws IOException {
        COSDictionary catalog = dict(root);
        if (catalog == null) {
            return;
        }
        if (!COSName.CATALOG.equals(catalog.getCOSName(COSName.TYPE))) {
            edit(root).setItem(COSName.TYPE, COSName.CATALOG);
        }
        int top = catalog.getItem(COSName.PAGES) instanceof COSObject o && o.getKey() != null
                ? index.id(o.getKey()) : -1;
        COSDictionary topDict = top < 0 ? null : dict(top);
        if (topDict != null && intermediate(topDict)) {
            walk(top, 0);
            if (!slots.isEmpty()) {
                reattach();
            }
            if (leaves.isEmpty() && (cut || !slots.isEmpty())) {
                topDict = null;
                kids.clear();
                rewired.clear();
                moved.clear();
                nodes.clear();
                slots.clear();
                for (int id : created) {
                    edited.remove(id);
                }
                created.clear();
            }
        }
        if (topDict == null || !intermediate(topDict)) {
            List<Integer> pages = roles.pageObjects();
            if (pages.isEmpty()) {
                return;
            }
            int fresh = create(new COSDictionary());
            List<Integer> all = new ArrayList<>();
            for (int page : pages) {
                if (dict(page) != null) {
                    all.add(page);
                    moved.add(page);
                }
            }
            kids.put(fresh, all);
            rewired.add(fresh);
            nodes.add(fresh);
            leaves.addAll(all);
            edit(root).setItem(COSName.PAGES, reference(fresh));
            notes.add("its page tree was rebuilt from the " + all.size() + " pages found in the file");
            top = fresh;
        }
        normalize(top, -1, new COSBase[INHERITED.length], 0);
        if (counts > 0) {
            notes.add("its page count" + (counts == 1 ? " was" : "s were") + " corrected");
        }
        if (boxes > 0) {
            notes.add(boxes + (boxes == 1 ? " page was" : " pages were") + " given the default page size");
        }
        commit();
    }

    private void walk(int node, int depth) throws IOException {
        PdfFiles.stopIfInterrupted();
        nodes.add(node);
        COSDictionary d = dict(node);
        List<Integer> out = new ArrayList<>();
        kids.put(node, out);
        if (!(d.getItem(COSName.KIDS) instanceof COSArray)) {
            rewired.add(node);
        }
        if (!(d.getDictionaryObject(COSName.KIDS) instanceof COSArray array)) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            COSBase entry = array.get(i);
            if (entry instanceof COSObject o && o.getKey() != null) {
                int kid = index.id(o.getKey());
                COSDictionary k = dict(kid);
                if (k != null && intermediate(k)) {
                    if (nodes.contains(kid) || depth + 1 >= MAX_DEPTH) {
                        rewired.add(node);
                        cut = true;
                        notes.add(nodes.contains(kid) ? "a loop in its page tree was cut"
                                : "its page tree was cut where it nests too deeply");
                        continue;
                    }
                    out.add(kid);
                    walk(kid, depth + 1);
                    continue;
                }
                if (k != null && leaf(k)) {
                    out.add(kid);
                    leaves.add(kid);
                    continue;
                }
            } else if (entry instanceof COSDictionary direct && !(entry instanceof COSStream) && leaf(direct)) {
                int kid = create(copy(direct));
                out.add(kid);
                leaves.add(kid);
                rewired.add(node);
                continue;
            }
            rewired.add(node);
            out.add(-1 - slots.size());
            slots.add(new int[] {node, out.size() - 1});
        }
    }

    private void reattach() throws IOException {
        List<Integer> orphans = new ArrayList<>();
        for (int page : roles.pageObjects()) {
            if (!leaves.contains(page) && dict(page) != null) {
                orphans.add(page);
            }
        }
        boolean[] used = new boolean[orphans.size()];
        int[] filled = new int[slots.size()];
        java.util.Arrays.fill(filled, -1);
        for (int pass = 0; pass < 2; pass++) {
            for (int s = 0; s < slots.size(); s++) {
                if (filled[s] >= 0) {
                    continue;
                }
                for (int k = 0; k < orphans.size(); k++) {
                    if (used[k]) {
                        continue;
                    }
                    int parent = declaredParent(orphans.get(k));
                    if (pass == 0 ? parent == slots.get(s)[0] : !nodes.contains(parent)) {
                        used[k] = true;
                        filled[s] = orphans.get(k);
                        break;
                    }
                }
            }
        }
        int reattached = 0;
        int blank = 0;
        for (int s = 0; s < slots.size(); s++) {
            int[] slot = slots.get(s);
            int page = filled[s];
            if (page < 0) {
                page = create(placeholder(slot[0], slot[1]));
                blank++;
            } else {
                reattached++;
                if (declaredParent(page) != slot[0]) {
                    moved.add(page);
                }
            }
            kids.get(slot[0]).set(slot[1], page);
            leaves.add(page);
        }
        for (int k = 0; k < orphans.size(); k++) {
            int parent = declaredParent(orphans.get(k));
            if (!used[k] && nodes.contains(parent)) {
                kids.get(parent).add(orphans.get(k));
                rewired.add(parent);
                leaves.add(orphans.get(k));
                reattached++;
            }
        }
        if (reattached > 0) {
            notes.add(reattached + (reattached == 1 ? " page that its page tree had lost was"
                    : " pages that its page tree had lost were") + " reattached");
        }
        if (blank > 0) {
            notes.add(blank + (blank == 1 ? " missing page was" : " missing pages were")
                    + " replaced by a blank page");
        }
    }

    private COSDictionary placeholder(int parent, int position) throws IOException {
        COSArray box = null;
        List<Integer> siblings = kids.get(parent);
        for (int d = 1; box == null && d <= siblings.size(); d++) {
            for (int at : new int[] {position - d, position + d}) {
                if (box == null && at >= 0 && at < siblings.size() && siblings.get(at) >= 0) {
                    box = box(inherited(siblings.get(at), COSName.MEDIA_BOX));
                }
            }
        }
        COSDictionary page = new COSDictionary();
        page.setItem(COSName.TYPE, COSName.PAGE);
        if (box != null) {
            page.setItem(COSName.MEDIA_BOX, copy(box));
        }
        page.setItem(COSName.RESOURCES, new COSDictionary());
        return page;
    }

    private int normalize(int node, int parent, COSBase[] above, int depth) throws IOException {
        PdfFiles.stopIfInterrupted();
        COSDictionary d = current(node);
        COSBase[] here = above.clone();
        for (int k = 0; k < INHERITED.length; k++) {
            COSBase own = usable(INHERITED[k], d.getDictionaryObject(INHERITED[k]));
            if (own != null) {
                here[k] = own;
            } else if (d.getItem(INHERITED[k]) != null) {
                edit(node).removeItem(INHERITED[k]);
            }
        }
        int count = 0;
        List<Integer> list = kids.get(node);
        for (int kid : list) {
            if (nodes.contains(kid) && depth + 1 < MAX_DEPTH) {
                count += normalize(kid, node, here, depth + 1);
            } else {
                page(kid, node, here);
                count++;
            }
        }
        if (rewired.contains(node)) {
            COSArray array = new COSArray();
            for (int kid : list) {
                array.add(reference(kid));
            }
            edit(node).setItem(COSName.KIDS, array);
        }
        if (!(d.getItem(COSName.COUNT) instanceof COSInteger n) || n.longValue() != count) {
            edit(node).setInt(COSName.COUNT, count);
            counts++;
        }
        if (!COSName.PAGES.equals(d.getCOSName(COSName.TYPE))) {
            edit(node).setItem(COSName.TYPE, COSName.PAGES);
        }
        parent(node, parent);
        return count;
    }

    private static COSBase usable(COSName key, COSBase value) {
        if (COSName.MEDIA_BOX.equals(key)) {
            return box(value);
        }
        if (COSName.RESOURCES.equals(key)) {
            return value instanceof COSDictionary r && !(value instanceof COSStream) ? r : null;
        }
        return value instanceof COSNull ? null : value;
    }

    private void page(int page, int parent, COSBase[] above) throws IOException {
        boolean away = moved.contains(page);
        for (int k = 0; k < INHERITED.length; k++) {
            COSName key = INHERITED[k];
            if (current(page).getItem(key) == null && (away || above[k] == null)) {
                COSBase value = usable(key, inherited(page, key));
                if (value != null) {
                    edit(page).setItem(key, copy(value));
                }
            }
        }
        COSDictionary d = current(page);
        if (!COSName.PAGE.equals(d.getCOSName(COSName.TYPE))) {
            edit(page).setItem(COSName.TYPE, COSName.PAGE);
        }
        COSBase own = d.getDictionaryObject(COSName.MEDIA_BOX);
        if (box(own) == null && (own != null || above[1] == null)) {
            COSArray crop = box(d.getDictionaryObject(COSName.CROP_BOX));
            edit(page).setItem(COSName.MEDIA_BOX, crop != null ? copy(crop) : letter());
            boxes++;
        }
        if (d.getItem(COSName.RESOURCES) == null && above[0] == null) {
            edit(page).setItem(COSName.RESOURCES, new COSDictionary());
        }
        parent(page, parent);
    }

    private void parent(int node, int parent) throws IOException {
        COSDictionary d = current(node);
        COSBase declared = d.getItem(COSName.PARENT);
        if (parent < 0) {
            if (declared != null) {
                edit(node).removeItem(COSName.PARENT);
            }
            return;
        }
        if (!(declared instanceof COSObject o) || o.getKey() == null || index.id(o.getKey()) != parent) {
            edit(node).setItem(COSName.PARENT, reference(parent));
        }
    }

    private int declaredParent(int page) throws IOException {
        COSDictionary d = current(page);
        return d.getItem(COSName.PARENT) instanceof COSObject o && o.getKey() != null ? index.id(o.getKey()) : -1;
    }

    private COSBase inherited(int page, COSName key) throws IOException {
        int node = page;
        Set<Integer> seen = new HashSet<>();
        for (int depth = 0; node >= 0 && depth < MAX_DEPTH && seen.add(node); depth++) {
            COSDictionary d = node == page || edited.containsKey(node) || created.contains(node) ? current(node)
                    : dict(node);
            if (d == null) {
                return null;
            }
            COSBase value = d.getDictionaryObject(key);
            if (value != null) {
                return value;
            }
            node = d.getItem(COSName.PARENT) instanceof COSObject o && o.getKey() != null ? index.id(o.getKey())
                    : -1;
        }
        return null;
    }

    private static COSArray box(COSBase value) {
        if (!(value instanceof COSArray a) || a.size() != 4) {
            return null;
        }
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            if (!(a.getObject(i) instanceof COSNumber n) || !Float.isFinite(n.floatValue())) {
                return null;
            }
            v[i] = n.floatValue();
        }
        return Math.abs(v[2] - v[0]) >= 1 && Math.abs(v[3] - v[1]) >= 1 ? a : null;
    }

    private static COSArray letter() {
        COSArray a = new COSArray();
        a.add(COSInteger.ZERO);
        a.add(COSInteger.ZERO);
        a.add(COSInteger.get(612));
        a.add(COSInteger.get(792));
        return a;
    }

    private static boolean intermediate(COSDictionary d) {
        return COSName.PAGES.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(COSName.KIDS)
                && !COSName.PAGE.equals(d.getCOSName(COSName.TYPE));
    }

    private static boolean leaf(COSDictionary d) {
        COSName type = d.getCOSName(COSName.TYPE);
        return COSName.PAGE.equals(type) || type == null && !d.containsKey(COSName.KIDS)
                && (d.containsKey(COSName.CONTENTS) || d.containsKey(COSName.MEDIA_BOX)
                        || d.containsKey(COSName.PARENT));
    }

    private COSDictionary dict(int id) throws IOException {
        if (created.contains(id) || edited.containsKey(id)) {
            return current(id);
        }
        if (index.kind(id) != Index.DICT) {
            return null;
        }
        return source.load(index.key(id)) instanceof COSDictionary d && !(d instanceof COSStream) ? d : null;
    }

    private COSDictionary current(int id) throws IOException {
        COSDictionary d = edited.get(id);
        return d != null ? d : dict(id);
    }

    private COSDictionary edit(int id) throws IOException {
        COSDictionary d = edited.get(id);
        if (d == null) {
            d = new COSDictionary(dict(id));
            edited.put(id, d);
        }
        return d;
    }

    private int create(COSDictionary d) {
        int id = index.id(new COSObjectKey(next++, 0));
        created.add(id);
        edited.put(id, d);
        return id;
    }

    private COSObject reference(int id) throws IOException {
        COSDictionary d = current(id);
        return new COSObject(d != null ? d : COSNull.NULL, index.key(id));
    }

    private void commit() throws IOException {
        for (Map.Entry<Integer, COSDictionary> e : edited.entrySet()) {
            int id = e.getKey();
            COSDictionary d = e.getValue();
            source.inject(index.key(id), d);
            recorder.record(id, d, null);
            if (created.contains(id)) {
                roles.collect(id, d);
            } else if (nodes.contains(id) || leaves.contains(id)) {
                roles.page(id, d);
            }
        }
    }

    private static COSBase copy(COSBase value) {
        return copy(value, 0);
    }

    private static COSBase copy(COSBase value, int depth) {
        if (depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof COSArray a) {
            COSArray out = new COSArray();
            for (int i = 0; i < a.size(); i++) {
                out.add(copy(a.get(i), depth + 1));
            }
            return out;
        }
        if (value instanceof COSDictionary d && !(value instanceof COSStream)) {
            COSDictionary out = new COSDictionary();
            for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
                out.setItem(e.getKey(), copy(e.getValue(), depth + 1));
            }
            return out;
        }
        return value;
    }

    private static COSDictionary copy(COSDictionary d) {
        return (COSDictionary) copy((COSBase) d);
    }
}
