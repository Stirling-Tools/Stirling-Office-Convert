package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

final class Roles {

    record Context(COSStream stream, COSDictionary resources) {}

    private static final COSName PATTERN_TYPE = COSName.getPDFName("PatternType");

    private static final COSName CHAR_PROCS = COSName.getPDFName("CharProcs");

    private static final COSName TYPE3 = COSName.getPDFName("Type3");

    private static final COSName BYTE_RANGE = COSName.getPDFName("ByteRange");

    private static final COSName PG = COSName.getPDFName("Pg");

    private static final COSName FT = COSName.getPDFName("FT");

    private static final Set<COSName> IDENTITY_TYPES = Set.of(COSName.CATALOG, COSName.PAGES, COSName.PAGE,
            COSName.ANNOT, COSName.STRUCT_ELEM, COSName.getPDFName("StructTreeRoot"), COSName.getPDFName("OBJR"),
            COSName.getPDFName("MCR"), COSName.OUTLINES, COSName.SIG, COSName.OCG, COSName.getPDFName("OCMD"),
            COSName.getPDFName("Thread"), COSName.getPDFName("Bead"), COSName.getPDFName("Template"),
            COSName.getPDFName("ObjStm"), COSName.XREF, COSName.getPDFName("DocTimeStamp"));

    private static final Set<COSName> IDENTITY_KEYS = Set.of(COSName.PARENT, COSName.P, COSName.KIDS, PG, FT,
            COSName.T, COSName.RECT, BYTE_RANGE);

    private static final Pattern PDFA_PART = Pattern.compile("pdfaid:part(?:\\s*=\\s*[\"']\\s*|\\s*>\\s*)(\\d)");

    final Map<COSStream, Recoder.Kind> kinds = new IdentityHashMap<>();

    final List<Context> contexts = new ArrayList<>();

    final Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());

    final Set<COSDictionary> pinned = Collections.newSetFromMap(new IdentityHashMap<>());

    final Set<COSBase> indirect = Collections.newSetFromMap(new IdentityHashMap<>());

    boolean signed;

    private Roles() {}

    static Roles of(Graph g, COSDictionary catalog) {
        Roles r = new Roles();
        r.pages(catalog);
        for (COSBase node : g.nodes()) {
            if (node instanceof COSStream s) {
                r.stream(s);
            }
            if (node instanceof COSDictionary d) {
                r.dictionary(d);
            }
        }
        if (catalog.getDictionaryObject(COSName.ACRO_FORM) instanceof COSDictionary form
                && form.getDictionaryObject(COSName.DR) instanceof COSDictionary dr) {
            r.pinned.add(dr);
        }
        return r;
    }

    private void pages(COSDictionary catalog) {
        if (!(catalog.getDictionaryObject(COSName.PAGES) instanceof COSDictionary top)) {
            return;
        }
        Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<COSDictionary> nodes = new ArrayDeque<>();
        ArrayDeque<COSDictionary> inherited = new ArrayDeque<>();
        COSDictionary none = new COSDictionary();
        nodes.push(top);
        inherited.push(none);
        while (!nodes.isEmpty()) {
            COSDictionary node = nodes.pop();
            COSDictionary parentResources = inherited.pop();
            if (!seen.add(node)) {
                continue;
            }
            COSDictionary res = node.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary own ? own
                    : parentResources;
            if (node.getDictionaryObject(COSName.KIDS) instanceof COSArray kids
                    && !COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) {
                for (int i = kids.size() - 1; i >= 0; i--) {
                    if (kids.getObject(i) instanceof COSDictionary kid) {
                        nodes.push(kid);
                        inherited.push(res);
                    }
                }
                continue;
            }
            COSDictionary pageResources = res == none ? null : res;
            COSBase contents = node.getDictionaryObject(COSName.CONTENTS);
            if (contents instanceof COSStream s) {
                content(s, pageResources);
            } else if (contents instanceof COSArray a) {
                for (int i = 0; i < a.size(); i++) {
                    if (a.getObject(i) instanceof COSStream s) {
                        content(s, pageResources);
                    }
                }
            }
        }
    }

    private void content(COSStream s, COSDictionary res) {
        kinds.put(s, Recoder.Kind.CONTENT);
        contexts.add(new Context(s, res));
    }

    private void stream(COSStream s) {
        COSName subtype = s.getCOSName(COSName.SUBTYPE);
        if (COSName.METADATA.equals(s.getCOSName(COSName.TYPE))) {
            kinds.put(s, Recoder.Kind.KEEP);
        } else if (COSName.FORM.equals(subtype) || s.containsKey(PATTERN_TYPE) && s.getInt(PATTERN_TYPE) == 1) {
            content(s, s.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary d ? d : null);
        } else if (COSName.IMAGE.equals(subtype)) {
            kinds.putIfAbsent(s, Recoder.Kind.IMAGE);
        }
    }

    private void dictionary(COSDictionary d) {
        if (d.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
            for (int i = 0; i < kids.size(); i++) {
                COSBase kid = kids.getObject(i);
                if (kid != null) {
                    indirect.add(kid);
                }
            }
        }
        if (d.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary res) {
            resources.add(res);
        }
        if (COSName.SIG.equals(d.getCOSName(COSName.TYPE)) || d.containsKey(BYTE_RANGE)
                && d.containsKey(COSName.CONTENTS)) {
            signed = true;
        }
        if (TYPE3.equals(d.getCOSName(COSName.SUBTYPE)) && d.getDictionaryObject(CHAR_PROCS) instanceof COSDictionary procs) {
            COSDictionary res = d.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary r ? r : null;
            for (COSBase v : procs.getValues()) {
                if (Graph.resolve(v) instanceof COSStream s) {
                    content(s, res);
                }
            }
        }
        if (d.getDictionaryObject(COSName.AP) instanceof COSDictionary ap) {
            for (COSName key : List.of(COSName.N, COSName.R, COSName.D)) {
                COSBase v = ap.getDictionaryObject(key);
                if (v instanceof COSStream s) {
                    appearance(s);
                } else if (v instanceof COSDictionary states) {
                    for (COSBase state : states.getValues()) {
                        if (Graph.resolve(state) instanceof COSStream s) {
                            appearance(s);
                        }
                    }
                }
            }
        }
    }

    private void appearance(COSStream s) {
        if (kinds.get(s) != Recoder.Kind.CONTENT) {
            content(s, s.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary d ? d : null);
        }
    }

    static boolean valueLike(COSBase b) {
        if (b instanceof COSArray) {
            return true;
        }
        if (!(b instanceof COSDictionary d)) {
            return false;
        }
        if (b instanceof COSStream) {
            return true;
        }
        COSName type = d.getCOSName(COSName.TYPE);
        if (type != null && IDENTITY_TYPES.contains(type)) {
            return false;
        }
        for (COSName key : IDENTITY_KEYS) {
            if (d.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    static int pdfaPart(COSDictionary catalog) {
        if (!(catalog.getDictionaryObject(COSName.METADATA) instanceof COSStream s)) {
            return 0;
        }
        try (InputStream in = s.createInputStream()) {
            String xmp = new String(in.readNBytes(4 << 20), StandardCharsets.UTF_8);
            Matcher m = PDFA_PART.matcher(xmp);
            return m.find() ? m.group(1).charAt(0) - '0' : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    static Predictors.Layout layout(COSStream s) {
        if (!COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
            return null;
        }
        int width = s.getInt(COSName.WIDTH, s.getInt(COSName.W, -1));
        if (width < 1 || width > 1 << 24) {
            return null;
        }
        if (s.getBoolean(COSName.IMAGE_MASK, s.getBoolean(COSName.IM, false))) {
            return new Predictors.Layout(1, 1, width);
        }
        int bpc = s.getInt(COSName.BITS_PER_COMPONENT, s.getInt(COSName.BPC, -1));
        int colors = components(s.getDictionaryObject(COSName.COLORSPACE, COSName.CS), 0);
        if (colors < 1 || bpc != 1 && bpc != 2 && bpc != 4 && bpc != 8 && bpc != 16) {
            return null;
        }
        return new Predictors.Layout(colors, bpc, width);
    }

    private static int components(COSBase cs, int depth) {
        if (depth > 4) {
            return -1;
        }
        if (cs instanceof COSName n) {
            return switch (n.getName()) {
                case "DeviceGray", "CalGray", "G", "Indexed", "I", "Separation" -> 1;
                case "DeviceRGB", "CalRGB", "RGB", "Lab" -> 3;
                case "DeviceCMYK", "CMYK" -> 4;
                default -> -1;
            };
        }
        if (!(cs instanceof COSArray a) || a.size() == 0 || !(a.getObject(0) instanceof COSName family)) {
            return -1;
        }
        return switch (family.getName()) {
            case "ICCBased" -> a.size() > 1 && a.getObject(1) instanceof COSStream icc ? icc.getInt(COSName.N, -1) : -1;
            case "DeviceN" -> a.size() > 1 && a.getObject(1) instanceof COSArray names ? names.size() : -1;
            case "Indexed", "I", "Separation", "CalGray" -> 1;
            case "CalRGB", "Lab" -> 3;
            default -> a.size() == 1 ? components(family, depth + 1) : -1;
        };
    }
}
