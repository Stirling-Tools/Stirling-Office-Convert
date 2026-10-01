package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.Collections;
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
    private final Map<COSDictionary, List<COSDictionary>> kids = new IdentityHashMap<>();
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
        for (COSDictionary kid : kids(node)) {
            if (isNode(kid)) {
                int count = kid.getInt(COSName.COUNT, 0);
                if (pageNum <= encountered + count) {
                    return find(pageNum, kid, encountered, seen);
                }
                encountered += count;
            } else {
                encountered++;
                if (pageNum == encountered) {
                    return find(pageNum, kid, encountered, seen);
                }
            }
        }
        throw new IllegalStateException("1-based index not found: " + pageNum);
    }

    private List<COSDictionary> kids(COSDictionary node) {
        List<COSDictionary> known = kids.get(node);
        if (known != null) {
            return known;
        }
        COSArray array = node.getCOSArray(COSName.KIDS);
        if (array == null) {
            return Collections.emptyList();
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
        kids.put(node, out);
        return out;
    }

    private static boolean isNode(COSDictionary node) {
        return node != null && (COSName.PAGES.equals(node.getCOSName(COSName.TYPE)) || node.containsKey(COSName.KIDS));
    }
}
