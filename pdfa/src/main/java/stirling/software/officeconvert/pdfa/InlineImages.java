package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.filter.FilterFactory;

final class InlineImages {

    enum Outcome {
        UNCHANGED,
        REENCODED,
        UNREADABLE
    }

    private static final Set<String> TRANSPORT = Set.of("AHx", "A85", "LZW", "Fl", "RL", "ASCIIHexDecode",
            "ASCII85Decode", "LZWDecode", "FlateDecode", "RunLengthDecode");

    private static final Set<String> ALLOWED = Set.of("AHx", "A85", "Fl", "RL", "CCF", "DCT", "ASCIIHexDecode",
            "ASCII85Decode", "FlateDecode", "RunLengthDecode", "CCITTFaxDecode", "DCTDecode");

    private InlineImages() {}

    static Outcome fix(Operator op) {
        COSDictionary params = op.getImageParameters();
        if (op.getImageData() == null) {
            return Outcome.UNREADABLE;
        }
        if (params == null) {
            return Outcome.UNCHANGED;
        }
        COSName key = params.containsKey(COSName.F) ? COSName.F : COSName.FILTER;
        List<COSName> filters = filters(params.getDictionaryObject(key));
        if (filters == null) {
            return Outcome.UNREADABLE;
        }
        boolean ok = true;
        for (COSName f : filters) {
            ok &= ALLOWED.contains(f.getName());
        }
        if (ok) {
            return Outcome.UNCHANGED;
        }
        List<COSBase> parms = parms(params, filters.size());
        byte[] data = op.getImageData();
        int i = 0;
        try {
            for (; i < filters.size() && TRANSPORT.contains(filters.get(i).getName()); i++) {
                COSDictionary one = new COSDictionary();
                one.setItem(COSName.FILTER, filters.get(i));
                if (parms.get(i) instanceof COSDictionary p) {
                    one.setItem(COSName.DECODE_PARMS, p);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(new ByteArrayInputStream(data), out, one, 0);
                data = out.toByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return Outcome.UNREADABLE;
        }
        List<COSName> rest = new ArrayList<>(filters.subList(i, filters.size()));
        for (COSName f : rest) {
            if (!ALLOWED.contains(f.getName())) {
                return Outcome.UNREADABLE;
            }
        }
        params.removeItem(COSName.F);
        params.removeItem(COSName.FILTER);
        params.removeItem(COSName.DP);
        params.removeItem(COSName.DECODE_PARMS);
        if (rest.size() == 1) {
            params.setItem(COSName.F, rest.get(0));
        } else if (!rest.isEmpty()) {
            params.setItem(COSName.F, new COSArray(rest));
        }
        List<COSBase> restParms = parms.subList(i, parms.size());
        if (restParms.stream().anyMatch(p -> p instanceof COSDictionary)) {
            params.setItem(COSName.DP, restParms.size() == 1 ? restParms.get(0) : new COSArray(restParms));
        }
        op.setImageData(data);
        return Outcome.REENCODED;
    }

    private static List<COSName> filters(COSBase f) {
        List<COSName> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        if (f instanceof COSName n) {
            out.add(n);
            return out;
        }
        if (!(f instanceof COSArray a)) {
            return null;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!(a.getObject(i) instanceof COSName n)) {
                return null;
            }
            out.add(n);
        }
        return out;
    }

    private static List<COSBase> parms(COSDictionary params, int n) {
        COSBase p = params.getDictionaryObject(COSName.DP);
        if (p == null) {
            p = params.getDictionaryObject(COSName.DECODE_PARMS);
        }
        List<COSBase> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (p instanceof COSArray a) {
                out.add(i < a.size() ? a.getObject(i) : COSNull.NULL);
            } else {
                out.add(i == 0 && p != null ? p : COSNull.NULL);
            }
        }
        return out;
    }
}
