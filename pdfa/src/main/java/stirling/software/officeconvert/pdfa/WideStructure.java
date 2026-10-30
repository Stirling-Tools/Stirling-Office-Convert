package stirling.software.officeconvert.pdfa;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;

final class WideStructure {

    private static final int GROUP = 4000;

    private static final COSName STRUCT_ELEM = COSName.getPDFName("StructElem");

    private static final COSName NON_STRUCT = COSName.getPDFName("NonStruct");

    private WideStructure() {}

    static void run(PDDocument doc, PdfALevel level, Report report) throws InterruptedIOException {
        COSDictionary root = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.STRUCT_TREE_ROOT));
        if (level.part() > 1 || root == null) {
            return;
        }
        if (!level.tagged() && oversizedParentTree(root)) {
            root.removeItem(COSName.PARENT_TREE);
            doc.getDocumentCatalog().getCOSObject().removeItem(COSName.STRUCT_TREE_ROOT);
            COSDictionary markInfo = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.MARK_INFO));
            if (markInfo != null) {
                markInfo.setBoolean(COSName.getPDFName("Marked"), false);
            }
            report.warn("Removed the structure tree, as a page marks more content than a PDF/A-1 array can index");
        }
        boolean changed = false;
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSDictionary> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && seen.size() < 2_000_000) {
            COSDictionary e = stack.pop();
            if (!seen.add(e)) {
                continue;
            }
            COSArray k = ContentGraph.array(e.getDictionaryObject(COSName.K));
            if (k != null && k.size() > LongArrays.MAX) {
                e.setItem(COSName.K, group(e, k));
                changed = true;
            }
            COSBase kids = e.getDictionaryObject(COSName.K);
            if (kids instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    COSDictionary d = ContentGraph.dict(a.getObject(i));
                    if (d != null && d.containsKey(COSName.S)) {
                        stack.push(d);
                    }
                }
            } else if (ContentGraph.dict(kids) != null && ContentGraph.dict(kids).containsKey(COSName.S)) {
                stack.push(ContentGraph.dict(kids));
            }
        }
        if (!changed) {
            return;
        }
        StructTree tree = StructTree.read(root);
        if (!tree.parentTreeMatches()) {
            tree.rebuildParentTree();
        }
        report.warn("Grouped structure elements with more children than PDF/A-1 allows under NonStruct elements");
    }

    private static boolean oversizedParentTree(COSDictionary root) {
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSDictionary> stack = new ArrayDeque<>();
        COSDictionary tree = ContentGraph.dict(root.getDictionaryObject(COSName.PARENT_TREE));
        if (tree != null) {
            stack.push(tree);
        }
        while (!stack.isEmpty() && seen.size() < 100_000) {
            COSDictionary node = stack.pop();
            if (!seen.add(node)) {
                continue;
            }
            COSArray nums = ContentGraph.array(node.getDictionaryObject(COSName.NUMS));
            if (nums != null) {
                for (int i = 1; i < nums.size(); i += 2) {
                    COSArray marked = ContentGraph.array(nums.getObject(i));
                    if (marked != null && marked.size() > LongArrays.MAX) {
                        return true;
                    }
                }
            }
            COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    COSDictionary kid = ContentGraph.dict(kids.getObject(i));
                    if (kid != null) {
                        stack.push(kid);
                    }
                }
            }
        }
        return false;
    }

    private static COSArray group(COSDictionary parent, COSArray kids) {
        COSArray level = kids;
        while (level.size() > LongArrays.MAX) {
            COSArray up = new COSArray();
            for (int i = 0; i < level.size(); i += GROUP) {
                COSDictionary g = new COSDictionary();
                g.setItem(COSName.TYPE, STRUCT_ELEM);
                g.setItem(COSName.S, NON_STRUCT);
                g.setItem(COSName.P, parent);
                COSBase pg = parent.getItem(COSName.PG);
                if (pg != null) {
                    g.setItem(COSName.PG, pg);
                }
                COSArray part = new COSArray();
                for (int j = i; j < Math.min(level.size(), i + GROUP); j++) {
                    COSBase kid = level.get(j);
                    part.add(kid);
                    COSDictionary d = ContentGraph.dict(kid);
                    if (d != null && d.containsKey(COSName.S)) {
                        d.setItem(COSName.P, g);
                    }
                }
                g.setItem(COSName.K, part);
                up.add(g);
            }
            level = up;
        }
        return level;
    }
}
