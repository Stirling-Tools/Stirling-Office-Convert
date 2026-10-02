package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class InvisibleText {

    private static final int MAX_DEPTH = 12;

    private static final Set<String> KEPT = Set.of("q", "Q", "cm", "BT", "ET", "Tc", "Tw", "Tz", "TL", "Tf", "Ts",
            "Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"", "BMC", "BDC", "EMC");

    private static final Set<String> TEXT = Set.of("Tj", "TJ", "'", "\"");

    private final PDDocument doc;

    private final Map<COSStream, Optional<COSStream>> forms = new IdentityHashMap<>();

    InvisibleText(PDDocument doc) {
        this.doc = doc;
    }

    COSStream of(COSStream form) throws IOException {
        return of(form, 0);
    }

    private COSStream of(COSStream form, int depth) throws IOException {
        if (!COSName.FORM.equals(form.getCOSName(COSName.SUBTYPE))) {
            return null;
        }
        Optional<COSStream> known = forms.get(form);
        if (known != null || depth > MAX_DEPTH) {
            return known == null ? null : known.orElse(null);
        }
        forms.put(form, Optional.empty());
        COSDictionary res = ContentGraph.dict(form.getDictionaryObject(COSName.RESOURCES));
        List<Object> tokens;
        try {
            tokens = ContentTokens.parse(List.of(form));
        } catch (IOException e) {
            Decoded.rethrowFatal(e);
            return null;
        }
        List<Object> out = new ArrayList<>();
        out.add(COSInteger.THREE);
        out.add(Operator.getOperator("Tr"));
        Map<COSName, COSStream> inner = new LinkedHashMap<>();
        boolean text = false;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> operation = tokens.subList(start, i + 1);
            start = i + 1;
            String name = op.getName();
            if (KEPT.contains(name)) {
                out.addAll(operation);
                text |= TEXT.contains(name);
            } else if ("Do".equals(name) && operation.size() == 2 && operation.get(0) instanceof COSName xn
                    && TransparencyScan.lookup(res, COSName.XOBJECT, xn) instanceof COSStream child) {
                COSStream hidden = of(child, depth + 1);
                if (hidden != null) {
                    inner.put(xn, hidden);
                    out.addAll(operation);
                    text = true;
                }
            }
        }
        if (!text) {
            return null;
        }
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.BBOX, form.getItem(COSName.BBOX));
        if (form.getItem(COSName.MATRIX) != null) {
            s.setItem(COSName.MATRIX, form.getItem(COSName.MATRIX));
        }
        COSDictionary own = new COSDictionary();
        if (res != null) {
            for (COSName k : List.of(COSName.FONT, COSName.PROPERTIES)) {
                if (res.getItem(k) != null) {
                    own.setItem(k, res.getItem(k));
                }
            }
        }
        if (!inner.isEmpty()) {
            COSDictionary x = new COSDictionary();
            inner.forEach(x::setItem);
            own.setItem(COSName.XOBJECT, x);
        }
        s.setItem(COSName.RESOURCES, own);
        ContentTokens.write(s, out);
        forms.put(form, Optional.of(s));
        return s;
    }
}
