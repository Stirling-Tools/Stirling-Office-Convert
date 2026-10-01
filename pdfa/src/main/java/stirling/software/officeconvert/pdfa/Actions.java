package stirling.software.officeconvert.pdfa;

import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

final class Actions {

    private static final Set<String> ALLOWED = Set.of("GoTo", "GoToR", "GoToE", "Thread", "URI", "Named",
            "SubmitForm");

    private static final Set<String> ALLOWED_A1 = Set.of("GoTo", "GoToR", "Thread", "URI", "Named");

    private static final Set<String> NAMED = Set.of("NextPage", "PrevPage", "FirstPage", "LastPage");

    private Actions() {}

    static COSBase filter(COSBase action, PdfALevel level, Report report, int depth) {
        COSDictionary a = ContentGraph.dict(action);
        if (a == null) {
            return action instanceof COSArray ? null : action;
        }
        if (depth > 64) {
            return null;
        }
        COSName s = a.getCOSName(COSName.S);
        String type = s == null ? "" : s.getName();
        boolean ok = (level.part() == 1 ? ALLOWED_A1 : ALLOWED).contains(type);
        if (ok && "Named".equals(type)) {
            COSName n = a.getCOSName(COSName.N);
            ok = n != null && NAMED.contains(n.getName());
        }
        COSBase next = a.getDictionaryObject(COSName.NEXT);
        COSBase kept = null;
        if (next instanceof COSArray arr) {
            COSArray out = new COSArray();
            for (int i = 0; i < arr.size(); i++) {
                COSBase f = filter(arr.getObject(i), level, report, depth + 1);
                if (f != null) {
                    out.add(f);
                }
            }
            kept = out.size() == 0 ? null : out.size() == 1 ? out.getObject(0) : out;
        } else if (next != null) {
            kept = filter(next, level, report, depth + 1);
        }
        if (!ok) {
            report.warn("Removed " + (type.isEmpty() ? "an unknown" : "a " + type) + " action, which PDF/A does not allow");
            return kept;
        }
        if (kept == null) {
            a.removeItem(COSName.NEXT);
        } else {
            a.setItem(COSName.NEXT, kept);
        }
        return a;
    }

    static void filterKey(COSDictionary owner, COSName key, PdfALevel level, Report report) {
        COSBase a = owner.getDictionaryObject(key);
        if (a == null) {
            return;
        }
        if (a instanceof COSArray) {
            if (key.equals(COSName.OPEN_ACTION)) {
                return;
            }
            owner.removeItem(key);
            return;
        }
        COSBase kept = filter(a, level, report, 0);
        if (kept == null) {
            owner.removeItem(key);
        } else {
            owner.setItem(key, kept);
        }
    }
}
