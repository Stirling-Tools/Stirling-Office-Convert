package stirling.software.officeconvert.pdfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;

final class UndefinedResources {

    private final COSDictionary resources;

    private boolean textOff;

    private final Deque<Boolean> saved = new ArrayDeque<>();

    private int removed;

    private UndefinedResources(COSDictionary resources) {
        this.resources = resources;
    }

    static Map<COSStream, COSDictionary> glyphResources(ContentGraph graph) {
        Map<COSStream, COSDictionary> out = new IdentityHashMap<>();
        for (Map.Entry<COSDictionary, COSDictionary> e : graph.type3Fonts().entrySet()) {
            COSDictionary own = ContentGraph.dict(e.getKey().getDictionaryObject(COSName.RESOURCES));
            COSDictionary res = own != null ? own : e.getValue();
            COSDictionary procs = ContentGraph.dict(e.getKey().getDictionaryObject(COSName.CHAR_PROCS));
            if (procs == null) {
                continue;
            }
            for (COSBase v : procs.getValues()) {
                COSBase s = v instanceof COSObject o ? o.getObject() : v;
                if (s instanceof COSStream stream) {
                    out.put(stream, res);
                }
            }
        }
        return out;
    }

    static UndefinedResources in(ContentGraph.Node n, Map<COSStream, COSDictionary> glyphs) {
        COSDictionary res;
        if (n.kind() == ContentGraph.Kind.GLYPH) {
            res = glyphs.containsKey(n.owner()) ? glyphs.get(n.owner()) : n.resources();
        } else {
            COSDictionary own = ContentGraph.dict(n.owner().getDictionaryObject(COSName.RESOURCES));
            res = own != null ? own : n.resources();
        }
        return new UndefinedResources(res);
    }

    int removed() {
        return removed;
    }

    List<Object> fix(List<Object> operation) {
        String op = ((Operator) operation.get(operation.size() - 1)).getName();
        switch (op) {
            case "q" -> saved.push(textOff);
            case "Q" -> textOff = saved.isEmpty() ? textOff : saved.pop();
            case "Tf" -> {
                boolean missing = operation.size() >= 3 && operation.get(0) instanceof COSName name
                        && !defined(COSName.FONT, name);
                textOff = missing;
                if (missing) {
                    removed++;
                    return List.of();
                }
            }
            case "gs" -> {
                return dropIfMissing(operation, COSName.EXT_G_STATE);
            }
            case "Do" -> {
                return dropIfMissing(operation, COSName.XOBJECT);
            }
            case "sh" -> {
                return dropIfMissing(operation, COSName.SHADING);
            }
            case "BDC" -> {
                if (operation.size() == 3 && operation.get(1) instanceof COSName name
                        && !defined(COSName.PROPERTIES, name)) {
                    removed++;
                    return List.of(operation.get(0), Operator.getOperator("BMC"));
                }
            }
            case "DP" -> {
                return dropIfMissing(operation, COSName.PROPERTIES);
            }
            case "Tj", "TJ" -> {
                if (textOff) {
                    removed++;
                    return List.of();
                }
            }
            case "'" -> {
                if (textOff) {
                    removed++;
                    return List.of(Operator.getOperator("T*"));
                }
            }
            case "\"" -> {
                if (textOff) {
                    removed++;
                    List<Object> out = new ArrayList<>();
                    if (operation.size() == 4 && !(operation.get(0) instanceof COSString)
                            && !(operation.get(1) instanceof COSString)) {
                        out.add(operation.get(0));
                        out.add(Operator.getOperator("Tw"));
                        out.add(operation.get(1));
                        out.add(Operator.getOperator("Tc"));
                    }
                    out.add(Operator.getOperator("T*"));
                    return out;
                }
            }
            default -> {
            }
        }
        return operation;
    }

    private List<Object> dropIfMissing(List<Object> operation, COSName kind) {
        if (operation.size() >= 2 && operation.get(operation.size() - 2) instanceof COSName name
                && !defined(kind, name)) {
            removed++;
            return List.of();
        }
        return operation;
    }

    private boolean defined(COSName kind, COSName name) {
        COSDictionary part = resources == null ? null : ContentGraph.dict(resources.getDictionaryObject(kind));
        return part != null && part.getDictionaryObject(name) != null;
    }
}
