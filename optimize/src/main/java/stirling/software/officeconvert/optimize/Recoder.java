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

    static final long MAX_DECODED_BYTES = Math.max(16L << 20,
            Math.min(256L << 20, Runtime.getRuntime().maxMemory() / 16));

    static final long MAX_RAW_BYTES = MAX_DECODED_BYTES;

    private static final COSName DL = COSName.getPDFName("DL");

    private static final COSName DCT_ABBREVIATION = COSName.getPDFName("DCT");

    enum Kind {
        CONTENT, IMAGE, KEEP, OTHER
    }

    record Plan(byte[] data, Map<COSName, COSBase> replace, boolean recoded) {}

    private record Candidate(byte[] data, List<COSName> filters, List<COSBase> parms, long cost) {}

    private Recoder() {}

    static COSBase resolve(COSBase b) {
        return b instanceof org.apache.pdfbox.cos.COSObject o ? o.getObject() : b;
    }

    record Job(byte[] raw, Kind kind, List<COSName> filters, List<COSBase> parms, boolean chained,
            Predictors.Layout image, int rows) {}

    static Job prepare(COSStream s, Kind kind, Predictors.Layout image) throws IOException {
        if (s.getLength() > MAX_RAW_BYTES) {
            return null;
        }
        byte[] raw;
        try (InputStream in = s.createRawInputStream()) {
            raw = in.readNBytes((int) MAX_RAW_BYTES + 1);
        }
        if (raw.length > MAX_RAW_BYTES) {
            return null;
        }
        List<COSName> filters = new ArrayList<>();
        List<COSBase> parms = new ArrayList<>();
        boolean chained = !s.containsKey(COSName.F) && chain(s, filters, parms);
        for (COSBase p : parms) {
            if (resolve(p) instanceof COSDictionary d) {
                for (COSBase v : d.getValues()) {
                    resolve(v);
                }
            }
        }
        return new Job(raw, kind, filters, parms, chained, image, imageRows(s));
    }

    static Plan plan(Job job, PdfOptimizer.Effort effort) throws IOException {
        return plan(job, effort, MAX_DECODED_BYTES);
    }

    static Plan plan(Job job, PdfOptimizer.Effort effort, long limit) throws IOException {
        byte[] raw = job.raw;
        Kind kind = job.kind;
        Predictors.Layout image = job.image;
        List<COSName> filters = job.filters;
        List<COSBase> parms = job.parms;
        if (kind == Kind.KEEP && job.filters.isEmpty() && job.chained) {
            byte[] trimmed = Xmp.withoutPadding(raw);
            if (trimmed != raw) {
                Map<COSName, COSBase> replace = new LinkedHashMap<>();
                replace.put(DL, null);
                replace.put(COSName.LENGTH, COSInteger.get(trimmed.length));
                return new Plan(trimmed, replace, true);
            }
        }
        if (!job.chained || kind == Kind.KEEP) {
            return original(raw);
        }
        byte[] data = raw;
        int peeled = 0;
        try {
            while (peeled < filters.size() && Filters.generalized(filters.get(peeled))) {
                COSBase p = resolve(parms.get(peeled));
                data = Filters.decode(filters.get(peeled), p instanceof COSDictionary d ? d : null, data,
                        Math.min(limit, MAX_DECODED_BYTES));
                peeled++;
            }
        } catch (Filters.TooLarge e) {
            return new Plan(null, null, false);
        } catch (Filters.Undecodable e) {
            return original(raw);
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
        if (effort != PdfOptimizer.Effort.FAST && rest.size() == 1 && (COSName.DCT_DECODE.equals(rest.get(0))
                || DCT_ABBREVIATION.equals(rest.get(0)))) {
            byte[] jpeg = Jpeg.optimize(data);
            if (jpeg != null) {
                best = better(best, candidate(jpeg, rest, restParms));
            }
        }
        byte[] flateInput = data;
        List<COSName> flateFilters = with(COSName.FLATE_DECODE, rest);
        List<COSBase> flateParms = with(null, restParms);
        byte[] quick = Deflate.baseline(data, effort);
        long quickCost = quick.length + overhead(flateFilters, flateParms);
        if (rest.isEmpty() && image != null && (long) image.rowBytes() * job.rows == data.length
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
                byte[] z = Deflate.baseline(predicted, effort);
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
            return original(raw);
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
                COSBase p = resolve(parms.get(i));
                data = Filters.decode(filters.get(i), p instanceof COSDictionary d ? d : null, data, MAX_DECODED_BYTES);
            }
        } catch (Filters.Undecodable e) {
            return null;
        }
        return data;
    }

    private static Plan original(byte[] raw) {
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
        COSBase f = resolve(s.getItem(COSName.FILTER));
        COSBase p = resolve(s.getItem(COSName.DECODE_PARMS));
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
            if (!(resolve(a.get(i)) instanceof COSName n)) {
                return false;
            }
            filters.add(n);
            COSBase item = pa != null && i < pa.size() ? pa.get(i) : null;
            COSBase resolved = resolve(item);
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

    static COSDictionary predictorParms(Predictors.Layout layout) {
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
