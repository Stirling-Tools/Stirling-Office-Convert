package stirling.software.officeconvert.pdfa;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

final class CosEquality {

    private static final int MAX_DEPTH = 32;

    private static final Set<COSName> ENCODING = Set.of(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS);

    private final Map<COSBase, Set<COSBase>> assumed = new IdentityHashMap<>();

    private CosEquality() {}

    static boolean same(COSBase a, COSBase b) {
        return new CosEquality().same(a, b, 0);
    }

    private boolean same(COSBase a, COSBase b, int depth) {
        a = a instanceof COSObject o ? o.getObject() : a;
        b = b instanceof COSObject o ? o.getObject() : b;
        if (a == b) {
            return true;
        }
        if (a == null || b == null || depth > MAX_DEPTH) {
            return false;
        }
        if (a instanceof COSNumber x && b instanceof COSNumber y) {
            return x.floatValue() == y.floatValue();
        }
        if ((a instanceof COSArray || a instanceof COSDictionary)
                && !assumed.computeIfAbsent(a, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(b)) {
            return true;
        }
        if (a instanceof COSArray x && b instanceof COSArray y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof COSStream x && b instanceof COSStream y) {
            if (!entries(x, y, depth)) {
                return false;
            }
            byte[] left = StreamFixer.read(x);
            return left != null && Arrays.equals(left, StreamFixer.read(y));
        }
        if (a instanceof COSDictionary x && b instanceof COSDictionary y) {
            return !(a instanceof COSStream) && !(b instanceof COSStream) && entries(x, y, depth);
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    private boolean entries(COSDictionary x, COSDictionary y, int depth) {
        int n = 0;
        for (Map.Entry<COSName, COSBase> e : x.entrySet()) {
            if (ENCODING.contains(e.getKey()) && x instanceof COSStream) {
                continue;
            }
            n++;
            if (!same(e.getValue(), y.getItem(e.getKey()), depth + 1)) {
                return false;
            }
        }
        int m = 0;
        for (COSName k : y.keySet()) {
            if (!(ENCODING.contains(k) && y instanceof COSStream)) {
                m++;
            }
        }
        return n == m;
    }
}
