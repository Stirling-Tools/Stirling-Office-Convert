package stirling.software.officeconvert.pdfa;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

    private static final String NONE = "";

    private final Map<COSStream, String> digests = new IdentityHashMap<>();

    private BlendingProfiles() {}

    static void run(PDDocument doc, ContentGraph graph, Report report) {
        new BlendingProfiles().apply(doc, graph, report);
    }

    private void apply(PDDocument doc, ContentGraph graph, Report report) {
        String output = outputProfile(doc);
        Map<Integer, COSDictionary> pages = new HashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.kind() == ContentGraph.Kind.PAGE) {
                pages.put(n.page(), n.owner());
            }
        }
        boolean droppedGroup = false;
        for (ContentGraph.Node n : graph.nodes()) {
            COSDictionary page = inheritsPageGroup(n) ? pages.get(n.page()) : null;
            String profile = page == null ? null : groupProfile(page);
            if (profile != null && holders(n.resources()).stream().anyMatch(h -> uses(h, profile))) {
                ContentGraph.dict(page.getDictionaryObject(COSName.GROUP)).removeItem(COSName.CS);
                droppedGroup = true;
            }
        }
        if (droppedGroup) {
            report.warn("Removed a page blending colour space that repeats a CMYK profile used on the page, which"
                    + " PDF/A-4 does not allow");
        }
        Map<COSDictionary, Set<String>> uses = new IdentityHashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            String blending = groupProfile(n.owner());
            if (blending == null && inheritsPageGroup(n) && pages.get(n.page()) != null) {
                blending = groupProfile(pages.get(n.page()));
            }
            for (COSDictionary holder : holders(n.resources())) {
                uses.computeIfAbsent(holder, k -> new HashSet<>()).add(blending == null ? NONE : blending);
            }
        }
        boolean changed = false;
        for (Map.Entry<COSDictionary, Set<String>> e : uses.entrySet()) {
            Set<String> targets = new HashSet<>();
            if (output != null) {
                targets.add(output);
            }
            if (e.getValue().size() == 1 && !e.getValue().contains(NONE)) {
                targets.addAll(e.getValue());
            }
            if (!targets.isEmpty()) {
                changed |= rewrite(e.getKey(), targets);
            }
        }
        if (changed) {
            report.warn("Replaced CMYK colour spaces that repeat the blending profile with DeviceCMYK, which PDF/A-4"
                    + " requires");
        }
    }

    private boolean inheritsPageGroup(ContentGraph.Node n) {
        return n.kind() == ContentGraph.Kind.PAGE || groupProfile(n.owner()) == null
                && (n.kind() == ContentGraph.Kind.FORM || n.kind() == ContentGraph.Kind.PATTERN);
    }

    private boolean uses(COSDictionary holder, String profile) {
        if (holder instanceof COSStream image) {
            return profile.equals(cmykProfile(image.getDictionaryObject(COSName.COLORSPACE)));
        }
        return holder.keySet().stream().anyMatch(k -> profile.equals(cmykProfile(holder.getDictionaryObject(k))));
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
        return out;
    }

    private boolean rewrite(COSDictionary holder, Set<String> targets) {
        if (holder instanceof COSStream image) {
            if (targets.contains(cmykProfile(image.getDictionaryObject(COSName.COLORSPACE)))) {
                image.setItem(COSName.COLORSPACE, COSName.DEVICECMYK);
                return true;
            }
            return false;
        }
        boolean changed = false;
        for (COSName k : new ArrayList<>(holder.keySet())) {
            if (!targets.contains(cmykProfile(holder.getDictionaryObject(k)))) {
                continue;
            }
            if (DEFAULT_CMYK.equals(k)) {
                holder.removeItem(k);
            } else {
                holder.setItem(k, COSName.DEVICECMYK);
            }
            changed = true;
        }
        return changed;
    }

    private String groupProfile(COSDictionary owner) {
        COSDictionary group = ContentGraph.dict(owner.getDictionaryObject(COSName.GROUP));
        return group == null ? null : cmykProfile(group.getDictionaryObject(COSName.CS));
    }

    private String outputProfile(PDDocument doc) {
        COSArray intents = ContentGraph.array(
                doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OUTPUT_INTENTS));
        if (intents == null || intents.size() == 0) {
            return null;
        }
        COSDictionary oi = ContentGraph.dict(intents.getObject(0));
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
