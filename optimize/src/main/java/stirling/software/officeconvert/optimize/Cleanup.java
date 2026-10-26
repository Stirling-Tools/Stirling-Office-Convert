package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class Cleanup {

    static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");

    private static final Set<COSName> DEFAULT_SPACES = Set.of(COSName.getPDFName("DefaultGray"),
            COSName.getPDFName("DefaultRGB"), COSName.getPDFName("DefaultCMYK"));

    record Removal(Object[] path, Set<COSName> keys) {}

    private record Sub(Roles.Loc loc, COSDictionary dict) {}

    private Cleanup() {}

    static Map<Integer, List<Removal>> unusedResources(Roles roles, Resolver resolver, Source source, Index index,
            Roles.Loc dr) throws IOException {
        Map<Integer, Content.Usage> scanned = new HashMap<>();
        Map<Roles.Loc, Set<COSName>> used = new HashMap<>();
        Map<Roles.Loc, Content.Category> categories = new HashMap<>();
        Map<Roles.Loc, COSDictionary> subs = new HashMap<>();
        Set<Roles.Loc> unsafe = new HashSet<>();
        Set<Roles.Loc> known = new HashSet<>();
        int count = 0;
        for (Roles.Context c : roles.contexts) {
            if ((++count & 255) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            Content.Usage usage = scanned.get(c.stream());
            if (usage == null) {
                usage = scan(source.load(index.key(c.stream())));
                scanned.put(c.stream(), usage);
            }
            Resolver.Found r = c.resources() == null ? null : resolver.find(c.resources());
            if (r == null || !(r.value() instanceof COSDictionary res)) {
                if (!usage.complete() || usage.any()) {
                    return Map.of();
                }
                continue;
            }
            known.add(r.loc());
            for (Content.Category category : Content.Category.values()) {
                Sub sub = sub(resolver, index, r.loc(), res, category.key);
                if (sub == null) {
                    continue;
                }
                subs.put(sub.loc, sub.dict);
                Content.Category previous = categories.putIfAbsent(sub.loc, category);
                if (previous != null && previous != category || !usage.complete()) {
                    unsafe.add(sub.loc);
                }
                used.computeIfAbsent(sub.loc, k -> new HashSet<>())
                        .addAll(usage.names().getOrDefault(category, Set.of()));
            }
        }
        List<Roles.Loc> outside = new ArrayList<>();
        if (dr != null) {
            outside.add(dr);
        }
        outside.addAll(roles.resourceDicts);
        for (Roles.Loc loc : outside) {
            Resolver.Found r = resolver.find(loc);
            if (r == null || !(r.value() instanceof COSDictionary res) || known.contains(r.loc()) && loc != dr) {
                continue;
            }
            for (Content.Category category : Content.Category.values()) {
                Sub sub = sub(resolver, index, r.loc(), res, category.key);
                if (sub != null) {
                    unsafe.add(sub.loc);
                }
            }
        }
        Map<Integer, List<Removal>> removals = new HashMap<>();
        for (Map.Entry<Roles.Loc, Set<COSName>> e : used.entrySet()) {
            Roles.Loc loc = e.getKey();
            if (unsafe.contains(loc)) {
                continue;
            }
            boolean colourSpaces = categories.get(loc) == Content.Category.COLOR_SPACE;
            Set<COSName> drop = new HashSet<>();
            for (COSName key : subs.get(loc).keySet()) {
                if (!e.getValue().contains(key) && !(colourSpaces && DEFAULT_SPACES.contains(key))) {
                    drop.add(key);
                }
            }
            if (!drop.isEmpty()) {
                removals.computeIfAbsent(loc.holder(), k -> new ArrayList<>()).add(new Removal(loc.path(), drop));
            }
        }
        return removals;
    }

    private static Sub sub(Resolver resolver, Index index, Roles.Loc at, COSDictionary res, COSName key)
            throws IOException {
        COSBase v = res.getItem(key);
        if (v instanceof COSObject o && o.getKey() != null) {
            int id = index.id(o.getKey());
            return resolver.load(id) instanceof COSDictionary d && !(d instanceof COSStream)
                    ? new Sub(new Roles.Loc(id, new Object[0]), d) : null;
        }
        return v instanceof COSDictionary d && !(d instanceof COSStream) ? new Sub(at.child(key), d) : null;
    }

    static void apply(COSBase holder, List<Removal> removals) {
        for (Removal r : removals) {
            COSBase v = holder;
            for (Object step : r.path()) {
                if (step instanceof COSName key && v instanceof COSDictionary d) {
                    v = d.getItem(key);
                } else if (step instanceof Integer i && v instanceof org.apache.pdfbox.cos.COSArray a && i < a.size()) {
                    v = a.get(i);
                } else {
                    v = null;
                }
            }
            if (v instanceof COSDictionary d) {
                for (COSName key : r.keys()) {
                    d.removeItem(key);
                }
            }
        }
    }

    private static Content.Usage scan(COSBase s) {
        if (!(s instanceof COSStream stream)) {
            return Content.Usage.unknown();
        }
        try {
            byte[] data = Recoder.decoded(stream);
            return data == null ? Content.Usage.unknown() : Content.scan(data);
        } catch (IOException | RuntimeException e) {
            return Content.Usage.unknown();
        }
    }
}
