package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class Rewrite {

    static final int OBJECTS_PER_STREAM = 256;

    static final int MAX_INLINE_DEPTH = 8;

    static final int MAX_THREADS = 4;

    static final int INLINE_JOB_BYTES = 8 << 20;

    private final Graph graph;

    private final PdfOptimizer.Options options;

    private final Spill spill;

    private int[] spilled;

    private List<Map<COSName, COSBase>> replaces;

    private byte[][] digests;

    private int[][] edges;

    private int[] rep;

    private boolean[] live;

    private boolean[] inline;

    private int[] numbers;

    private int recoded;

    private Encryption encryption;

    private Rewrite(Graph graph, PdfOptimizer.Options options, Spill spill) {
        this.graph = graph;
        this.options = options;
        this.spill = spill;
    }

    static PdfOptimizer.Result run(PDDocument doc, OutputStream os, PdfOptimizer.Options options, long inputBytes)
            throws IOException {
        Encryption encryption = Encryption.of(doc, options.password());
        COSDocument cos = doc.getDocument();
        COSDictionary trailer = cos.getTrailer();
        if (!(Graph.resolve(trailer.getItem(COSName.ROOT)) instanceof COSDictionary root)) {
            throw new IOException("The PDF has no document catalog");
        }
        COSDictionary info = Graph.resolve(trailer.getItem(COSName.INFO)) instanceof COSDictionary i && i != root ? i
                : null;
        List<COSBase> roots = info == null ? List.of(root) : List.of(root, info);
        List<String> warnings = new ArrayList<>();
        Graph g = Graph.walk(roots);
        if (!options.keepPrivateData()) {
            Cleanup.dropPrivateData(g);
        }
        Cleanup.dropUnusedResources(Roles.of(g, root));
        g = Graph.walk(roots);
        Roles roles = Roles.of(g, root);
        if (encryption != null) {
            for (COSBase node : g.nodes()) {
                if (node instanceof COSStream s && Encryption.usesCryptFilter(s)) {
                    throw new IOException("The PDF names crypt filters on its streams, which this optimizer does not write");
                }
            }
        }
        if (roles.signed) {
            warnings.add("The PDF is signed; rewriting it invalidates its signatures");
        }
        boolean objectStreams = Roles.pdfaPart(root) != 1;
        float version = Math.max(cos.getVersion(), objectStreams ? 1.5f : 1.0f);
        COSBase id = Graph.resolve(trailer.getItem(COSName.ID));
        try (Spill spill = new Spill()) {
            Rewrite r = new Rewrite(g, options, spill);
            r.encryption = encryption;
            r.plan(roles);
            r.merge(g.id(root), info == null ? -1 : g.id(info));
            int rootNode = r.rep[g.id(root)];
            int infoNode = info == null ? -1 : r.rep[g.id(info)];
            r.liveness(rootNode, infoNode, roles.indirect);
            CountingOutput out = new CountingOutput(os);
            int objects = r.write(out, version, objectStreams, rootNode, infoNode, id instanceof COSArray a ? a : null);
            int merged = 0;
            for (int i = 0; i < g.size(); i++) {
                if (r.rep[i] != i && r.live[r.rep[i]]) {
                    merged++;
                }
            }
            return new PdfOptimizer.Result(inputBytes, out.position(), true, objects, merged, r.recoded, warnings);
        }
    }

    private record Pending(int node, Future<Recoder.Plan> plan) {}

    private void plan(Roles roles) throws IOException {
        int n = graph.size();
        spilled = new int[n];
        replaces = new ArrayList<>(n);
        digests = new byte[n][];
        MessageDigest sha = sha256();
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_THREADS));
        ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("pdf-optimize-recode-", 0).daemon().factory()) : null;
        ArrayDeque<Pending> window = new ArrayDeque<>();
        try {
            for (int i = 0; i < n; i++) {
                spilled[i] = -1;
                replaces.add(null);
            }
            for (int i = 0; i < n; i++) {
                if (!(graph.node(i) instanceof COSStream s)) {
                    continue;
                }
                PdfFiles.stopIfInterrupted();
                Recoder.Kind kind = roles.kinds.getOrDefault(s, Recoder.Kind.OTHER);
                Recoder.Job job = Recoder.prepare(s, kind, kind == Recoder.Kind.IMAGE ? Roles.layout(s) : null);
                if (job == null) {
                    try (InputStream in = s.createRawInputStream()) {
                        spilled[i] = spill.put(in, sha);
                    }
                    digests[i] = sha.digest();
                    replaces.set(i, Map.of(COSName.LENGTH, COSInteger.get(spill.length(spilled[i]))));
                    continue;
                }
                if (pool == null || job.raw().length > INLINE_JOB_BYTES) {
                    while (!window.isEmpty()) {
                        finish(window.poll(), sha);
                    }
                    record(i, Recoder.plan(job, options.effort()), sha);
                    continue;
                }
                window.add(new Pending(i, pool.submit(() -> Recoder.plan(job, options.effort()))));
                while (window.size() > threads * 2) {
                    finish(window.poll(), sha);
                }
            }
            while (!window.isEmpty()) {
                finish(window.poll(), sha);
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    private void finish(Pending p, MessageDigest sha) throws IOException {
        try {
            record(p.node(), p.plan().get(), sha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted while optimizing");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException(cause);
        }
    }

    private void record(int i, Recoder.Plan p, MessageDigest sha) throws IOException {
        spilled[i] = spill.put(p.data());
        digests[i] = sha.digest(p.data());
        replaces.set(i, p.replace());
        if (p.recoded()) {
            recoded++;
        }
    }

    private void merge(int root, int info) throws IOException {
        int n = graph.size();
        byte[][] signatures = new byte[n][];
        edges = new int[n][];
        boolean[] mergeable = new boolean[n];
        List<Integer> found = new ArrayList<>();
        Syntax syntax = new Syntax(graph, (id, s) -> {
            found.add(id);
            s.word("@");
        });
        Bytes b = new Bytes(1 << 12);
        for (int i = 0; i < n; i++) {
            if ((i & 1023) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            b.reset();
            syntax.into(b);
            COSBase node = graph.node(i);
            if (node instanceof COSStream s) {
                syntax.dictionary(s, replaces.get(i));
                b.write(digests[i]);
            } else {
                syntax.node(i);
            }
            signatures[i] = b.toByteArray();
            int[] e = new int[found.size()];
            for (int k = 0; k < e.length; k++) {
                e[k] = found.get(k);
            }
            found.clear();
            edges[i] = e;
            mergeable[i] = i != root && i != info && Roles.valueLike(node);
        }
        rep = Merge.representatives(signatures, edges, mergeable);
    }

    private void liveness(int root, int info, java.util.Set<COSBase> indirect) {
        int n = graph.size();
        live = new boolean[n];
        int[] refs = new int[n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        live[root] = true;
        queue.add(root);
        if (info >= 0 && !live[info]) {
            live[info] = true;
            queue.add(info);
        }
        while (!queue.isEmpty()) {
            int r = queue.poll();
            for (int e : edges[r]) {
                int t = rep[e];
                refs[t]++;
                if (!live[t]) {
                    live[t] = true;
                    queue.add(t);
                }
            }
        }
        inline = new boolean[n];
        for (int i = 0; i < n; i++) {
            COSBase node = graph.node(i);
            inline[i] = live[i] && refs[i] == 1 && i != root && i != info && !(node instanceof COSStream)
                    && Roles.valueLike(node) && !indirect.contains(node);
        }
        boolean[] onPath = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (live[i] && !inline[i]) {
                    changed |= demote(i, onPath);
                }
            }
        }
    }

    private boolean demote(int top, boolean[] onPath) {
        boolean changed = false;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {top, 0, 0});
        onPath[top] = true;
        while (!stack.isEmpty()) {
            int[] frame = stack.peek();
            int node = frame[0];
            if (frame[1] >= edges[node].length) {
                onPath[node] = false;
                stack.pop();
                continue;
            }
            int t = rep[edges[node][frame[1]++]];
            if (!inline[t]) {
                continue;
            }
            if (onPath[t] || frame[2] + 1 > MAX_INLINE_DEPTH) {
                inline[t] = false;
                changed = true;
                continue;
            }
            onPath[t] = true;
            stack.push(new int[] {t, 0, frame[2] + 1});
        }
        return changed;
    }

    private int write(CountingOutput out, float version, boolean objectStreams, int root, int info, COSArray id)
            throws IOException {
        int n = graph.size();
        List<Integer> packed = new ArrayList<>();
        List<Integer> plain = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!live[i] || inline[i]) {
                continue;
            }
            if (graph.node(i) instanceof COSStream || !objectStreams) {
                plain.add(i);
            } else {
                packed.add(i);
            }
        }
        packed.sort(Comparator.comparing((Integer i) -> group(graph.node(i))).thenComparing(i -> i));
        numbers = new int[n];
        int next = 1;
        for (int i : packed) {
            numbers[i] = next++;
        }
        int containers = (packed.size() + OBJECTS_PER_STREAM - 1) / OBJECTS_PER_STREAM;
        int firstContainer = next;
        next += containers;
        for (int i : plain) {
            numbers[i] = next++;
        }
        int encryptNumber = encryption != null ? next++ : 0;
        int xrefNumber = objectStreams ? next++ : 0;
        int size = next;
        long[] offsets = new long[size];
        int[] containerOf = new int[size];
        int[] indexIn = new int[size];
        out.ascii(versionHeader(version));
        out.write(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
        Syntax syntax = new Syntax(graph, (node, s) -> {
            int t = rep[node];
            if (inline[t]) {
                s.node(t);
            } else {
                s.reference(numbers[t]);
            }
        });
        Bytes b = new Bytes(1 << 16);
        int written = 0;
        for (int i : plain) {
            if ((++written & 255) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            int number = numbers[i];
            offsets[number] = out.position();
            b.reset();
            b.ascii(number + " 0 obj\n");
            syntax.into(b).strings(encryption == null ? null : text -> encryption.string(text, number));
            if (graph.node(i) instanceof COSStream s) {
                if (encryption == null) {
                    syntax.dictionary(s, replaces.get(i));
                    b.ascii("stream\n");
                    b.writeTo(out);
                    spill.copy(spilled[i], out);
                } else {
                    Bytes clear = new Bytes(spill.length(spilled[i]));
                    spill.copy(spilled[i], clear);
                    byte[] data = encryption.stream(clear.toByteArray(), number, s);
                    Map<COSName, COSBase> replace = new java.util.LinkedHashMap<>(replaces.get(i));
                    replace.put(COSName.LENGTH, COSInteger.get(data.length));
                    syntax.dictionary(s, replace);
                    b.ascii("stream\n");
                    b.writeTo(out);
                    out.write(data);
                }
                out.ascii("\nendstream\nendobj\n");
            } else {
                syntax.node(i);
                b.ascii("\nendobj\n");
                b.writeTo(out);
            }
            syntax.strings(null);
        }
        Bytes body = new Bytes(1 << 16);
        StringBuilder index = new StringBuilder();
        for (int c = 0; c < containers; c++) {
            PdfFiles.stopIfInterrupted();
            body.reset();
            index.setLength(0);
            int from = c * OBJECTS_PER_STREAM;
            int to = Math.min(packed.size(), from + OBJECTS_PER_STREAM);
            syntax.into(body);
            for (int k = from; k < to; k++) {
                int node = packed.get(k);
                if (k > from) {
                    index.append(' ');
                }
                index.append(numbers[node]).append(' ').append(body.size());
                containerOf[numbers[node]] = firstContainer + c;
                indexIn[numbers[node]] = k - from;
                syntax.into(body);
                syntax.node(node);
            }
            index.append('\n');
            Bytes raw = new Bytes(index.length() + body.size());
            raw.ascii(index.toString());
            body.writeTo(raw);
            int number = firstContainer + c;
            byte[] data = Deflate.best(raw.toByteArray(), options.effort());
            if (encryption != null) {
                data = encryption.encrypt(data, number);
            }
            offsets[number] = out.position();
            out.ascii(number + " 0 obj\n<</Type/ObjStm/N " + (to - from) + "/First " + index.length()
                    + "/Filter/FlateDecode/Length " + data.length + ">>stream\n");
            out.write(data);
            out.ascii("\nendstream\nendobj\n");
        }
        Bytes trailerId = new Bytes(96);
        if (id != null) {
            new Syntax(graph, (node, s) -> s.word("null")).into(trailerId).array(id);
        }
        if (encryption != null) {
            offsets[encryptNumber] = out.position();
            Bytes e = new Bytes(512);
            e.ascii(encryptNumber + " 0 obj\n");
            new Syntax(graph, (node, s) -> s.word("null")).copyUnknown().into(e).dictionary(encryption.dictionary, null);
            e.ascii("\nendobj\n");
            e.writeTo(out);
        }
        String head = "/Size " + size + "/Root " + numbers[root] + " 0 R" + (info >= 0 ? "/Info " + numbers[info]
                + " 0 R" : "") + (encryption != null ? "/Encrypt " + encryptNumber + " 0 R" : "");
        if (objectStreams) {
            xrefStream(out, size, xrefNumber, offsets, containerOf, indexIn, head, trailerId);
        } else {
            xrefTable(out, size, offsets, head, trailerId);
        }
        out.flush();
        return size - 1;
    }

    private static String group(COSBase node) {
        if (node instanceof COSArray) {
            return "~array";
        }
        COSDictionary d = (COSDictionary) node;
        COSName type = d.getCOSName(COSName.TYPE);
        COSName subtype = d.getCOSName(COSName.SUBTYPE);
        return (type == null ? "~" : type.getName()) + "/" + (subtype == null ? "" : subtype.getName());
    }

    private void xrefStream(CountingOutput out, int size, int self, long[] offsets, int[] containerOf,
            int[] indexIn, String head, Bytes id) throws IOException {
        long start = out.position();
        offsets[self] = start;
        long maxField = 0;
        int maxIndex = 0;
        for (int k = 1; k < size; k++) {
            maxField = Math.max(maxField, containerOf[k] != 0 ? containerOf[k] : offsets[k]);
            maxIndex = Math.max(maxIndex, indexIn[k]);
        }
        int w2 = bytesFor(maxField);
        int w3 = Math.max(1, bytesFor(maxIndex));
        int row = 1 + w2 + w3;
        byte[] rows = new byte[size * row];
        for (int k = 0; k < w3; k++) {
            rows[1 + w2 + k] = (byte) 0xFF;
        }
        for (int k = 1; k < size; k++) {
            int p = k * row;
            boolean inStream = containerOf[k] != 0;
            rows[p] = (byte) (inStream ? 2 : 1);
            put(rows, p + 1, w2, inStream ? containerOf[k] : offsets[k]);
            put(rows, p + 1 + w2, w3, inStream ? indexIn[k] : 0);
        }
        byte[] plainData = Deflate.best(rows, options.effort());
        byte[] predicted = Deflate.best(Predictors.encodePng(rows, new Predictors.Layout(1, 8, row), Predictors.UP),
                options.effort());
        boolean usePredictor = predicted.length + 30 < plainData.length;
        byte[] data = usePredictor ? predicted : plainData;
        out.ascii(self + " 0 obj\n<</Type/XRef" + head + "/W[1 " + w2 + " " + w3 + "]");
        if (id.size() > 0) {
            out.ascii("/ID");
            id.writeTo(out);
        }
        out.ascii("/Filter/FlateDecode" + (usePredictor ? "/DecodeParms<</Predictor 12/Columns " + row + ">>" : "")
                + "/Length " + data.length + ">>stream\n");
        out.write(data);
        out.ascii("\nendstream\nendobj\nstartxref\n" + start + "\n%%EOF\n");
    }

    private void xrefTable(CountingOutput out, int size, long[] offsets, String head, Bytes id) throws IOException {
        long start = out.position();
        StringBuilder b = new StringBuilder(size * 20 + 64);
        b.append("xref\n0 ").append(size).append('\n').append("0000000000 65535 f\r\n");
        for (int k = 1; k < size; k++) {
            String o = Long.toString(offsets[k]);
            b.append("0".repeat(Math.max(0, 10 - o.length()))).append(o).append(" 00000 n\r\n");
        }
        out.ascii(b.toString());
        out.ascii("trailer\n<<" + head);
        if (id.size() > 0) {
            out.ascii("/ID");
            id.writeTo(out);
        }
        out.ascii(">>\nstartxref\n" + start + "\n%%EOF\n");
    }

    private static int bytesFor(long v) {
        int w = 1;
        while (w < 8 && v >= 1L << (8 * w)) {
            w++;
        }
        return w;
    }

    private static void put(byte[] rows, int at, int width, long value) {
        for (int k = 0; k < width; k++) {
            rows[at + k] = (byte) (value >>> (8 * (width - 1 - k)));
        }
    }

    private static String versionHeader(float version) {
        float v = Math.min(version, 2.0f);
        return "%PDF-" + String.format(Locale.ROOT, "%.1f", v < 1.0f ? 1.4f : v) + "\n";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class CountingOutput extends OutputStream {

        private final OutputStream out;

        private long position;

        CountingOutput(OutputStream out) {
            this.out = out;
        }

        long position() {
            return position;
        }

        void ascii(String s) throws IOException {
            write(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            position++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            position += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
