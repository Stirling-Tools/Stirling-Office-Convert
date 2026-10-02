package stirling.software.officeconvert.pdfa;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

final class References {

    private static final Set<String> KEEP_INDIRECT = Set.of("Catalog", "Pages", "Page", "StructTreeRoot", "StructElem",
            "Annot", "OCG", "OCMD", "Outlines", "Thread", "Bead", "Sig", "Font", "FontDescriptor", "Metadata",
            "EmbeddedFile", "Filespec", "XObject", "Encoding", "Template");

    private References() {}

    static Set<COSBase> once(COSDictionary... roots) {
        Map<COSBase, Integer> count = new IdentityHashMap<>();
        Deque<COSBase> stack = new ArrayDeque<>();
        for (COSDictionary r : roots) {
            if (r != null && count.put(r, 2) == null) {
                stack.push(r);
            }
        }
        while (!stack.isEmpty()) {
            COSBase b = stack.pop();
            if (b instanceof COSDictionary d) {
                for (Map.Entry<COSName, COSBase> e : d.entrySet()) {
                    visit(e.getValue(), count, stack);
                }
            } else {
                COSArray a = (COSArray) b;
                for (int i = 0, n = a.size(); i < n; i++) {
                    visit(a.get(i), count, stack);
                }
            }
        }
        Set<COSBase> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<COSBase, Integer> e : count.entrySet()) {
            if (e.getValue() == 1 && movable(e.getKey())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static void visit(COSBase v, Map<COSBase, Integer> count, Deque<COSBase> stack) {
        COSBase t = v instanceof COSObject o ? o.getObject() : v;
        if (t instanceof COSDictionary || t instanceof COSArray) {
            Integer n = count.put(t, 1);
            if (n == null) {
                stack.push(t);
            } else {
                count.put(t, n + 1);
            }
        }
    }

    private static boolean movable(COSBase b) {
        if (b instanceof COSArray) {
            return true;
        }
        if (b instanceof COSStream || !(b instanceof COSDictionary d)) {
            return false;
        }
        COSName type = d.getCOSName(COSName.TYPE);
        if (type != null && KEEP_INDIRECT.contains(type.getName())) {
            return false;
        }
        return !(d.containsKey(COSName.RECT) && d.containsKey(COSName.SUBTYPE)) && !d.containsKey(COSName.FT)
                && !d.containsKey(COSName.PARENT) && !(d.containsKey(COSName.S) && d.containsKey(COSName.P))
                && !d.containsKey(COSName.FIRST) && !d.containsKey(COSName.KIDS);
    }
}
