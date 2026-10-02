package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import stirling.software.officeconvert.extract.PdfFiles;

final class OptionalContentRemoval {

    private static final COSName OCMD = COSName.getPDFName("OCMD");

    private static final COSName BASE_STATE = COSName.getPDFName("BaseState");

    private static final COSName ON = COSName.getPDFName("ON");

    private static final COSName OFF = COSName.getPDFName("OFF");

    private final Set<COSBase> off = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<COSBase> on = Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean baseOff;

    private int removed;

    private OptionalContentRemoval() {}

    static void run(PDDocument doc, Report report) throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary props = ContentGraph.dict(cat.getDictionaryObject(COSName.OCPROPERTIES));
        if (props == null) {
            return;
        }
        OptionalContentRemoval r = new OptionalContentRemoval();
        COSDictionary d = ContentGraph.dict(props.getDictionaryObject(COSName.D));
        if (d != null) {
            r.baseOff = OFF.equals(d.getDictionaryObject(BASE_STATE));
            r.collect(d.getDictionaryObject(OFF), r.off);
            r.collect(d.getDictionaryObject(ON), r.on);
        }
        for (PDPage page : doc.getPages()) {
            COSArray annots = ContentGraph.array(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
            if (annots == null) {
                continue;
            }
            COSArray kept = new COSArray();
            for (int i = 0; i < annots.size(); i++) {
                COSDictionary a = ContentGraph.dict(annots.getObject(i));
                if (a != null && r.hidden(a.getDictionaryObject(COSName.OC), 0)) {
                    r.removed++;
                    continue;
                }
                if (a != null) {
                    a.removeItem(COSName.OC);
                }
                kept.add(annots.get(i));
            }
            page.getCOSObject().setItem(COSName.ANNOTS, kept);
        }
        ContentGraph graph = ContentGraph.of(doc);
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            r.edit(n);
        }
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.owner() instanceof COSStream s) {
                s.removeItem(COSName.OC);
            }
        }
        cat.removeItem(COSName.OCPROPERTIES);
        report.warn("Removed optional content (layers), which PDF/A-1 does not allow; only what was visible is kept");
    }

    private void collect(COSBase list, Set<COSBase> into) {
        COSArray a = ContentGraph.array(list);
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.size(); i++) {
            COSBase b = a.getObject(i);
            if (b != null) {
                into.add(b);
            }
        }
    }

    private boolean groupHidden(COSBase g) {
        return off.contains(g) || baseOff && !on.contains(g);
    }

    private boolean hidden(COSBase oc, int depth) {
        COSDictionary d = ContentGraph.dict(oc);
        if (d == null || depth > 16) {
            return false;
        }
        if (!OCMD.equals(d.getCOSName(COSName.TYPE))) {
            return groupHidden(d);
        }
        COSBase groups = d.getDictionaryObject(COSName.OCGS);
        List<COSBase> list = new ArrayList<>();
        if (groups instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                list.add(a.getObject(i));
            }
        } else if (groups != null) {
            list.add(groups);
        }
        if (list.isEmpty()) {
            return false;
        }
        COSName p = d.getCOSName(COSName.P);
        String policy = p == null ? "AnyOn" : p.getName();
        long hiddenCount = list.stream().filter(this::groupHidden).count();
        return switch (policy) {
            case "AllOn" -> hiddenCount > 0;
            case "AnyOff" -> hiddenCount == 0;
            case "AllOff" -> hiddenCount < list.size();
            default -> hiddenCount == list.size();
        };
    }

    private void edit(ContentGraph.Node n) throws IOException {
        List<Object> tokens;
        try {
            tokens = ContentTokens.parse(n.streams());
        } catch (IOException e) {
            return;
        }
        COSDictionary res = n.resources();
        List<Object> out = new ArrayList<>(tokens.size());
        boolean changed = false;
        int skipDepth = 0;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (!(t instanceof Operator op)) {
                continue;
            }
            List<Object> operation = tokens.subList(start, i + 1);
            start = i + 1;
            String name = op.getName();
            if ("BDC".equals(name) || "BMC".equals(name)) {
                depth++;
                if (skipDepth == 0 && "BDC".equals(name) && operation.size() >= 3
                        && COSName.OC.equals(operation.get(0)) && hidden(property(res, operation.get(1)), 0)) {
                    skipDepth = depth;
                    changed = true;
                    continue;
                }
            } else if ("EMC".equals(name)) {
                if (skipDepth > 0 && depth == skipDepth) {
                    skipDepth = 0;
                    depth--;
                    continue;
                }
                depth = Math.max(0, depth - 1);
            } else if ("Do".equals(name) && skipDepth == 0 && operation.size() >= 2
                    && operation.get(0) instanceof COSName xn && hidden(xobjectOc(res, xn), 0)) {
                changed = true;
                continue;
            }
            if (skipDepth == 0) {
                out.addAll(operation);
            }
        }
        if (changed) {
            ContentTokens.replace(n, out);
        }
    }

    private static COSBase property(COSDictionary res, Object operand) {
        if (operand instanceof COSDictionary d) {
            return d;
        }
        if (!(operand instanceof COSName name) || res == null) {
            return null;
        }
        COSDictionary props = ContentGraph.dict(res.getDictionaryObject(COSName.PROPERTIES));
        return props == null ? null : props.getDictionaryObject(name);
    }

    private static COSBase xobjectOc(COSDictionary res, COSName name) {
        COSDictionary x = res == null ? null : ContentGraph.dict(res.getDictionaryObject(COSName.XOBJECT));
        COSDictionary xo = x == null ? null : ContentGraph.dict(x.getDictionaryObject(name));
        return xo == null ? null : xo.getDictionaryObject(COSName.OC);
    }
}
