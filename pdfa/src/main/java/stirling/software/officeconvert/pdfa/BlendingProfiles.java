package stirling.software.officeconvert.pdfa;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class BlendingProfiles {

    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");

    private static final COSName DEST_OUTPUT_PROFILE = COSName.getPDFName("DestOutputProfile");

    private static final int MAX_DEPTH = 4;

    private final Map<COSStream, String> digests = new IdentityHashMap<>();

    private BlendingProfiles() {}

    static void run(PDDocument doc, ContentGraph graph, Report report) {
        new BlendingProfiles().apply(doc, graph, report);
    }

    private void apply(PDDocument doc, ContentGraph graph, Report report) {
        if (dropRepeatedGroupSpaces(graph)) {
            report.warn("Removed transparency group colour spaces that repeat a CMYK profile used in the document,"
                    + " which PDF/A-4 does not allow");
        }
        if (rewriteOutputIntentRepeats(doc, graph)) {
            report.warn("Replaced CMYK colour spaces that repeat the output intent profile with DeviceCMYK, which"
                    + " PDF/A-4 requires");
        }
    }

    private boolean dropRepeatedGroupSpaces(ContentGraph graph) {
        Set<String> used = new HashSet<>();
        Set<COSBase> softMasks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (COSDictionary res : graph.resources()) {
            for (COSDictionary holder : holders(res)) {
                for (COSName k : spaceKeys(holder)) {
                    collect(holder.getDictionaryObject(k), used, 0);
                }
            }
            softMasks(res, softMasks);
        }
        boolean changed = false;
        for (ContentGraph.Node n : graph.nodes()) {
            boolean blends = n.kind() == ContentGraph.Kind.PAGE
                    || n.kind() == ContentGraph.Kind.FORM && !softMasks.contains(n.owner());
            COSDictionary group = ContentGraph.dict(n.owner().getDictionaryObject(COSName.GROUP));
            if (!blends || group == null
                    || n.kind() != ContentGraph.Kind.PAGE && !group.getBoolean(COSName.I, false)) {
                continue;
            }
            String profile = cmykProfile(group.getDictionaryObject(COSName.CS));
            if (profile != null && used.contains(profile)) {
                group.removeItem(COSName.CS);
                changed = true;
            }
        }
        return changed;
    }

    private boolean rewriteOutputIntentRepeats(PDDocument doc, ContentGraph graph) {
        String documentIntent = intentProfile(
                doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        Map<Integer, COSDictionary> pages = new HashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.kind() == ContentGraph.Kind.PAGE) {
                pages.put(n.page(), n.owner());
            }
        }
        Map<COSDictionary, Set<String>> allowed = new IdentityHashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            COSDictionary page = pages.get(n.page());
            COSBase pageIntents = page == null ? null : page.getDictionaryObject(COSName.OUTPUT_INTENTS);
            String intent = pageIntents != null ? intentProfile(pageIntents) : documentIntent;
            Set<String> context = intent == null ? Set.of() : Set.of(intent);
            for (COSDictionary holder : holders(n.resources())) {
                Set<String> current = allowed.get(holder);
                if (current == null) {
                    allowed.put(holder, new HashSet<>(context));
                } else {
                    current.retainAll(context);
                }
            }
        }
        boolean changed = false;
        for (Map.Entry<COSDictionary, Set<String>> e : allowed.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            COSDictionary holder = e.getKey();
            for (COSName k : spaceKeys(holder)) {
                COSBase replacement = replace(holder.getDictionaryObject(k), e.getValue(), 0);
                if (replacement == null) {
                    continue;
                }
                if (DEFAULT_CMYK.equals(k)) {
                    holder.removeItem(k);
                } else {
                    holder.setItem(k, replacement);
                }
                changed = true;
            }
        }
        return changed;
    }

    private static List<COSDictionary> holders(COSDictionary res) {
        List<COSDictionary> out = new ArrayList<>();
        if (res == null) {
            return out;
        }
        COSDictionary spaces = ContentGraph.dict(res.getDictionaryObject(COSName.COLORSPACE));
        if (spaces != null) {
            out.add(spaces);
        }
        COSDictionary xobjects = ContentGraph.dict(res.getDictionaryObject(COSName.XOBJECT));
        if (xobjects != null) {
            for (COSName k : xobjects.keySet()) {
                if (xobjects.getDictionaryObject(k) instanceof COSStream s
                        && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
                    out.add(s);
                }
            }
        }
        COSDictionary shadings = ContentGraph.dict(res.getDictionaryObject(COSName.SHADING));
        if (shadings != null) {
            for (COSName k : shadings.keySet()) {
                COSDictionary s = ContentGraph.dict(shadings.getDictionaryObject(k));
                if (s != null) {
                    out.add(s);
                }
            }
        }
        COSDictionary patterns = ContentGraph.dict(res.getDictionaryObject(COSName.PATTERN));
        if (patterns != null) {
            for (COSName k : patterns.keySet()) {
                COSDictionary p = ContentGraph.dict(patterns.getDictionaryObject(k));
                COSDictionary s = p == null ? null : ContentGraph.dict(p.getDictionaryObject(COSName.SHADING));
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static List<COSName> spaceKeys(COSDictionary holder) {
        if (holder.containsKey(COSName.SHADING_TYPE) || holder instanceof COSStream) {
            return List.of(COSName.COLORSPACE);
        }
        return new ArrayList<>(holder.keySet());
    }

    private static void softMasks(COSDictionary res, Set<COSBase> out) {
        COSDictionary states = ContentGraph.dict(res.getDictionaryObject(COSName.EXT_G_STATE));
        if (states == null) {
            return;
        }
        for (COSName k : states.keySet()) {
            COSDictionary gs = ContentGraph.dict(states.getDictionaryObject(k));
            COSDictionary mask = gs == null ? null : ContentGraph.dict(gs.getDictionaryObject(COSName.SMASK));
            if (mask != null && mask.getDictionaryObject(COSName.G) instanceof COSStream g) {
                out.add(g);
            }
        }
    }

    private void collect(COSBase space, Set<String> out, int depth) {
        String profile = cmykProfile(space);
        if (profile != null) {
            out.add(profile);
            return;
        }
        int slot = nestedSlot(space);
        if (slot > 0 && depth < MAX_DEPTH) {
            collect(((COSArray) space).getObject(slot), out, depth + 1);
        }
    }

    private COSBase replace(COSBase space, Set<String> targets, int depth) {
        String profile = cmykProfile(space);
        if (profile != null) {
            return targets.contains(profile) ? COSName.DEVICECMYK : null;
        }
        int slot = nestedSlot(space);
        if (slot != 1 || depth >= MAX_DEPTH) {
            return null;
        }
        COSArray a = (COSArray) space;
        COSBase inner = replace(a.getObject(slot), targets, depth + 1);
        if (inner == null) {
            return null;
        }
        COSArray copy = new COSArray();
        for (int i = 0; i < a.size(); i++) {
            copy.add(i == slot ? inner : a.get(i));
        }
        return copy;
    }

    private static int nestedSlot(COSBase space) {
        if (!(space instanceof COSArray a) || a.size() < 2 || !(a.getObject(0) instanceof COSName family)) {
            return -1;
        }
        int slot = switch (family.getName()) {
            case "Indexed", "Pattern" -> 1;
            case "Separation", "DeviceN" -> 2;
            default -> -1;
        };
        return slot < a.size() ? slot : -1;
    }

    private String intentProfile(COSBase intents) {
        COSArray a = ContentGraph.array(intents);
        if (a == null || a.size() == 0) {
            return null;
        }
        COSDictionary oi = ContentGraph.dict(a.getObject(0));
        if (oi == null || !(oi.getDictionaryObject(DEST_OUTPUT_PROFILE) instanceof COSStream s)
                || s.getInt(COSName.N, -1) != 4) {
            return null;
        }
        return digest(s);
    }

    private String cmykProfile(COSBase space) {
        if (!(space instanceof COSArray a) || a.size() < 2 || !COSName.ICCBASED.equals(a.getObject(0))
                || !(a.getObject(1) instanceof COSStream s) || s.getInt(COSName.N, -1) != 4) {
            return null;
        }
        return digest(s);
    }

    private String digest(COSStream s) {
        if (digests.containsKey(s)) {
            return digests.get(s);
        }
        String d = md5(s);
        digests.put(s, d);
        return d;
    }

    private static String md5(COSStream s) {
        byte[] data = ColourFixer.read(s);
        if (data == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
