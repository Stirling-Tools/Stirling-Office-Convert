package stirling.software.officeconvert.pdfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;

final class ContentLimits {

    static final int STRING_CHUNK = 32_760;

    private ContentLimits() {}

    static List<Object> fix(List<Object> operation, PdfALevel level) {
        Operator op = (Operator) operation.get(operation.size() - 1);
        boolean changed = false;
        List<Object> o = new ArrayList<>(operation);
        for (int k = 0; k < o.size() - 1; k++) {
            Object v = o.get(k);
            if (v instanceof COSName n) {
                COSName s = Limits.name(n);
                if (s != n) {
                    o.set(k, s);
                    changed = true;
                }
            } else if (v instanceof COSDictionary d) {
                changed |= names(d);
            }
        }
        if ("BI".equals(op.getName()) && op.getImageParameters() != null) {
            changed |= names(op.getImageParameters());
        }
        String name = op.getName();
        int maxString = Limits.maxString(level);
        if (("Tj".equals(name) || "'".equals(name) || "\"".equals(name)) && o.get(o.size() - 2) instanceof COSString s
                && s.getBytes().length > maxString) {
            return showSplit(o, s);
        }
        if ("TJ".equals(name) && o.size() == 2 && o.get(0) instanceof COSArray a) {
            List<COSBase> items = new ArrayList<>();
            boolean split = false;
            for (int i = 0; i < a.size(); i++) {
                if (a.get(i) instanceof COSString s && s.getBytes().length > maxString) {
                    for (byte[] chunk : chunks(s.getBytes())) {
                        items.add(new COSString(chunk));
                    }
                    split = true;
                } else {
                    items.add(a.get(i));
                }
            }
            int maxArray = Limits.maxArray(level);
            if (split || items.size() > maxArray) {
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < items.size(); i += maxArray) {
                    COSArray part = new COSArray(items.subList(i, Math.min(items.size(), i + maxArray)));
                    out.add(part);
                    out.add(Operator.getOperator("TJ"));
                }
                return out;
            }
        }
        return changed ? o : null;
    }

    private static List<Object> showSplit(List<Object> o, COSString s) {
        List<byte[]> parts = chunks(s.getBytes());
        List<Object> out = new ArrayList<>(o.subList(0, o.size() - 2));
        out.add(new COSString(parts.get(0)));
        out.add(o.get(o.size() - 1));
        for (byte[] p : parts.subList(1, parts.size())) {
            out.add(new COSString(p));
            out.add(Operator.getOperator("Tj"));
        }
        return out;
    }

    private static List<byte[]> chunks(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < b.length; i += STRING_CHUNK) {
            out.add(Arrays.copyOfRange(b, i, Math.min(b.length, i + STRING_CHUNK)));
        }
        return out;
    }

    private static boolean names(COSDictionary d) {
        boolean changed = false;
        for (Map.Entry<COSName, COSBase> e : new ArrayList<>(d.entrySet())) {
            COSName k = Limits.name(e.getKey());
            COSBase v = e.getValue() instanceof COSName n ? Limits.name(n) : e.getValue();
            if (k != e.getKey() || v != e.getValue()) {
                d.removeItem(e.getKey());
                d.setItem(k, v);
                changed = true;
            }
        }
        return changed;
    }
}
