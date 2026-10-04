package stirling.software.officeconvert.pdfa;

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

final class StructureSlimming {

    private static final COSName STRUCT_ELEM = COSName.getPDFName("StructElem");

    private StructureSlimming() {}

    static void run(PDDocument doc) {
        COSDictionary root = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject()
                .getDictionaryObject(COSName.STRUCT_TREE_ROOT));
        if (root == null) {
            return;
        }
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<COSDictionary> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && seen.size() < 5_000_000) {
            COSDictionary e = stack.pop();
            if (!seen.add(e)) {
                continue;
            }
            if (e != root && STRUCT_ELEM.equals(e.getCOSName(COSName.TYPE))) {
                e.removeItem(COSName.TYPE);
            }
            COSBase k = e.getDictionaryObject(COSName.K);
            if (k instanceof COSArray a && a.size() == 1 && e != root) {
                e.setItem(COSName.K, a.get(0));
                k = e.getDictionaryObject(COSName.K);
            }
            if (k instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    COSDictionary d = ContentGraph.dict(a.getObject(i));
                    if (d != null && d.containsKey(COSName.S)) {
                        stack.push(d);
                    }
                }
            } else if (ContentGraph.dict(k) != null && ContentGraph.dict(k).containsKey(COSName.S)) {
                stack.push(ContentGraph.dict(k));
            }
        }
    }
}
