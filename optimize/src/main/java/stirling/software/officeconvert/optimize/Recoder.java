package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

final class Recoder {

    static final long MAX_DECODED_BYTES = Math.max(16L << 20, Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 16));

    static final long MAX_RAW_BYTES = MAX_DECODED_BYTES;

    private static final COSName DL = COSName.getPDFName("DL");

    enum Kind {
        CONTENT, IMAGE, KEEP, OTHER
    }

    record Plan(byte[] data, Map<COSName, COSBase> replace, boolean recoded) {}

    private record Candidate(byte[] data, List<COSName> filters, List<COSBase> parms, long cost) {}

    private Recoder() {}

    static Plan plan(COSStream s, Kind kind, Predictors.Layout image, PdfOptimizer.Effort effort) throws IOException {
        if (s.getLength() > MAX_RAW_BYTES) {
            return new Plan(null, null, false);
        }
        byte[] raw;
        try (InputStream in = s.createRawInputStream()) {
            raw = in.readNBytes((int) MAX_RAW_BYTES + 1);
        }
        if (raw.length > MAX_RAW_BYTES) {
            return new Plan(null, null, false);
        }
        if (kind == Kind.KEEP || s.containsKey(COSName.F)) {
            return original(raw, s);
        }
        List<COSName> filters = new ArrayList<>();
        List<COSBase> parms = new ArrayList<>();
        if (!chain(s, filters, parms)) {
            return original(raw, s);
        }
        byte[] data = raw;
        int peeled = 0;
        try {
            while (peeled < filters.size() && Filters.generalized(filters.get(peeled))) {
                COSBase p = Graph.resolve(parms.get(peeled));
                data = Filters.decode(filters.get(peeled), p instanceof COSDictionary d ? d : null, data,
                        MAX_DECODED_BYTES);
                peeled++;
            }
        } catch (Filters.Undecodable e) {
            return original(raw, s);
        }
        List<COSName> rest = filters.subList(peeled, filters.size());
        List<COSBase> restParms = parms.subList(peeled, parms.size());
        if (kind == Kind.CONTENT && rest.isEmpty()) {
            byte[] minified = Content.minify(data);
            if (minified != null && minified.length <= data.length) {
                data = minified;
            }
        }
        Candidate best = new Candidate(raw, filters, parms, raw.length + overhead(filters, parms));
        best = better(best, candidate(data, rest, restParms));
        byte[] flateInput = data;
        List<COSName> flateFilters = with(COSName.FLATE_DECODE, rest);
        List<COSBase> flateParms = with(null, restParms);
        byte[] quick = Deflate.quick(data);
        long quickCost = quick.length + overhead(flateFilters, flateParms);
        if (rest.isEmpty() && image != null && (long) image.rowBytes() * imageRows(s) == data.length
                && data.length > 0 && image.rowBytes() > 0) {
            int[] trials = switch (effort) {
                case FAST -> new int[] {Predictors.ADAPTIVE};
                case DEFAULT -> new int[] {Predictors.ADAPTIVE, Predictors.UP};
                case MAX -> new int[] {Predictors.ADAPTIVE, Predictors.SUB, Predictors.UP, Predictors.AVERAGE,
                        Predictors.PAETH};
            };
            List<COSBase> predictor = List.of(predictorParms(image));
            List<COSName> flate = List.of(COSName.FLATE_DECODE);
            for (int f : trials) {
                byte[] predicted = Predictors.encodePng(data, image, f);
                byte[] z = Deflate.quick(predicted);
                long cost = z.length + overhead(flate, predictor);
                if (cost < quickCost) {
                    quickCost = cost;
                    quick = z;
                    flateInput = predicted;
                    flateFilters = flate;
                    flateParms = predictor;
                }
            }
        }
        best = better(best, candidate(Deflate.best(flateInput, quick, effort), flateFilters, flateParms));
        if (best.data == raw) {
            return original(raw, s);
        }
        Map<COSName, COSBase> replace = new LinkedHashMap<>();
        replace.put(COSName.FILTER, filterValue(best.filters));
        replace.put(COSName.DECODE_PARMS, parmsValue(best.parms));
        replace.put(DL, null);
        replace.put(COSName.LENGTH, COSInteger.get(best.data.length));
        return new Plan(best.data, replace, true);
    }

    static byte[] decoded(COSStream s) throws IOException {
        if (s.getLength() > MAX_RAW_BYTES || s.containsKey(COSName.F)) {
            return null;
        }
        List<COSName> filters = new ArrayList<>();
        List<COSBase> parms = new ArrayList<>();
        if (!chain(s, filters, parms)) {
            return null;
        }
        byte[] data;
        try (InputStream in = s.createRawInputStream()) {
            data = in.readNBytes((int) MAX_RAW_BYTES + 1);
        }
        if (data.length > MAX_RAW_BYTES) {
            return null;
        }
        try {
            for (int i = 0; i < filters.size(); i++) {
                if (!Filters.generalized(filters.get(i))) {
                    return null;
                }
                COSBase p = Graph.resolve(parms.get(i));
                data = Filters.decode(filters.get(i), p instanceof COSDictionary d ? d : null, data, MAX_DECODED_BYTES);
            }
        } catch (Filters.Undecodable e) {
            return null;
        }
        return data;
    }

    private static Plan original(byte[] raw, COSStream s) {
        Map<COSName, COSBase> replace = new LinkedHashMap<>();
        replace.put(COSName.LENGTH, COSInteger.get(raw.length));
        return new Plan(raw, replace, false);
    }

    private static int imageRows(COSStream s) {
        return s.getInt(COSName.HEIGHT, s.getInt(COSName.H, -1));
    }

    private static Candidate better(Candidate a, Candidate b) {
        return b.cost < a.cost ? b : a;
    }

    private static Candidate candidate(byte[] data, List<COSName> filters, List<COSBase> parms) {
        return new Candidate(data, filters, parms, data.length + overhead(filters, parms));
    }

    private static <T> List<T> with(T first, List<T> rest) {
        List<T> l = new ArrayList<>(rest.size() + 1);
        l.add(first);
        l.addAll(rest);
        return l;
    }

    private static long overhead(List<COSName> filters, List<COSBase> parms) {
        if (filters.isEmpty()) {
            return 0;
        }
        long n = 7 + (filters.size() > 1 ? 2 : 0);
        for (COSName f : filters) {
            n += f.getName().length() + 1;
        }
        for (COSBase p : parms) {
            if (p instanceof COSDictionary d && d.containsKey(COSName.PREDICTOR)) {
                n += 12 + 24 + d.size() * 10L;
            } else if (p != null) {
                n += 24;
            }
        }
        return n;
    }

    static boolean chain(COSStream s, List<COSName> filters, List<COSBase> parms) {
        COSBase f = Graph.resolve(s.getItem(COSName.FILTER));
        COSBase p = Graph.resolve(s.getItem(COSName.DECODE_PARMS));
        if (f == null) {
            return p == null;
        }
        if (f instanceof COSName n) {
            if (p != null && !(p instanceof COSDictionary)) {
                return false;
            }
            filters.add(n);
            parms.add(p == null ? null : s.getItem(COSName.DECODE_PARMS));
            return true;
        }
        if (!(f instanceof COSArray a) || p != null && !(p instanceof COSArray)) {
            return false;
        }
        COSArray pa = (COSArray) p;
        for (int i = 0; i < a.size(); i++) {
            if (!(Graph.resolve(a.get(i)) instanceof COSName n)) {
                return false;
            }
            filters.add(n);
            COSBase item = pa != null && i < pa.size() ? pa.get(i) : null;
            COSBase resolved = Graph.resolve(item);
            if (resolved != null && !(resolved instanceof COSDictionary)
                    && !(resolved instanceof org.apache.pdfbox.cos.COSNull)) {
                return false;
            }
            parms.add(resolved instanceof COSDictionary ? item : null);
        }
        return true;
    }

    private static COSBase filterValue(List<COSName> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        COSArray a = new COSArray();
        filters.forEach(a::add);
        return a;
    }

    private static COSBase parmsValue(List<COSBase> parms) {
        boolean any = false;
        for (COSBase p : parms) {
            any |= p != null && !Syntax.absent(p);
        }
        if (!any) {
            return null;
        }
        if (parms.size() == 1) {
            return parms.get(0);
        }
        COSArray a = new COSArray();
        for (COSBase p : parms) {
            a.add(p == null ? org.apache.pdfbox.cos.COSNull.NULL : p);
        }
        return a;
    }

    private static COSDictionary predictorParms(Predictors.Layout layout) {
        COSDictionary d = new COSDictionary();
        d.setInt(COSName.PREDICTOR, 15);
        if (layout.colors() != 1) {
            d.setInt(COSName.COLORS, layout.colors());
        }
        if (layout.bitsPerComponent() != 8) {
            d.setInt(COSName.BITS_PER_COMPONENT, layout.bitsPerComponent());
        }
        if (layout.columns() != 1) {
            d.setInt(COSName.COLUMNS, layout.columns());
        }
        d.setDirect(true);
        return d;
    }
}
