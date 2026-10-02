package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

final class PageIndex {

    private final PDDocument document;
    private final Map<COSDictionary, Kids> kids = new IdentityHashMap<>();
    private COSDictionary root;

    PageIndex(PDDocument document) {
        this.document = document;
    }

    PDRectangle cropBox(int index) {
        return new PDPage(find(index)).getCropBox();
    }

    private COSDictionary find(int index) {
        COSDictionary tree = root();
        COSDictionary found;
        if (COSName.PAGE.equals(tree.getCOSName(COSName.TYPE))) {
            if (index + 1 < 1) {
                throw new IndexOutOfBoundsException("Index out of bounds: " + (index + 1));
            }
            if (index + 1 > 1) {
                throw new IndexOutOfBoundsException("1-based index out of bounds: " + (index + 1));
            }
            found = tree;
        } else {
            found = find(index + 1, tree, 0, new HashSet<>());
        }
        COSName type = found.getCOSName(COSName.TYPE);
        if (type == null) {
            found.setItem(COSName.TYPE, COSName.PAGE);
        } else if (!COSName.PAGE.equals(type)) {
            throw new IllegalStateException("Expected 'Page' but found " + type);
        }
        return found;
    }

    private COSDictionary root() {
        if (root == null) {
            root = document.getDocumentCatalog().getPages().getCOSObject();
            if (root == null) {
                throw new IllegalArgumentException("page tree root cannot be null");
            }
        }
        return root;
    }

    private COSDictionary find(int pageNum, COSDictionary node, int encountered, Set<COSDictionary> seen) {
        if (pageNum < 1) {
            throw new IndexOutOfBoundsException("Index out of bounds: " + pageNum);
        }
        if (!seen.add(node)) {
            throw new IllegalStateException("Possible recursion found when searching for page " + pageNum);
        }
        if (!isNode(node)) {
            if (encountered == pageNum) {
                return node;
            }
            throw new IllegalStateException("1-based index not found: " + pageNum);
        }
        if (pageNum > encountered + node.getInt(COSName.COUNT, 0)) {
            throw new IndexOutOfBoundsException("1-based index out of bounds: " + pageNum);
        }
        Kids kids = kids(node);
        for (int i = 0; i < kids.dicts.length; i++) {
            if (kids.node[i]) {
                int count = kids.count[i];
                if (pageNum <= encountered + count) {
                    return find(pageNum, kids.dicts[i], encountered, seen);
                }
                encountered += count;
            } else {
                encountered++;
                if (pageNum == encountered) {
                    return find(pageNum, kids.dicts[i], encountered, seen);
                }
            }
        }
        throw new IllegalStateException("1-based index not found: " + pageNum);
    }

    private record Kids(COSDictionary[] dicts, boolean[] node, int[] count) {}

    private Kids kids(COSDictionary node) {
        Kids known = kids.get(node);
        if (known != null) {
            return known;
        }
        COSArray array = node.getCOSArray(COSName.KIDS);
        if (array == null) {
            return new Kids(new COSDictionary[0], new boolean[0], new int[0]);
        }
        List<COSDictionary> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            COSBase kid = array.getObject(i);
            if (kid instanceof COSDictionary dict) {
                out.add(dict);
            } else if (kid == null) {
                COSDictionary blank = new COSDictionary();
                blank.setItem(COSName.TYPE, COSName.PAGE);
                array.set(i, blank);
                out.add(blank);
            }
        }
        COSDictionary[] dicts = out.toArray(new COSDictionary[0]);
        boolean[] nodes = new boolean[dicts.length];
        int[] counts = new int[dicts.length];
        for (int i = 0; i < dicts.length; i++) {
            nodes[i] = isNode(dicts[i]);
            counts[i] = nodes[i] ? dicts[i].getInt(COSName.COUNT, 0) : 0;
        }
        Kids built = new Kids(dicts, nodes, counts);
        kids.put(node, built);
        return built;
    }

    private static boolean isNode(COSDictionary node) {
        return node != null && (COSName.PAGES.equals(node.getCOSName(COSName.TYPE)) || node.containsKey(COSName.KIDS));
    }
}
