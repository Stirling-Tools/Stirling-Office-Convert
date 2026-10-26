package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class Cleanup {

    private static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");

    private static final Set<COSName> DEFAULT_SPACES = Set.of(COSName.getPDFName("DefaultGray"),
            COSName.getPDFName("DefaultRGB"), COSName.getPDFName("DefaultCMYK"));

    private Cleanup() {}

    static int dropPrivateData(Graph g) {
        int dropped = 0;
        for (COSBase node : g.nodes()) {
            if (node instanceof COSDictionary d && d.containsKey(PIECE_INFO)) {
                d.removeItem(PIECE_INFO);
                dropped++;
            }
        }
        return dropped;
    }

    static int dropUnusedResources(Roles roles) throws IOException {
        Map<COSStream, Content.Usage> scanned = new IdentityHashMap<>();
        Map<COSDictionary, Set<COSName>> used = new IdentityHashMap<>();
        Map<COSDictionary, Content.Category> categories = new IdentityHashMap<>();
        Set<COSDictionary> unsafe = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSDictionary> known = Collections.newSetFromMap(new IdentityHashMap<>());
        int count = 0;
        for (Roles.Context c : roles.contexts) {
            if ((++count & 255) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            Content.Usage usage = scanned.get(c.stream());
            if (usage == null) {
                usage = scan(c.stream());
                scanned.put(c.stream(), usage);
            }
            if (c.resources() == null) {
                if (!usage.complete() || usage.any()) {
                    return 0;
                }
                continue;
            }
            known.add(c.resources());
            for (Content.Category category : Content.Category.values()) {
                if (!(c.resources().getDictionaryObject(category.key) instanceof COSDictionary sub)) {
                    continue;
                }
                Content.Category previous = categories.putIfAbsent(sub, category);
                if (previous != null && previous != category || !usage.complete()) {
                    unsafe.add(sub);
                }
                used.computeIfAbsent(sub, k -> new HashSet<>()).addAll(usage.names().getOrDefault(category, Set.of()));
            }
        }
        List<COSDictionary> outside = new ArrayList<>(roles.pinned);
        for (COSDictionary res : roles.resources) {
            if (!known.contains(res)) {
                outside.add(res);
            }
        }
        for (COSDictionary res : outside) {
            for (Content.Category category : Content.Category.values()) {
                if (res.getDictionaryObject(category.key) instanceof COSDictionary sub) {
                    unsafe.add(sub);
                }
            }
        }
        int removed = 0;
        for (Map.Entry<COSDictionary, Set<COSName>> e : used.entrySet()) {
            COSDictionary sub = e.getKey();
            if (unsafe.contains(sub)) {
                continue;
            }
            boolean colourSpaces = categories.get(sub) == Content.Category.COLOR_SPACE;
            for (COSName key : new ArrayList<>(sub.keySet())) {
                if (!e.getValue().contains(key) && !(colourSpaces && DEFAULT_SPACES.contains(key))) {
                    sub.removeItem(key);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static Content.Usage scan(COSStream s) {
        try {
            byte[] data = Recoder.decoded(s);
            return data == null ? Content.Usage.unknown() : Content.scan(data);
        } catch (IOException | RuntimeException e) {
            return Content.Usage.unknown();
        }
    }
}
