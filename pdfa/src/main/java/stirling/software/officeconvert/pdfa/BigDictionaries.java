package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class BigDictionaries {

    static final int MAX = 4095;

    private static final COSName DESTS = COSName.getPDFName("Dests");

    private static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");

    private static final List<COSName> RESOURCE_KINDS = List.of(COSName.FONT, COSName.XOBJECT, COSName.EXT_G_STATE,
            COSName.COLORSPACE, COSName.PATTERN, COSName.SHADING, COSName.PROPERTIES);

    private static final Set<String> INFO_KEYS = Set.of("Title", "Author", "Subject", "Keywords", "Creator",
            "Producer", "CreationDate", "ModDate", "Trapped");

    private BigDictionaries() {}

    static void run(PDDocument doc, ContentGraph graph, PdfALevel level, Report report) throws IOException {
        if (level.part() > 1) {
            return;
        }
        resources(graph, report);
        dests(doc, report);
        info(doc, report);
        pieceInfo(doc, report);
    }

    private static void resources(ContentGraph graph, Report report) throws IOException {
        Map<COSDictionary, Set<COSName>> used = null;
        for (COSDictionary res : graph.resources()) {
            for (COSName kind : RESOURCE_KINDS) {
                COSDictionary d = ContentGraph.dict(res.getDictionaryObject(kind));
                if (d == null || d.size() <= MAX) {
                    continue;
                }
                if (used == null) {
                    used = used(graph);
                }
                Set<COSName> names = used.getOrDefault(res, Set.of());
                for (COSName k : new ArrayList<>(d.keySet())) {
                    if (!names.contains(k)) {
                        d.removeItem(k);
                    }
                }
                report.warn("Removed unused resources from a resource dictionary larger than PDF/A-1 allows");
            }
        }
    }

    private static Map<COSDictionary, Set<COSName>> used(ContentGraph graph) {
        Map<COSDictionary, Set<COSName>> out = new IdentityHashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.resources() == null) {
                continue;
            }
            Set<COSName> names = out.computeIfAbsent(n.resources(), k -> new HashSet<>());
            List<Object> tokens;
            try {
                tokens = ContentTokens.parse(n.streams());
            } catch (IOException e) {
                names.addAll(allNames(n.resources()));
                continue;
            }
            for (Object t : tokens) {
                if (t instanceof COSName name) {
                    names.add(name);
                } else if (t instanceof Operator op && op.getImageParameters() != null
                        && op.getImageParameters().getDictionaryObject(COSName.CS) instanceof COSName cs) {
                    names.add(cs);
                }
            }
        }
        for (Map.Entry<COSDictionary, COSDictionary> e : graph.type3Fonts().entrySet()) {
            out.computeIfAbsent(e.getValue(), k -> new HashSet<>());
        }
        return out;
    }

    private static Set<COSName> allNames(COSDictionary res) {
        Set<COSName> out = new HashSet<>();
        for (COSName kind : RESOURCE_KINDS) {
            COSDictionary d = ContentGraph.dict(res.getDictionaryObject(kind));
            if (d != null) {
                out.addAll(d.keySet());
            }
        }
        return out;
    }

    private static void dests(PDDocument doc, Report report) {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSDictionary dests = ContentGraph.dict(cat.getDictionaryObject(DESTS));
        if (dests == null || dests.size() <= MAX) {
            return;
        }
        COSDictionary names = ContentGraph.dict(cat.getDictionaryObject(COSName.NAMES));
        if (names == null) {
            names = new COSDictionary();
            cat.setItem(COSName.NAMES, names);
        }
        if (names.getDictionaryObject(DESTS) != null) {
            return;
        }
        TreeMap<byte[], COSBase> sorted = new TreeMap<>(Arrays::compareUnsigned);
        for (Map.Entry<COSName, COSBase> e : dests.entrySet()) {
            sorted.put(e.getKey().getName().getBytes(StandardCharsets.UTF_8), e.getValue());
        }
        COSArray pairs = new COSArray();
        for (Map.Entry<byte[], COSBase> e : sorted.entrySet()) {
            pairs.add(new COSString(e.getKey()));
            pairs.add(e.getValue());
        }
        COSDictionary tree = new COSDictionary();
        tree.setItem(COSName.NAMES, pairs);
        names.setItem(DESTS, tree);
        cat.removeItem(DESTS);
        report.warn("Moved named destinations into a name tree, as PDF/A-1 limits dictionaries to " + MAX + " entries");
    }

    private static void info(PDDocument doc, Report report) {
        COSDictionary info = ContentGraph.dict(doc.getDocument().getTrailer().getDictionaryObject(COSName.INFO));
        if (info == null || info.size() <= MAX) {
            return;
        }
        for (COSName k : new ArrayList<>(info.keySet())) {
            if (info.size() > MAX - INFO_KEYS.size() && !INFO_KEYS.contains(k.getName())) {
                info.removeItem(k);
            }
        }
        report.warn("Removed custom document properties past the " + MAX + " entries PDF/A-1 allows");
    }

    private static void pieceInfo(PDDocument doc, Report report) throws IOException {
        List<COSDictionary> owners = new ArrayList<>();
        CosWalk.walk(doc, b -> {
            if (b instanceof COSDictionary d && d.containsKey(PIECE_INFO)) {
                owners.add(d);
            }
        });
        for (COSDictionary d : owners) {
            if (oversized(d.getDictionaryObject(PIECE_INFO), 0)) {
                d.removeItem(PIECE_INFO);
                report.warn("Removed application data larger than PDF/A-1 allows");
            }
        }
    }

    private static boolean oversized(COSBase b, int depth) {
        COSBase v = b instanceof COSObject o ? o.getObject() : b;
        if (depth > 8) {
            return false;
        }
        if (v instanceof COSDictionary d) {
            if (d.size() > MAX) {
                return true;
            }
            for (COSBase x : d.getValues()) {
                if (oversized(x, depth + 1)) {
                    return true;
                }
            }
        } else if (v instanceof COSArray a) {
            if (a.size() > LongArrays.MAX) {
                return true;
            }
            for (int i = 0; i < a.size() && i < 4096; i++) {
                if (oversized(a.get(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
