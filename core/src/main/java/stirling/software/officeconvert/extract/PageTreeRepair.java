package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class PageTreeRepair {

    private static final int NODE = 1024;

    private static final int MAX_DEPTH = 64;

    private static final int MAX_PAGES = 1_000_000;

    private record Slot(int at, COSDictionary node, COSBase[] above) {}

    private final List<COSDictionary> pages = new ArrayList<>();

    private final Set<COSDictionary> reached = identitySet();

    private final Set<COSDictionary> nodes = identitySet();

    private final List<Slot> slots = new ArrayList<>();

    private boolean damaged;

    private PageTreeRepair() {}

    public static boolean run(PDDocument doc) {
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        COSDictionary root = dict(catalog.getDictionaryObject(COSName.PAGES));
        List<COSDictionary> boxless = new ArrayList<>();
        if (root != null && sound(root, null, null, 0, identitySet(), new int[1], boxless)) {
            boxless.forEach(PageTreeRepair::defaultBox);
            return false;
        }
        PageTreeRepair tree = new PageTreeRepair();
        tree.damaged = root == null;
        if (root != null) {
            tree.collect(root, 0, new COSBase[PageNodes.INHERITED.size()]);
        }
        if (!tree.slots.isEmpty() || tree.pages.isEmpty() && tree.damaged) {
            tree.recover(scan(doc.getDocument()));
        }
        for (COSDictionary p : tree.pages) {
            p.setItem(COSName.TYPE, COSName.PAGE);
            if (!usableBox(p.getDictionaryObject(COSName.MEDIA_BOX))) {
                defaultBox(p);
            }
        }
        boolean reuse = root != null && !(root instanceof COSStream)
                && !COSName.PAGE.equals(root.getCOSName(COSName.TYPE)) && tree.pages.stream().noneMatch(p -> p == root);
        COSDictionary top = reuse ? root : new COSDictionary();
        top.clear();
        top.setItem(COSName.TYPE, COSName.PAGES);
        List<COSDictionary> level = tree.pages;
        while (level.size() > NODE) {
            List<COSDictionary> up = new ArrayList<>();
            for (int i = 0; i < level.size(); i += NODE) {
                COSDictionary node = new COSDictionary();
                node.setItem(COSName.TYPE, COSName.PAGES);
                link(node, level.subList(i, Math.min(level.size(), i + NODE)));
                up.add(node);
            }
            level = up;
        }
        link(top, level);
        catalog.setItem(COSName.PAGES, top);
        return true;
    }

    private static boolean sound(COSDictionary node, COSDictionary parent, COSBase box, int depth,
            Set<COSDictionary> seen, int[] count, List<COSDictionary> boxless) {
        if (depth > MAX_DEPTH || !seen.add(node) || !COSName.PAGES.equals(node.getCOSName(COSName.TYPE))
                || parent != null && dict(node.getDictionaryObject(COSName.PARENT)) != parent) {
            return false;
        }
        COSArray kids = array(node.getDictionaryObject(COSName.KIDS));
        if (kids == null) {
            return false;
        }
        COSBase own = node.getDictionaryObject(COSName.MEDIA_BOX);
        COSBase here = own != null ? own : box;
        int before = count[0];
        for (int i = 0; i < kids.size(); i++) {
            COSDictionary kid = dict(kids.getObject(i));
            if (kid == null || kid instanceof COSStream) {
                return false;
            }
            if (COSName.PAGE.equals(kid.getCOSName(COSName.TYPE))) {
                if (!seen.add(kid) || dict(kid.getDictionaryObject(COSName.PARENT)) != node
                        || kid.getDictionaryObject(COSName.KIDS) != null) {
                    return false;
                }
                COSBase pageBox = kid.getDictionaryObject(COSName.MEDIA_BOX);
                COSBase effective = pageBox != null ? pageBox : here;
                if (effective != null && !usableBox(effective)) {
                    boxless.add(kid);
                }
                count[0]++;
            } else if (!sound(kid, node, here, depth + 1, seen, count, boxless)) {
                return false;
            }
        }
        return node.getInt(COSName.COUNT, -1) == count[0] - before;
    }

    private void collect(COSDictionary node, int depth, COSBase[] above) {
        if (depth > MAX_DEPTH || !nodes.add(node)) {
            damaged = true;
            return;
        }
        COSArray kids = array(node.getDictionaryObject(COSName.KIDS));
        if (kids == null) {
            damaged = true;
            return;
        }
        COSBase[] here = above.clone();
        for (int k = 0; k < PageNodes.INHERITED.size(); k++) {
            COSBase value = usable(PageNodes.INHERITED.get(k), node.getDictionaryObject(PageNodes.INHERITED.get(k)));
            if (value != null) {
                here[k] = value;
            }
        }
        for (int i = 0; i < kids.size() && pages.size() < MAX_PAGES; i++) {
            COSDictionary kid = dict(kids.getObject(i));
            if (kid != null && !(kid instanceof COSStream) && PageNodes.intermediate(kid)) {
                collect(kid, depth + 1, here);
            } else if (kid != null && !(kid instanceof COSStream) && PageNodes.leaf(kid) && !reached.contains(kid)) {
                inherit(kid, here);
                reached.add(kid);
                pages.add(kid);
            } else {
                damaged = true;
                slots.add(new Slot(pages.size(), node, here));
                pages.add(placeholder(here));
            }
        }
    }

    private void recover(List<COSDictionary> found) {
        List<COSDictionary> orphans = new ArrayList<>();
        for (COSDictionary page : found) {
            if (!reached.contains(page)) {
                orphans.add(page);
            }
        }
        if (pages.isEmpty()) {
            for (COSDictionary page : orphans) {
                inherit(page, new COSBase[PageNodes.INHERITED.size()]);
            }
            pages.addAll(orphans);
            return;
        }
        boolean[] used = new boolean[orphans.size()];
        for (int pass = 0; pass < 2; pass++) {
            for (Slot slot : slots) {
                if (reached.contains(pages.get(slot.at()))) {
                    continue;
                }
                for (int k = 0; k < orphans.size(); k++) {
                    COSDictionary parent = dict(orphans.get(k).getDictionaryObject(COSName.PARENT));
                    if (!used[k] && (pass == 0 ? parent == slot.node() : parent == null || !nodes.contains(parent))) {
                        used[k] = true;
                        inherit(orphans.get(k), slot.above());
                        reached.add(orphans.get(k));
                        pages.set(slot.at(), orphans.get(k));
                        break;
                    }
                }
            }
        }
        for (int k = 0; k < orphans.size(); k++) {
            COSDictionary parent = dict(orphans.get(k).getDictionaryObject(COSName.PARENT));
            if (!used[k] && parent != null && nodes.contains(parent)) {
                inherit(orphans.get(k), new COSBase[PageNodes.INHERITED.size()]);
                pages.add(orphans.get(k));
            }
        }
    }

    private static COSDictionary placeholder(COSBase[] above) {
        COSDictionary page = new COSDictionary();
        page.setItem(COSName.TYPE, COSName.PAGE);
        inherit(page, above);
        if (page.getDictionaryObject(COSName.RESOURCES) == null) {
            page.setItem(COSName.RESOURCES, new COSDictionary());
        }
        return page;
    }

    private static List<COSDictionary> scan(COSDocument document) {
        List<Map.Entry<COSObjectKey, Long>> entries = new ArrayList<>(document.getXrefTable().entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<COSObjectKey, Long> e) -> e.getValue() < 0 ? 1 : 0)
                .thenComparingLong(e -> e.getValue() < 0 ? e.getKey().getNumber() : e.getValue()));
        List<COSDictionary> found = new ArrayList<>();
        Set<COSDictionary> seen = identitySet();
        for (Map.Entry<COSObjectKey, Long> e : entries) {
            if (found.size() >= MAX_PAGES) {
                break;
            }
            COSBase value;
            try {
                value = document.getObjectFromPool(e.getKey()).getObject();
            } catch (RuntimeException unreadable) {
                continue;
            }
            if (value instanceof COSDictionary d && !(value instanceof COSStream)
                    && COSName.PAGE.equals(d.getCOSName(COSName.TYPE)) && !d.containsKey(COSName.KIDS)
                    && seen.add(d)) {
                found.add(d);
            }
        }
        return found;
    }

    private static void inherit(COSDictionary page, COSBase[] above) {
        for (int k = 0; k < PageNodes.INHERITED.size(); k++) {
            COSName key = PageNodes.INHERITED.get(k);
            if (usable(key, page.getDictionaryObject(key)) != null) {
                continue;
            }
            COSBase value = above[k];
            COSDictionary p = dict(page.getDictionaryObject(COSName.PARENT));
            Set<COSDictionary> visited = identitySet();
            for (int i = 0; value == null && p != null && i < MAX_DEPTH && visited.add(p); i++) {
                value = usable(key, p.getDictionaryObject(key));
                p = dict(p.getDictionaryObject(COSName.PARENT));
            }
            if (value != null) {
                page.setItem(key, value);
            }
        }
    }

    private static void defaultBox(COSDictionary page) {
        COSBase crop = page.getDictionaryObject(COSName.CROP_BOX);
        if (usableBox(crop)) {
            page.setItem(COSName.MEDIA_BOX, crop);
            return;
        }
        COSArray letter = new COSArray();
        letter.add(COSInteger.ZERO);
        letter.add(COSInteger.ZERO);
        letter.add(COSInteger.get(612));
        letter.add(COSInteger.get(792));
        page.setItem(COSName.MEDIA_BOX, letter);
    }

    private static COSBase usable(COSName key, COSBase value) {
        if (COSName.MEDIA_BOX.equals(key) || COSName.CROP_BOX.equals(key)) {
            return usableBox(value) ? value : null;
        }
        if (COSName.RESOURCES.equals(key)) {
            return value instanceof COSDictionary r && !(value instanceof COSStream) ? r : null;
        }
        return value instanceof COSNumber ? value : null;
    }

    private static boolean usableBox(COSBase value) {
        if (!(value instanceof COSArray a) || a.size() != 4) {
            return false;
        }
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            if (!(a.getObject(i) instanceof COSNumber n) || !Float.isFinite(n.floatValue())) {
                return false;
            }
            v[i] = n.floatValue();
        }
        return v[2] != v[0] && v[3] != v[1];
    }

    private static void link(COSDictionary node, List<COSDictionary> kids) {
        COSArray array = new COSArray();
        int count = 0;
        for (COSDictionary k : kids) {
            array.add(k);
            k.setItem(COSName.PARENT, node);
            count += COSName.PAGES.equals(k.getCOSName(COSName.TYPE)) ? k.getInt(COSName.COUNT) : 1;
        }
        node.setItem(COSName.KIDS, array);
        node.setInt(COSName.COUNT, count);
    }

    private static COSDictionary dict(COSBase b) {
        if (b instanceof COSObject o) {
            b = o.getObject();
        }
        return b instanceof COSDictionary d ? d : null;
    }

    private static COSArray array(COSBase b) {
        if (b instanceof COSObject o) {
            b = o.getObject();
        }
        return b instanceof COSArray a ? a : null;
    }

    private static Set<COSDictionary> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
