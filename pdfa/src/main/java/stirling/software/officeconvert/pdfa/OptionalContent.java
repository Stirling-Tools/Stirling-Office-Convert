package stirling.software.officeconvert.pdfa;

import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class OptionalContent {

    private static final COSName AS = COSName.getPDFName("AS");

    private static final COSName CONFIGS = COSName.getPDFName("Configs");

    private static final COSName ORDER = COSName.getPDFName("Order");

    private OptionalContent() {}

    static void configure(PDDocument doc) throws InterruptedIOException {
        COSDictionary oc = ContentGraph.dict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OCPROPERTIES));
        if (oc == null) {
            return;
        }
        COSArray groups = ContentGraph.array(oc.getDictionaryObject(COSName.OCGS));
        Set<String> names = new HashSet<>();
        COSDictionary d = ContentGraph.dict(oc.getDictionaryObject(COSName.D));
        if (d == null) {
            d = new COSDictionary();
            oc.setItem(COSName.D, d);
        }
        config(d, groups, names, "Default");
        COSArray configs = ContentGraph.array(oc.getDictionaryObject(CONFIGS));
        if (configs != null) {
            for (int i = 0; i < configs.size(); i++) {
                COSDictionary c = ContentGraph.dict(configs.getObject(i));
                if (c != null) {
                    config(c, groups, names, "Configuration " + (i + 1));
                }
            }
        }
    }

    private static void config(COSDictionary c, COSArray groups, Set<String> names, String fallback)
            throws InterruptedIOException {
        c.removeItem(AS);
        String name = c.getDictionaryObject(COSName.NAME) instanceof COSString s ? s.getString().strip() : "";
        if (name.isEmpty() || !names.add(name)) {
            String n = fallback;
            for (int k = 2; !names.add(n); k++) {
                n = fallback + " " + k;
            }
            c.setString(COSName.NAME, n);
        }
        COSArray order = ContentGraph.array(c.getDictionaryObject(ORDER));
        if (order == null || groups == null) {
            return;
        }
        Set<COSBase> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(order, listed, new Visits(), 0);
        for (int i = 0; i < groups.size(); i++) {
            COSBase g = groups.getObject(i);
            if (g instanceof COSDictionary && !listed.contains(g)) {
                order.add(groups.get(i));
            }
        }
    }

    private static void collect(COSArray order, Set<COSBase> listed, Visits visits, int depth)
            throws InterruptedIOException {
        if (depth > 32 || !visits.first(order)) {
            return;
        }
        for (int i = 0; i < order.size(); i++) {
            COSBase b = order.getObject(i);
            if (b instanceof COSArray a) {
                collect(a, listed, visits, depth + 1);
            } else if (b instanceof COSDictionary) {
                listed.add(b);
            }
        }
    }
}
