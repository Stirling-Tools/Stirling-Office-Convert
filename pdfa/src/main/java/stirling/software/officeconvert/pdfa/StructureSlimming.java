package stirling.software.officeconvert.pdfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
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
        breakCycles(root);
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

    private record Frame(COSDictionary node, List<COSDictionary> kids, int[] next) {}

    private static void breakCycles(COSDictionary root) {
        Set<COSDictionary> path = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> done = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, kids(root), new int[1]));
        path.add(root);
        while (!stack.isEmpty() && done.size() < 5_000_000) {
            Frame f = stack.peek();
            if (f.next()[0] >= f.kids().size()) {
                stack.pop();
                path.remove(f.node());
                done.add(f.node());
                continue;
            }
            COSDictionary kid = f.kids().get(f.next()[0]++);
            if (path.contains(kid)) {
                unlink(f.node(), kid);
            } else if (!done.contains(kid)) {
                path.add(kid);
                stack.push(new Frame(kid, kids(kid), new int[1]));
            }
        }
    }

    private static List<COSDictionary> kids(COSDictionary e) {
        List<COSDictionary> out = new ArrayList<>();
        COSBase k = e.getDictionaryObject(COSName.K);
        if (k instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                COSDictionary d = ContentGraph.dict(a.getObject(i));
                if (d != null && d.containsKey(COSName.S)) {
                    out.add(d);
                }
            }
        } else if (ContentGraph.dict(k) != null && ContentGraph.dict(k).containsKey(COSName.S)) {
            out.add(ContentGraph.dict(k));
        }
        return out;
    }

    private static void unlink(COSDictionary parent, COSDictionary kid) {
        COSBase k = parent.getDictionaryObject(COSName.K);
        if (k instanceof COSArray a) {
            for (int i = a.size() - 1; i >= 0; i--) {
                if (ContentGraph.dict(a.getObject(i)) == kid) {
                    a.remove(i);
                }
            }
            if (a.size() == 0) {
                parent.removeItem(COSName.K);
            }
        } else if (ContentGraph.dict(k) == kid) {
            parent.removeItem(COSName.K);
        }
    }
}
