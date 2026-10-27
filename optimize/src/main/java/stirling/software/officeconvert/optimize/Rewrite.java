package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class Rewrite {

    static final int OBJECTS_PER_STREAM = 256;

    static final int MAX_INLINE_DEPTH = 8;

    static final int MAX_THREADS = 4;

    static final int INLINE_JOB_BYTES = 8 << 20;

    private static final byte[] NULL = {'n', 'u', 'l', 'l'};

    private final Index index;

    private final Source source;

    private final PdfOptimizer.Options options;

    private final Spill spill;

    private final Recorder recorder;

    private Encryption encryption;

    private Map<Integer, List<Cleanup.Removal>> removals = Map.of();

    private int[] spilled;

    private byte[][] digests;

    private int[][] edges;

    private int[] rep;

    private boolean[] live;

    private boolean[] inline;

    private int[] numbers;

    private int recoded;

    private Rewrite(Index index, Source source, PdfOptimizer.Options options, Spill spill, boolean encrypted) {
        this.index = index;
        this.source = source;
        this.options = options;
        this.spill = spill;
        this.recorder = new Recorder(index, encrypted, options.keepPrivateData() ? Set.of()
                : Set.of(Cleanup.PIECE_INFO));
    }

    static PdfOptimizer.Result run(PDDocument doc, OutputStream os, PdfOptimizer.Options options, long inputBytes)
            throws IOException {
        Encryption encryption = Encryption.of(doc, options.password());
        COSDocument cos = doc.getDocument();
        COSDictionary trailer = cos.getTrailer();
        if (!(trailer.getItem(COSName.ROOT) instanceof COSObject rootRef) || rootRef.getKey() == null) {
            throw new IOException("The PDF has no document catalog");
        }
        Source source = new Source(cos);
        Index index = new Index();
        Roles roles = new Roles(index);
        List<String> warnings = new ArrayList<>();
        try (Spill spill = new Spill()) {
            Rewrite r = new Rewrite(index, source, options, spill, encryption != null);
            r.encryption = encryption;
            int root = index.id(rootRef.getKey());
            int info = trailer.getItem(COSName.INFO) instanceof COSObject infoRef && infoRef.getKey() != null
                    ? index.id(infoRef.getKey()) : -1;
            List<COSObjectKey> order = source.keys();
            r.index(order, roles);
            source.release();
            if (index.kind(root) != Index.DICT) {
                throw new IOException("The PDF has no document catalog");
            }
            if (info >= 0 && index.kind(info) != Index.DICT) {
                info = -1;
            }
            Resolver resolver = new Resolver(source, index);
            roles.resolve(resolver, root);
            if (encryption != null) {
                for (int s : roles.content) {
                    COSBase b = source.load(index.key(s));
                    if (b instanceof COSStream st && Encryption.usesCryptFilter(st)) {
                        throw new IOException(
                                "The PDF names crypt filters on its streams, which this optimizer does not write");
                    }
                }
            }
            Resolver.Found dr = resolver.find(new Roles.Loc(root, new Object[] {COSName.ACRO_FORM, COSName.DR}));
            r.removals = Cleanup.unusedResources(roles, resolver, source, index, dr == null ? null : dr.loc());
            r.prune();
            if (roles.signed) {
                warnings.add("The PDF is signed; rewriting it invalidates its signatures");
            }
            COSBase catalog = source.load(index.key(root));
            boolean objectStreams = !(catalog instanceof COSDictionary c) || Roles.pdfaPart(c) != 1;
            float version = Math.max(cos.getVersion(), objectStreams ? 1.5f : 1.0f);
            COSBase id = trailer.getItem(COSName.ID) instanceof COSObject o ? o.getObject() : trailer.getItem(COSName.ID);
            r.plan(order, roles, new int[] {root, info});
            r.merge(root, info);
            int rootNode = r.rep[root];
            int infoNode = info < 0 ? -1 : r.rep[info];
            r.liveness(rootNode, infoNode, roles.indirect);
            CountingOutput out = new CountingOutput(os);
            int objects = r.write(out, version, objectStreams, rootNode, infoNode, id instanceof COSArray a ? a : null);
            int merged = 0;
            for (int i = 0; i < index.size(); i++) {
                if (r.rep[i] != i && r.live[r.rep[i]] && container(index.kind(i))) {
                    merged++;
                }
            }
            return new PdfOptimizer.Result(inputBytes, out.position(), true, objects, merged, r.recoded, warnings);
        }
    }

    private static boolean container(byte kind) {
        return kind == Index.DICT || kind == Index.ARRAY || kind == Index.STREAM;
    }

    private void index(List<COSObjectKey> order, Roles roles) throws IOException {
        Source.Visitor visitor = (key, object) -> {
            int id = index.id(key);
            recorder.record(id, object, null);
            roles.collect(id, object);
        };
        source.load(order, visitor);
        int scanned = 0;
        while (scanned < index.size()) {
            List<COSObjectKey> missing = new ArrayList<>();
            int end = index.size();
            for (; scanned < end; scanned++) {
                if (index.kind(scanned) == Index.UNSEEN) {
                    missing.add(index.key(scanned));
                }
                if ((scanned & 1023) == 0) {
                    PdfFiles.stopIfInterrupted();
                }
            }
            order.addAll(source.recover(missing, visitor));
            PdfFiles.stopIfInterrupted();
        }
    }

    private void prune() throws IOException {
        for (Map.Entry<Integer, List<Cleanup.Removal>> e : removals.entrySet()) {
            int id = e.getKey();
            if (index.kind(id) == Index.STREAM) {
                continue;
            }
            COSBase fresh = source.load(index.key(id));
            Cleanup.apply(fresh, e.getValue());
            recorder.record(id, fresh, null);
        }
    }

    private record Pending(int node, COSStream stream, Future<Recoder.Plan> plan) {}

    private boolean[] reachable(int[] roots) {
        boolean[] seen = new boolean[index.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int r : roots) {
            if (r >= 0 && !seen[r]) {
                seen[r] = true;
                queue.add(r);
            }
        }
        while (!queue.isEmpty()) {
            int n = queue.poll();
            for (int e : index.edges(n)) {
                if (!seen[e]) {
                    seen[e] = true;
                    queue.add(e);
                }
            }
        }
        return seen;
    }

    private void plan(List<COSObjectKey> order, Roles roles, int[] roots) throws IOException {
        int n = index.size();
        spilled = new int[n];
        digests = new byte[n][];
        java.util.Arrays.fill(spilled, -1);
        MessageDigest sha = sha256();
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_THREADS));
        ExecutorService pool = new java.util.concurrent.ForkJoinPool(threads);
        ArrayDeque<Pending> window = new ArrayDeque<>();
        boolean[] reached = reachable(roots);
        List<COSObjectKey> streams = new ArrayList<>();
        for (COSObjectKey key : order) {
            int id = index.find(key);
            if (id >= 0 && reached[id] && index.kind(id) == Index.STREAM) {
                streams.add(key);
            }
        }
        try {
            for (COSObjectKey key : streams) {
                PdfFiles.stopIfInterrupted();
                int i = index.find(key);
                if (!(source.load(key) instanceof COSStream s)) {
                    index.kind(i, Index.MISSING);
                    continue;
                }
                List<Cleanup.Removal> drop = removals.get(i);
                if (drop != null) {
                    Cleanup.apply(s, drop);
                }
                Recoder.Kind kind = index.has(i, Index.METADATA) ? Recoder.Kind.KEEP
                        : roles.content.contains(i) ? Recoder.Kind.CONTENT
                        : index.has(i, Index.IMAGE) ? Recoder.Kind.IMAGE : Recoder.Kind.OTHER;
                Recoder.Job job = Recoder.prepare(s, kind, kind == Recoder.Kind.IMAGE ? Roles.layout(s) : null);
                if (job == null) {
                    large(i, s, kind, sha);
                    continue;
                }
                if (job.raw().length > INLINE_JOB_BYTES) {
                    while (!window.isEmpty()) {
                        finish(window.poll(), sha);
                    }
                    finish(new Pending(i, s, pool.submit(() -> Recoder.plan(job, options.effort()))), sha);
                    continue;
                }
                window.add(new Pending(i, s, pool.submit(() -> Recoder.plan(job, options.effort()))));
                while (window.size() > threads * 2) {
                    finish(window.poll(), sha);
                }
            }
            while (!window.isEmpty()) {
                finish(window.poll(), sha);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void finish(Pending p, MessageDigest sha) throws IOException {
        try {
            store(p.node(), p.stream(), p.plan().get(), sha);
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

    private void large(int i, COSStream s, Recoder.Kind kind, MessageDigest sha) throws IOException {
        Streamed.Result big = Streamed.recode(s, kind, kind == Recoder.Kind.IMAGE ? Roles.layout(s) : null,
                s.getInt(COSName.HEIGHT, -1), spill, sha, options.effort());
        if (big != null) {
            spilled[i] = big.spilled();
            digests[i] = big.digest();
            recorder.record(i, s, big.replace());
            recoded++;
            return;
        }
        try (InputStream in = s.createRawInputStream()) {
            spilled[i] = spill.put(in, sha);
        }
        digests[i] = sha.digest();
        recorder.record(i, s, Map.of(COSName.LENGTH, COSInteger.get(spill.length(spilled[i]))));
    }

    private void store(int i, COSStream s, Recoder.Plan p, MessageDigest sha) throws IOException {
        if (p.data() == null) {
            large(i, s, index.has(i, Index.IMAGE) ? Recoder.Kind.IMAGE : Recoder.Kind.OTHER, sha);
            return;
        }
        spilled[i] = spill.put(p.data());
        digests[i] = sha.digest(p.data());
        recorder.record(i, s, p.replace());
        if (p.recoded()) {
            recoded++;
        }
    }

    private void merge(int root, int info) throws IOException {
        int n = index.size();
        byte[][] signatures = new byte[n][];
        edges = new int[n][];
        boolean[] mergeable = new boolean[n];
        MessageDigest sha = sha256();
        for (int i = 0; i < n; i++) {
            if ((i & 4095) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            byte kind = index.kind(i);
            edges[i] = container(kind) ? index.edges(i) : new int[0];
            byte[] body = index.bytes(i);
            sha.update(kind);
            if (body != null) {
                sha.update(body);
            }
            for (int k = 0; k < index.holeCount(i); k++) {
                int at = index.holeAt(i, k);
                sha.update((byte) (at >>> 24));
                sha.update((byte) (at >>> 16));
                sha.update((byte) (at >>> 8));
                sha.update((byte) at);
                sha.update((byte) (index.holeRef(i, k) < 0 ? 1 : 0));
            }
            byte[][] strings = index.strings(i);
            if (strings != null) {
                for (byte[] s : strings) {
                    sha.update(s);
                    sha.update((byte) 0);
                }
            }
            if (digests != null && digests[i] != null) {
                sha.update(digests[i]);
            }
            signatures[i] = java.util.Arrays.copyOf(sha.digest(), 16);
            mergeable[i] = i != root && i != info && (index.has(i, Index.VALUE_LIKE) || kind == Index.STREAM
                    || kind == Index.SCALAR || kind == Index.MISSING || kind == Index.UNSEEN);
        }
        rep = Merge.representatives(signatures, edges, mergeable);
    }

    private void liveness(int root, int info, Set<Integer> indirect) {
        int n = index.size();
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
            byte kind = index.kind(i);
            inline[i] = live[i] && refs[i] == 1 && i != root && i != info && (kind == Index.DICT || kind == Index.ARRAY)
                    && index.has(i, Index.VALUE_LIKE) && !indirect.contains(i);
        }
        boolean[] onPath = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (live[i] && !inline[i] && container(index.kind(i))) {
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

    private void emit(int node, Bytes out, int number, int depth) throws IOException {
        byte[] body = index.bytes(node);
        if (body == null) {
            out.write(NULL);
            return;
        }
        int holes = index.holeCount(node);
        byte[][] strings = index.strings(node);
        int pos = 0;
        int string = 0;
        for (int k = 0; k < holes; k++) {
            int at = index.holeAt(node, k);
            out.write(body, pos, at - pos);
            pos = at;
            int start = out.size();
            int ref = index.holeRef(node, k);
            if (ref < 0) {
                byte[] raw = strings[string++];
                Lexical.string(encryption != null && number > 0 ? encryption.string(raw, number) : raw, out);
            } else {
                substitute(ref, out, number, depth);
            }
            space(out, start, body, pos);
        }
        out.write(body, pos, body.length - pos);
    }

    private void substitute(int target, Bytes out, int number, int depth) throws IOException {
        int t = rep[target];
        byte kind = index.kind(t);
        boolean regularBefore = out.size() > 0 && Lexical.regular(out.at(out.size() - 1) & 0xFF);
        if (kind == Index.SCALAR || inline[t] && depth < MAX_INLINE_DEPTH * 2) {
            byte[] body = index.bytes(t);
            if (regularBefore && body != null && body.length > 0 && Lexical.regular(body[0] & 0xFF)) {
                out.write(' ');
            }
            emit(t, out, number, depth + 1);
            return;
        }
        if (regularBefore) {
            out.write(' ');
        }
        if (container(kind) && numbers[t] > 0) {
            out.ascii(numbers[t] + " 0 R");
        } else {
            out.write(NULL);
        }
    }

    private static void space(Bytes out, int start, byte[] body, int pos) {
        if (out.size() > start && pos < body.length && Lexical.regular(out.at(out.size() - 1) & 0xFF)
                && Lexical.regular(body[pos] & 0xFF)) {
            out.write(' ');
        }
    }

    private int write(CountingOutput out, float version, boolean objectStreams, int root, int info, COSArray id)
            throws IOException {
        int n = index.size();
        List<Integer> packed = new ArrayList<>();
        List<Integer> plain = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!live[i] || inline[i] || !container(index.kind(i))) {
                continue;
            }
            if (index.kind(i) == Index.STREAM || !objectStreams) {
                plain.add(i);
            } else {
                packed.add(i);
            }
        }
        packed.sort(Comparator.comparing((Integer i) -> index.group(i) == null ? "" : index.group(i))
                .thenComparingInt(i -> i));
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
            emit(i, b, number, 0);
            if (index.kind(i) == Index.STREAM) {
                if (encryption == null) {
                    b.ascii("stream\n");
                    b.writeTo(out);
                    spill.copy(spilled[i], out);
                } else {
                    Bytes clear = new Bytes(spill.length(spilled[i]));
                    spill.copy(spilled[i], clear);
                    COSBase s = source.load(index.key(i));
                    byte[] data = encryption.stream(clear.toByteArray(), number,
                            s instanceof COSStream cs ? cs : new COSStream());
                    byte[] dict = b.toByteArray();
                    b.reset();
                    b.write(lengthReplaced(dict, spill.length(spilled[i]), data.length));
                    b.ascii("stream\n");
                    b.writeTo(out);
                    out.write(data);
                }
                out.ascii("\nendstream\nendobj\n");
            } else {
                b.ascii("\nendobj\n");
                b.writeTo(out);
            }
        }
        ExecutorService pool = containers > 1 && Runtime.getRuntime().availableProcessors() > 1
                ? Executors.newFixedThreadPool(Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()),
                        Thread.ofPlatform().name("pdf-optimize-pack-", 0).daemon().factory()) : null;
        try {
            ArrayDeque<Future<byte[]>> queue = new ArrayDeque<>();
            ArrayDeque<int[]> meta = new ArrayDeque<>();
            for (int c = 0; c <= containers; c++) {
                if (c < containers) {
                    PdfFiles.stopIfInterrupted();
                    Bytes body = new Bytes(1 << 16);
                    StringBuilder idx = new StringBuilder();
                    int from = c * OBJECTS_PER_STREAM;
                    int to = Math.min(packed.size(), from + OBJECTS_PER_STREAM);
                    for (int k = from; k < to; k++) {
                        int node = packed.get(k);
                        if (k > from) {
                            idx.append(' ');
                            if (body.size() > 0 && Lexical.regular(body.at(body.size() - 1) & 0xFF)) {
                                body.write(' ');
                            }
                        }
                        idx.append(numbers[node]).append(' ').append(body.size());
                        containerOf[numbers[node]] = firstContainer + c;
                        indexIn[numbers[node]] = k - from;
                        emit(node, body, 0, 0);
                    }
                    idx.append('\n');
                    Bytes raw = new Bytes(idx.length() + body.size());
                    raw.ascii(idx.toString());
                    body.writeTo(raw);
                    byte[] data = raw.toByteArray();
                    int number = firstContainer + c;
                    java.util.concurrent.Callable<byte[]> job = () -> {
                        byte[] z = Deflate.best(data, options.effort());
                        return encryption != null ? encryption.encrypt(z, number) : z;
                    };
                    queue.add(pool != null ? pool.submit(job) : java.util.concurrent.CompletableFuture
                            .completedFuture(job.call()));
                    meta.add(new int[] {number, to - from, idx.length()});
                }
                while (!queue.isEmpty() && (c == containers || queue.size() > 2 * MAX_THREADS)) {
                    byte[] data = get(queue.poll());
                    int[] m = meta.poll();
                    offsets[m[0]] = out.position();
                    out.ascii(m[0] + " 0 obj\n<</Type/ObjStm/N " + m[1] + "/First " + m[2]
                            + "/Filter/FlateDecode/Length " + data.length + ">>stream\n");
                    out.write(data);
                    out.ascii("\nendstream\nendobj\n");
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        if (encryption != null) {
            offsets[encryptNumber] = out.position();
            Bytes e = new Bytes(512);
            e.ascii(encryptNumber + " 0 obj\n");
            new Syntax((ref, s) -> s.value(ref.getObject())).into(e).dictionary(encryption.dictionary, null);
            e.ascii("\nendobj\n");
            e.writeTo(out);
        }
        Bytes trailerId = new Bytes(96);
        if (id != null) {
            new Syntax((ref, s) -> s.word("null")).into(trailerId).value(id);
        }
        String head = "/Size " + size + "/Root " + numbers[root] + " 0 R" + (info >= 0 && numbers[info] > 0
                ? "/Info " + numbers[info] + " 0 R" : "") + (encryption != null ? "/Encrypt " + encryptNumber + " 0 R"
                : "");
        if (objectStreams) {
            xrefStream(out, size, xrefNumber, offsets, containerOf, indexIn, head, trailerId);
        } else {
            xrefTable(out, size, offsets, head, trailerId);
        }
        out.flush();
        return size - 1;
    }

    private static byte[] get(Future<byte[]> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted while optimizing");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IOException(e.getCause());
        }
    }

    private static byte[] lengthReplaced(byte[] dict, int oldLength, int newLength) {
        String s = new String(dict, java.nio.charset.StandardCharsets.ISO_8859_1);
        String from = "/Length " + oldLength;
        int at = s.lastIndexOf(from);
        if (at < 0) {
            return dict;
        }
        return (s.substring(0, at) + "/Length " + newLength + s.substring(at + from.length()))
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
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
        byte[] data = Deflate.best(Predictors.encodePng(rows, new Predictors.Layout(1, 8, row), Predictors.UP),
                options.effort() == PdfOptimizer.Effort.MAX ? PdfOptimizer.Effort.MAX : PdfOptimizer.Effort.FAST);
        boolean usePredictor = true;
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
