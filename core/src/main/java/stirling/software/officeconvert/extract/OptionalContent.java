package stirling.software.officeconvert.extract;

import java.util.IdentityHashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;

final class OptionalContent {

    private static final COSName OCMD = COSName.getPDFName("OCMD");
    private static final COSName ALL_ON = COSName.getPDFName("AllOn");
    private static final COSName ANY_OFF = COSName.getPDFName("AnyOff");
    private static final COSName ALL_OFF = COSName.getPDFName("AllOff");
    private static final COSName VE = COSName.getPDFName("VE");
    private static final COSName AND = COSName.getPDFName("And");
    private static final COSName OR = COSName.getPDFName("Or");
    private static final COSName NOT = COSName.getPDFName("Not");
    private static final COSName AS = COSName.getPDFName("AS");
    private static final COSName EVENT = COSName.getPDFName("Event");
    private static final COSName CATEGORY = COSName.getPDFName("Category");
    private static final COSName USAGE = COSName.getPDFName("Usage");
    private static final COSName VIEW = COSName.getPDFName("View");
    private static final COSName VIEW_STATE = COSName.getPDFName("ViewState");
    private static final COSName OFF = COSName.getPDFName("OFF");
    private static final int MAX_GROUPS = 256;
    private static final int MAX_DEPTH = 16;

    private final PDOptionalContentProperties properties;
    private final Map<COSDictionary, Boolean> viewStates;

    private OptionalContent(PDOptionalContentProperties properties) {
        this.properties = properties;
        this.viewStates = properties == null ? Map.of() : viewStates(properties);
    }

    static OptionalContent of(PDDocument document) {
        try {
            return new OptionalContent(document == null ? null : document.getDocumentCatalog().getOCProperties());
        } catch (RuntimeException e) {
            return new OptionalContent(null);
        }
    }

    boolean hidden(COSBase membership) {
        if (properties == null || !(resolve(membership) instanceof COSDictionary dict)) {
            return false;
        }
        try {
            if (OCMD.equals(dict.getCOSName(COSName.TYPE))) {
                return !visible(dict);
            }
            return off(dict);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean visible(COSDictionary ocmd) {
        if (resolve(ocmd.getDictionaryObject(VE)) instanceof COSArray expression) {
            return expression(expression, 0);
        }
        COSBase groups = resolve(ocmd.getDictionaryObject(COSName.OCGS));
        int on = 0;
        int off = 0;
        if (groups instanceof COSDictionary group) {
            if (off(group)) {
                off++;
            } else {
                on++;
            }
        } else if (groups instanceof COSArray list) {
            for (int i = 0; i < Math.min(list.size(), MAX_GROUPS); i++) {
                if (resolve(list.get(i)) instanceof COSDictionary group) {
                    if (off(group)) {
                        off++;
                    } else {
                        on++;
                    }
                }
            }
        }
        if (on + off == 0) {
            return true;
        }
        COSName policy = ocmd.getCOSName(COSName.P);
        if (ALL_ON.equals(policy)) {
            return off == 0;
        }
        if (ANY_OFF.equals(policy)) {
            return off > 0;
        }
        if (ALL_OFF.equals(policy)) {
            return on == 0;
        }
        return on > 0;
    }

    private boolean expression(COSArray expression, int depth) {
        if (expression.size() < 2 || depth > MAX_DEPTH || !(resolve(expression.get(0)) instanceof COSName op)) {
            return true;
        }
        if (NOT.equals(op)) {
            return !operand(expression.get(1), depth);
        }
        boolean and = AND.equals(op);
        if (!and && !OR.equals(op)) {
            return true;
        }
        for (int i = 1; i < Math.min(expression.size(), MAX_GROUPS); i++) {
            boolean on = operand(expression.get(i), depth);
            if (and != on) {
                return on;
            }
        }
        return and;
    }

    private boolean operand(COSBase item, int depth) {
        COSBase value = resolve(item);
        if (value instanceof COSArray nested) {
            return expression(nested, depth + 1);
        }
        return !(value instanceof COSDictionary group) || !off(group);
    }

    private boolean off(COSDictionary group) {
        Boolean on = viewStates.get(group);
        if (on != null) {
            return !on;
        }
        return !properties.isGroupEnabled(new PDOptionalContentGroup(group));
    }

    private static Map<COSDictionary, Boolean> viewStates(PDOptionalContentProperties properties) {
        Map<COSDictionary, Boolean> states = new IdentityHashMap<>();
        try {
            if (!(resolve(properties.getCOSObject().getDictionaryObject(COSName.D)) instanceof COSDictionary config)
                    || !(resolve(config.getDictionaryObject(AS)) instanceof COSArray auto)) {
                return states;
            }
            for (int i = 0; i < Math.min(auto.size(), MAX_GROUPS); i++) {
                if (resolve(auto.get(i)) instanceof COSDictionary entry && VIEW.equals(entry.getCOSName(EVENT))
                        && names(entry.getDictionaryObject(CATEGORY), VIEW)
                        && resolve(entry.getDictionaryObject(COSName.OCGS)) instanceof COSArray groups) {
                    for (int g = 0; g < Math.min(groups.size(), MAX_GROUPS); g++) {
                        if (resolve(groups.get(g)) instanceof COSDictionary group
                                && resolve(group.getDictionaryObject(USAGE)) instanceof COSDictionary usage
                                && resolve(usage.getDictionaryObject(VIEW)) instanceof COSDictionary view
                                && view.getCOSName(VIEW_STATE) != null) {
                            states.put(group, !OFF.equals(view.getCOSName(VIEW_STATE)));
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            states.clear();
        }
        return states;
    }

    private static boolean names(COSBase value, COSName wanted) {
        COSBase v = resolve(value);
        if (v instanceof COSName name) {
            return wanted.equals(name);
        }
        if (v instanceof COSArray list) {
            for (int i = 0; i < Math.min(list.size(), MAX_GROUPS); i++) {
                if (wanted.equals(resolve(list.get(i)))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static COSBase resolve(COSBase base) {
        return base instanceof COSObject o ? o.getObject() : base;
    }
}
