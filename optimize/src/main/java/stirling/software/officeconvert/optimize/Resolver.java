package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;

final class Resolver {

    record Found(Roles.Loc loc, COSBase value) {}

    private static final int CACHE = 256;

    private final Source source;

    private final Index index;

    private final Map<Integer, COSBase> cache = new LinkedHashMap<>(CACHE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, COSBase> eldest) {
            return size() > CACHE;
        }
    };

    Resolver(Source source, Index index) {
        this.source = source;
        this.index = index;
    }

    COSBase load(int id) throws IOException {
        COSBase b = cache.get(id);
        if (b == null) {
            b = source.load(index.key(id));
            cache.put(id, b);
        }
        return b;
    }

    void forget(int id) {
        cache.remove(id);
    }

    Found find(Roles.Loc loc) throws IOException {
        int holder = loc.holder();
        COSBase v = load(holder);
        int from = 0;
        for (int k = 0; k < loc.path().length; k++) {
            Object step = loc.path()[k];
            COSBase next;
            if (step instanceof COSName key && v instanceof COSDictionary d) {
                next = d.getItem(key);
            } else if (step instanceof Integer i && v instanceof COSArray a && i < a.size()) {
                next = a.get(i);
            } else {
                return null;
            }
            if (next instanceof COSObject o && o.getKey() != null) {
                holder = index.id(o.getKey());
                next = load(holder);
                from = k + 1;
            }
            v = next;
        }
        if (v == null) {
            return null;
        }
        Object[] rest = java.util.Arrays.copyOfRange(loc.path(), from, loc.path().length);
        return new Found(new Roles.Loc(holder, rest), v);
    }
}
