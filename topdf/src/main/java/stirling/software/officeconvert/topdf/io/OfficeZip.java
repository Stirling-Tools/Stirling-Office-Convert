package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class OfficeZip implements Closeable {

    // Parts past the inflate ratio (metafiles, flat bitmaps) may be read up to maxDenseBytes in all; past that
    // size the package as a whole may not inflate that much
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, double minInflateRatio,
            long graceBytes, long maxDenseBytes) {

        public static final Limits DEFAULT = new Limits(10_000, 512L << 20, 1L << 30, 0.01, 100L << 10, 48L << 20);

        public Limits {
            if (maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < 1 || graceBytes < 0 || maxDenseBytes < 0) {
                throw new IllegalArgumentException("Zip limits must be positive");
            }
            if (!(minInflateRatio >= 0 && minInflateRatio < 1)) {
                throw new IllegalArgumentException("minInflateRatio must be in [0, 1), was " + minInflateRatio);
            }
        }

        public Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, double minInflateRatio, long graceBytes) {
            this(maxEntries, maxEntryBytes, maxTotalBytes, minInflateRatio, graceBytes, 0);
        }
    }

    public static final String CONTENT_TYPES = "/[Content_Types].xml";

    public static final String PACKAGE_RELS = "/_rels/.rels";

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");

    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
        (byte) 0xE1};

    private final Path path;

    private final ZipFile zip;

    private final Limits limits;

    private final Map<String, ZipEntry> entries;

    private final List<String> names;

    private final Map<String, String> defaults = new HashMap<>();

    private final Map<String, String> overrides = new HashMap<>();

    private final Map<String, Relationships> relationships = new ConcurrentHashMap<>();

    private final Set<String> leftOut;

    private final Set<String> notes = new LinkedHashSet<>();

    private final Set<String> unreadableRelationships = ConcurrentHashMap.newKeySet();

    private final Map<String, String> dense = new HashMap<>();

    private final Set<String> denseRead = new HashSet<>();

    private long denseBytes;

    private final Path scratch;

    private final Set<String> salvage = new HashSet<>();

    private final Map<String, byte[]> salvagedParts = new HashMap<>();

    private OfficeZip(Path path, ZipFile zip, Path scratch, Limits limits, Set<String> leftOut) throws IOException {
        this.path = path;
        this.zip = zip;
        this.scratch = scratch;
        this.limits = limits;
        if (zip.size() > limits.maxEntries()) {
            throw new Oversized("The document is too large: it has more than " + limits.maxEntries() + " parts");
        }
        Map<String, ZipEntry> map = new HashMap<>();
        List<String> order = new ArrayList<>();
        long total = 0;
        long compressed = 0;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry e = all.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String key = key(e.getName());
            if (key.isEmpty()) {
                continue;
            }
            if (map.putIfAbsent(key, e) != null) {
                throw new IOException("The document is damaged: the part /" + e.getName() + " appears twice");
            }
            long size = e.getSize();
            long csize = e.getCompressedSize();
            if (size < 0 || csize < 0) {
                throw new IOException("The document is damaged: the part /" + e.getName() + " has no size");
            }
            if (size > limits.maxEntryBytes()) {
                throw new Oversized("The document is too large: the part /" + e.getName() + " is " + mb(size)
                        + " MB uncompressed, over the " + mb(limits.maxEntryBytes()) + " MB limit");
            }
            total += size;
            if (total > limits.maxTotalBytes()) {
                throw new Oversized("The document is too large: over " + mb(limits.maxTotalBytes())
                        + " MB uncompressed");
            }
            if (size > limits.graceBytes() && (double) csize / size < limits.minInflateRatio()) {
                String inflates = "The document looks like a zip bomb: the part /" + e.getName() + " inflates "
                        + (csize == 0 ? "without limit" : (size / csize) + " times");
                if (csize == 0) {
                    throw new Oversized(inflates);
                }
                dense.put(key, inflates);
            }
            compressed += csize;
            order.add("/" + e.getName().replace('\\', '/').replaceFirst("^/+", ""));
        }
        if (compressed > Files.size(scratch == null ? path : scratch)) {
            throw new IOException("The document is damaged: its zip entries overlap");
        }
        if (total > Math.max(limits.graceBytes(), limits.maxDenseBytes())
                && (double) compressed / total < limits.minInflateRatio()) {
            throw new Oversized("The document looks like a zip bomb: it inflates "
                    + (compressed == 0 ? "without limit" : (total / compressed) + " times"));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : leftOut) {
            String k = key(part);
            if (!k.isEmpty() && !k.equals(key(CONTENT_TYPES)) && map.remove(k) != null) {
                out.add(canonical(part));
                order.removeIf(name -> key(name).equals(k));
            }
        }
        this.leftOut = Collections.unmodifiableSet(out);
        this.entries = Collections.unmodifiableMap(map);
        this.names = Collections.unmodifiableList(order);
        if (!exists(CONTENT_TYPES)) {
            if (exists("/mimetype") && exists("/META-INF/manifest.xml")) {
                throw new IOException("The file is an OpenDocument file of a kind that is not supported (only text,"
                        + " spreadsheets and presentations are)");
            }
            throw new LostPart("The file is not an Office document: the zip package has no [Content_Types].xml", null);
        }
        try {
            readContentTypes();
        } catch (DamagedPart e) {
            throw new LostPart(e.getMessage(), e);
        }
    }

    public static OfficeZip open(Path file) throws IOException {
        return open(file, Limits.DEFAULT);
    }

    public static OfficeZip open(Path file, Limits limits) throws IOException {
        return open(file, limits, Set.of());
    }

    // The parts left out read as missing: damaged parts the caller chose to skip
    public static OfficeZip open(Path file, Limits limits, Set<String> leftOut) throws IOException {
        return open(file, limits, leftOut, Set.of());
    }

    // Salvaged XML parts read as their well-formed start, closed again where the damage begins
    public static OfficeZip open(Path file, Limits limits, Set<String> leftOut, Set<String> salvaged)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(leftOut, "leftOut");
        Objects.requireNonNull(salvaged, "salvaged");
        checkNotInterrupted();
        refuseOtherKinds(file);
        Set<String> drop = new LinkedHashSet<>();
        OfficeZip zip;
        try {
            zip = open(file, limits, leftOut, salvaged, null);
        } catch (LostPart e) {
            drop.add(key(CONTENT_TYPES));
            zip = rebuilt(file, limits, leftOut, salvaged, drop, e);
        }
        IOException noMain = zip.lostMain();
        if (noMain == null) {
            return zip;
        }
        zip.close();
        drop.add(key(PACKAGE_RELS));
        return rebuilt(file, limits, leftOut, salvaged, drop, noMain);
    }

    // A package whose [Content_Types].xml or /_rels/.rels is missing or damaged is made again without them, as
    // Office repairs it: content types from the part names, the main part by its usual name
    private static OfficeZip rebuilt(Path file, Limits limits, Set<String> leftOut, Set<String> salvaged,
            Set<String> drop, IOException original) throws IOException {
        try {
            OfficeZip zip = open(file, limits, leftOut, salvaged, drop);
            if (drop.contains(key(PACKAGE_RELS)) && zip.lostMain() != null) {
                zip.close();
                throw original;
            }
            return zip;
        } catch (IOException | RuntimeException e) {
            if (e == original) {
                throw original;
            }
            if (e instanceof InterruptedIOException i) {
                throw i;
            }
            original.addSuppressed(e);
            throw original;
        }
    }

    // Why the main part cannot be found, when a rebuilt package relationships part might find it
    private IOException lostMain() throws InterruptedIOException {
        try {
            mainPart();
            return null;
        } catch (DamagedPart e) {
            return e.part().equalsIgnoreCase(PACKAGE_RELS) ? e : null;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().endsWith(NO_MAIN) ? e : null;
        }
    }

    private static final String NO_MAIN = "it names no main document part";

    private static OfficeZip open(Path file, Limits limits, Set<String> leftOut, Set<String> salvaged,
            Set<String> drop) throws IOException {
        ZipFile zf = null;
        ZipRepair.Repaired repaired = null;
        try {
            try {
                if (drop != null) {
                    repaired = ZipRepair.rebuild(file, limits, drop);
                    if (repaired == null) {
                        throw new IOException("The file is not an Office document: no part of it can be read");
                    }
                    zf = new ZipFile(repaired.file().toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8);
                } else {
                    zf = new ZipFile(file.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8);
                }
            } catch (ZipException e) {
                if (drop != null) {
                    throw new IOException("The file is not an Office document: it is not a valid zip package", e);
                }
                repaired = ZipRepair.rebuild(file, limits);
                if (repaired == null) {
                    throw new IOException("The file is not an Office document: it is not a valid zip package", e);
                }
                try {
                    zf = new ZipFile(repaired.file().toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8);
                } catch (ZipException x) {
                    x.addSuppressed(e);
                    throw new IOException("The file is not an Office document: it is not a valid zip package", x);
                }
            }
            OfficeZip opened = new OfficeZip(file, zf, repaired == null ? null : repaired.file(), limits, leftOut);
            for (String part : salvaged) {
                opened.salvage.add(key(part));
            }
            if (repaired != null) {
                opened.note("The file was damaged" + (drop == null ? " (its zip directory is missing or broken)" : "")
                        + " and was repaired from "
                        + repaired.parts() + " parts" + (repaired.lost() == 0 ? ""
                                : "; " + repaired.lost() + " damaged or incomplete parts were left out")
                        + (repaired.cut() == 0 ? "" : "; " + repaired.cut() + " parts cut short were kept up to the damage")
                        + (repaired.guessedTypes() ? "; its content types were lost and were guessed from the part names"
                                : "")
                        + (repaired.guessedMain() ? "; its main part was found by its usual name" : ""));
            }
            return opened;
        } catch (IOException | RuntimeException e) {
            try {
                if (zf != null) {
                    zf.close();
                }
                if (repaired != null) {
                    Files.deleteIfExists(repaired.file());
                }
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            if (e instanceof IllegalArgumentException) {
                throw new IOException("The document is damaged: " + e.getMessage(), e);
            }
            throw e;
        }
    }

    // True when the package was rebuilt from its local headers: a damaged zip directory, or lost package parts
    public boolean repaired() {
        return scratch != null;
    }

    public Path path() {
        return path;
    }

    public Limits limits() {
        return limits;
    }

    public Set<String> leftOut() {
        return leftOut;
    }

    // What readers of the package skipped on their own, for the conversion's warnings
    public List<String> notes() {
        synchronized (notes) {
            return List.copyOf(notes);
        }
    }

    void note(String message) {
        synchronized (notes) {
            notes.add(message);
        }
    }

    // Relationships parts other than the package's that could not be read (damaged or with a DOCTYPE)
    public Set<String> unreadableRelationships() {
        return Set.copyOf(unreadableRelationships);
    }

    public List<String> partNames() {
        return names;
    }

    public boolean exists(String part) {
        return entry(part) != null;
    }

    public long size(String part) {
        ZipEntry e = entry(part);
        return e == null ? -1 : e.getSize();
    }

    public InputStream open(String part) throws IOException {
        ZipEntry e = entry(part);
        if (e == null) {
            throw new FileNotFoundException("The document has no part " + canonical(part));
        }
        checkNotInterrupted();
        chargeDense(key(e.getName()), e.getSize(), canonical(part));
        if (salvage.contains(key(e.getName()))) {
            return new ByteArrayInputStream(salvaged(e, canonical(part)));
        }
        try {
            return new PartStream(zip.getInputStream(e), e.getSize(), canonical(part));
        } catch (ZipException | EOFException x) {
            throw PartStream.unreadable(canonical(part), x);
        }
    }

    /** Up to max bytes from the start of a part, such as a picture's header, not counted as reading the part. */
    public byte[] head(String part, int max) throws IOException {
        ZipEntry e = entry(part);
        if (e == null) {
            throw new FileNotFoundException("The document has no part " + canonical(part));
        }
        checkNotInterrupted();
        if (salvage.contains(key(e.getName()))) {
            return new byte[0];
        }
        try (InputStream in = new PartStream(zip.getInputStream(e), e.getSize(), canonical(part))) {
            return in.readNBytes(max);
        } catch (ZipException | EOFException x) {
            throw PartStream.unreadable(canonical(part), x);
        }
    }

    // The part a stream from open(...) reads, or null for any other stream
    static String partName(InputStream in) {
        return in instanceof PartStream p ? p.name : null;
    }

    public byte[] read(String part) throws IOException {
        try (InputStream in = open(part)) {
            return in.readAllBytes();
        }
    }

    public InputStream open(Relationship r) throws IOException {
        return open(followable(r));
    }

    public byte[] read(Relationship r) throws IOException {
        return read(followable(r));
    }

    public Document xml(Relationship r) throws IOException {
        return xml(followable(r));
    }

    public Document xml(String part) throws IOException {
        try (InputStream in = open(part)) {
            return SecureXml.parse(in);
        } catch (InterruptedIOException | FileNotFoundException | DamagedPart e) {
            throw e;
        } catch (IOException e) {
            String message = "The document is damaged: " + canonical(part) + " is not well-formed XML ("
                    + e.getMessage() + ")";
            throw SecureXml.refusedDoctype(e) ? new IOException(message, e)
                    : new DamagedPart(canonical(part), message, e);
        }
    }

    public String contentType(String part) {
        String key = key(part);
        String type = overrides.get(key);
        if (type != null) {
            return type;
        }
        int slash = key.lastIndexOf('/');
        int dot = key.lastIndexOf('.');
        return dot > slash ? defaults.get(key.substring(dot + 1)) : null;
    }

    public Relationships packageRelationships() throws IOException {
        return relationships("/");
    }

    public Relationships relationships(String sourcePart) throws IOException {
        String source = sourcePart == null || sourcePart.isEmpty() || sourcePart.equals("/") ? "/" : canonical(sourcePart);
        Relationships known = relationships.get(source);
        if (known != null) {
            return known;
        }
        String rels = relsPartFor(source);
        Relationships parsed;
        try {
            parsed = exists(rels) ? parseRelationships(source, rels) : Relationships.NONE;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            if (!source.equals("/")) {
                unreadableRelationships.add(rels);
            }
            throw e;
        }
        Relationships raced = relationships.putIfAbsent(source, parsed);
        return raced == null ? parsed : raced;
    }

    Relationships peekRelationships(String sourcePart) throws IOException {
        String source = canonical(sourcePart);
        Relationships known = relationships.get(source);
        if (known != null) {
            return known;
        }
        String rels = relsPartFor(source);
        Relationships parsed = exists(rels) ? parseRelationships(source, rels) : Relationships.NONE;
        Relationships raced = relationships.putIfAbsent(source, parsed);
        return raced == null ? parsed : raced;
    }

    public String mainContentType() throws IOException {
        return contentType(mainPart());
    }

    public String mainPart() throws IOException {
        Relationship main = packageRelationships().first("officeDocument");
        if (main == null) {
            main = packageRelationships().first("http://schemas.microsoft.com/visio/2010/relationships/document");
        }
        if (main == null || main.part() == null || !exists(main.part())) {
            throw new IOException("The file is not an Office document: " + NO_MAIN);
        }
        return main.part();
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            if (scratch != null) {
                Files.deleteIfExists(scratch);
            }
        }
    }

    public static String relsPartFor(String part) {
        String source = part == null || part.isEmpty() || part.equals("/") ? "/" : canonical(part);
        if (source.equals("/")) {
            return PACKAGE_RELS;
        }
        int slash = source.lastIndexOf('/');
        return source.substring(0, slash + 1) + "_rels/" + source.substring(slash + 1) + ".rels";
    }

    public static String resolve(String sourcePart, String target) {
        if (target == null) {
            return null;
        }
        String t = target.strip().replace('\\', '/');
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || SCHEME.matcher(t).find()) {
            return null;
        }
        int cut = indexOfAny(t, '#', '?');
        if (cut >= 0) {
            t = t.substring(0, cut);
        }
        String source = sourcePart == null || sourcePart.isEmpty() ? "/" : canonical(sourcePart);
        String base = t.startsWith("/") ? "" : source.substring(0, source.lastIndexOf('/') + 1);
        Deque<String> segments = new ArrayDeque<>();
        for (String s : (base + "/" + t).split("/")) {
            if (s.isEmpty() || s.equals(".")) {
                continue;
            }
            if (s.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.removeLast();
            } else {
                segments.addLast(s);
            }
        }
        return segments.isEmpty() ? null : "/" + String.join("/", segments);
    }

    public static String canonical(String part) {
        String p = part.replace('\\', '/');
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String followable(Relationship r) throws IOException {
        Objects.requireNonNull(r, "relationship");
        ActiveContent.Kind kind = ActiveContent.of(r);
        if (kind != null) {
            throw new IOException("The document refers to " + kind.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " (" + r.typeName() + "), which is never loaded");
        }
        if (r.external() || r.part() == null) {
            throw new IOException("The relationship " + r.id() + " does not point inside the document");
        }
        return r.part();
    }

    // A dense part is refused only when something reads it, so an unread thumbnail never counts
    private void chargeDense(String key, long size, String part) throws DamagedPart {
        String inflates = dense.get(key);
        if (inflates == null) {
            return;
        }
        synchronized (denseRead) {
            if (denseRead.contains(key)) {
                return;
            }
            if (denseBytes + size > limits.maxDenseBytes()) {
                throw new DamagedPart(part, inflates, new Oversized(inflates));
            }
            denseBytes += size;
            denseRead.add(key);
        }
    }

    private byte[] salvaged(ZipEntry e, String part) throws IOException {
        synchronized (salvagedParts) {
            byte[] known = salvagedParts.get(key(e.getName()));
            if (known != null) {
                return known;
            }
        }
        if (e.getSize() > XmlSalvage.MAX_BYTES) {
            throw new DamagedPart(part, "The document is damaged: " + part + " is too large to salvage", null);
        }
        byte[] bytes = readWhatIsLeft(e, part);
        byte[] kept = XmlSalvage.salvage(bytes);
        if (kept == null) {
            throw new DamagedPart(part, "The document is damaged: nothing of " + part + " could be read", null);
        }
        if (kept != bytes) {
            note("The part " + part + " is damaged; only its start, up to the damage, was converted");
        }
        synchronized (salvagedParts) {
            salvagedParts.put(key(e.getName()), kept);
        }
        return kept;
    }

    private byte[] readWhatIsLeft(ZipEntry e, String part) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = new PartStream(zip.getInputStream(e), e.getSize(), part)) {
            in.transferTo(raw);
        } catch (DamagedPart | ZipException | EOFException x) {
            checkNotInterrupted();
        }
        return raw.toByteArray();
    }

    // True when the XML part is damaged but its start can still be read (see open with salvaged parts)
    public boolean salvageable(String part) throws IOException {
        ZipEntry e = entry(part);
        if (e == null || e.getSize() > XmlSalvage.MAX_BYTES) {
            return false;
        }
        byte[] bytes = readWhatIsLeft(e, canonical(part));
        byte[] kept = XmlSalvage.salvage(bytes);
        return kept != null && kept != bytes;
    }

    private ZipEntry entry(String part) {
        if (part == null) {
            return null;
        }
        ZipEntry e = entries.get(key(part));
        if (e == null && part.indexOf('%') >= 0) {
            try {
                e = entries.get(key(Percent.decode(part)));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return e;
    }

    private void readContentTypes() throws IOException {
        Element root = xml(CONTENT_TYPES).getDocumentElement();
        for (Node n = root == null ? null : root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el)) {
                continue;
            }
            String type = el.getAttribute("ContentType").strip();
            if (type.isEmpty()) {
                continue;
            }
            if ("Default".equals(el.getLocalName())) {
                String ext = el.getAttribute("Extension").strip().toLowerCase(Locale.ROOT);
                if (!ext.isEmpty()) {
                    defaults.putIfAbsent(ext, type);
                }
            } else if ("Override".equals(el.getLocalName())) {
                String name = el.getAttribute("PartName").strip();
                if (!name.isEmpty()) {
                    overrides.putIfAbsent(key(name), type);
                }
            }
        }
    }

    private Relationships parseRelationships(String source, String rels) throws IOException {
        Element root = xml(rels).getDocumentElement();
        List<Relationship> out = new ArrayList<>();
        for (Node n = root == null ? null : root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el) || !"Relationship".equals(el.getLocalName())) {
                continue;
            }
            String id = el.getAttribute("Id").strip();
            String type = el.getAttribute("Type").strip();
            String target = el.getAttribute("Target");
            if (id.isEmpty() || type.isEmpty() || !el.hasAttribute("Target")) {
                continue;
            }
            boolean external = "External".equalsIgnoreCase(el.getAttribute("TargetMode").strip());
            String t = target.strip().replace('\\', '/');
            if (!external && (t.startsWith("//") || SCHEME.matcher(t).find())) {
                external = true;
            }
            out.add(new Relationship(id, type, target, external, external ? null : resolve(source, target)));
        }
        return new Relationships(out);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static String key(String name) {
        String k = name.replace('\\', '/');
        int start = 0;
        while (start < k.length() && k.charAt(start) == '/') {
            start++;
        }
        return k.substring(start).toLowerCase(Locale.ROOT);
    }

    private static long mb(long bytes) {
        return (bytes + (1 << 20) - 1) >> 20;
    }

    public static void checkNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    private static void refuseOtherKinds(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(8);
        }
        if (head.length == 0) {
            throw new IOException("The file is empty");
        }
        String text = new String(head, StandardCharsets.ISO_8859_1);
        if (text.startsWith("%PDF")) {
            throw new IOException("The file is a PDF, not an Office document");
        }
        if (text.startsWith("{\\rtf")) {
            throw new IOException("The file is an RTF document, not an Office Open XML document");
        }
        if (head.length == OLE2.length && java.util.Arrays.equals(head, OLE2)) {
            if (encrypted(file)) {
                throw new IOException("The document is password protected; remove the password and try again");
            }
            throw new IOException("The file is a legacy binary Office document (Word, Excel or PowerPoint 97-2003),"
                    + " which is not supported yet");
        }
        if (head.length < 4 || head[0] != 'P' || head[1] != 'K') {
            throw new IOException("The file is not an Office document: it is not a zip package");
        }
    }

    private static boolean encrypted(Path file) {
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            return fs.getRoot().hasEntryCaseInsensitive("EncryptionInfo")
                    || fs.getRoot().hasEntryCaseInsensitive("EncryptedPackage");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // [Content_Types].xml is missing or not well-formed
    private static final class LostPart extends IOException {
        LostPart(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A part that cannot be read or parsed; the rest of the package may still be usable. */
    // Over a size limit or inflating like a zip bomb: no other reader should be handed it either
    public static final class Oversized extends IOException {

        public Oversized(String message) {
            super(message);
        }
    }

    public static final class DamagedPart extends IOException {

        private final String part;

        DamagedPart(String part, String message, Throwable cause) {
            super(message, cause);
            this.part = part;
        }

        public String part() {
            return part;
        }
    }

    private static final class PartStream extends FilterInputStream {

        private final String name;

        private long remaining;

        PartStream(InputStream in, long declared, String name) {
            super(in);
            this.remaining = declared;
            this.name = name;
        }

        @Override
        public int read() throws IOException {
            checkNotInterrupted();
            int b;
            try {
                b = super.read();
            } catch (ZipException | EOFException e) {
                throw unreadable(name, e);
            }
            if (b >= 0 && --remaining < 0) {
                throw overrun();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkNotInterrupted();
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            int n;
            try {
                n = super.read(b, off, (int) Math.min(len, remaining + 1));
            } catch (ZipException | EOFException e) {
                throw unreadable(name, e);
            }
            if (n > 0) {
                remaining -= n;
                if (remaining < 0) {
                    throw overrun();
                }
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] scratch = new byte[8192];
            long skipped = 0;
            while (skipped < n) {
                int r = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
                if (r < 0) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readlimit) {}

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset not supported");
        }

        private IOException overrun() {
            return new DamagedPart(name, "The document is damaged: the part " + name + " is larger than its zip"
                    + " entry says", null);
        }

        static DamagedPart unreadable(String name, IOException e) {
            return new DamagedPart(name, "The document is damaged: the part " + name + " cannot be read ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")", e);
        }
    }
}
