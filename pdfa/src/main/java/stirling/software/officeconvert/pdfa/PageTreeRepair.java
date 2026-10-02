package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;

final class PageTreeRepair {

    private static final int NODE = 1024;

    private static final int MAX_DEPTH = 64;

    private PageTreeRepair() {}

    static void run(PDDocument doc, Report report) {
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        COSDictionary root = ContentGraph.dict(catalog.getDictionaryObject(COSName.PAGES));
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<COSDictionary> leaves = new ArrayList<>();
        if (root != null && sound(root, null, 0, seen, leaves)) {
            return;
        }
        List<COSDictionary> pages = new ArrayList<>();
        if (root != null) {
            collect(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()), pages);
        }
        for (COSDictionary p : pages) {
            inherit(p);
            p.setItem(COSName.TYPE, COSName.PAGE);
        }
        COSDictionary top = root != null ? root : new COSDictionary();
        top.clear();
        top.setItem(COSName.TYPE, COSName.PAGES);
        List<COSDictionary> level = pages;
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
        report.warn("Rebuilt a page tree that was not well formed");
    }

    private static void collect(COSDictionary node, int depth, Set<COSDictionary> seen, List<COSDictionary> pages) {
        if (depth > MAX_DEPTH || !seen.add(node) || pages.size() > 1_000_000) {
            return;
        }
        COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
        if (kids == null || COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) {
            if (depth > 0) {
                pages.add(node);
            }
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            COSDictionary kid = ContentGraph.dict(kids.getObject(i));
            if (kid != null) {
                collect(kid, depth + 1, seen, pages);
            }
        }
    }

    private static boolean sound(COSDictionary node, COSDictionary parent, int depth, Set<COSDictionary> seen,
            List<COSDictionary> leaves) {
        if (depth > MAX_DEPTH || !seen.add(node) || !COSName.PAGES.equals(node.getCOSName(COSName.TYPE))
                || parent != null && ContentGraph.dict(node.getDictionaryObject(COSName.PARENT)) != parent) {
            return false;
        }
        COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
        if (kids == null) {
            return false;
        }
        int before = leaves.size();
        for (int i = 0; i < kids.size(); i++) {
            COSDictionary kid = ContentGraph.dict(kids.getObject(i));
            if (kid == null) {
                return false;
            }
            if (COSName.PAGE.equals(kid.getCOSName(COSName.TYPE))) {
                if (!seen.add(kid) || ContentGraph.dict(kid.getDictionaryObject(COSName.PARENT)) != node) {
                    return false;
                }
                leaves.add(kid);
            } else if (!sound(kid, node, depth + 1, seen, leaves)) {
                return false;
            }
        }
        return node.getInt(COSName.COUNT, -1) == leaves.size() - before;
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

    private static void inherit(COSDictionary page) {
        for (COSName key : new COSName[] {COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE}) {
            if (page.getDictionaryObject(key) != null) {
                continue;
            }
            COSDictionary p = ContentGraph.dict(page.getDictionaryObject(COSName.PARENT));
            for (int i = 0; p != null && i < MAX_DEPTH; i++) {
                COSBase v = p.getDictionaryObject(key);
                if (v != null) {
                    page.setItem(key, v);
                    break;
                }
                p = ContentGraph.dict(p.getDictionaryObject(COSName.PARENT));
            }
        }
    }
}
