package stirling.software.officeconvert.topdf.xps;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.XmlElements;
import stirling.software.officeconvert.topdf.io.XmlSalvage;

final class XpsPackage implements Closeable {

    static final String XPS_REPRESENTATION = "http://schemas.microsoft.com/xps/2005/06/fixedrepresentation";

    static final String OXPS_REPRESENTATION = "http://schemas.openxps.org/oxps/v1.0/fixedrepresentation";

    static final int MAX_DOCUMENTS = 1_000;

    static final int MAX_OUTLINE = 100_000;

    static final int MAX_LINK_TARGETS = 1_000_000;

    private static final Pattern PIECE = Pattern.compile("/\\[(\\d{1,6})\\](\\.last)?\\.piece$", Pattern.CASE_INSENSITIVE);

    record Page(String part, int document, double width, double height, Set<String> targets) {}

    record OutlineEntry(String title, int level, String target) {}

    private final OfficeZip zip;

    private boolean oxps;

    private final List<Page> pages = new ArrayList<>();

    private final List<String> documents = new ArrayList<>();

    private final List<OutlineEntry> outline = new ArrayList<>();

    private final List<String> notes = new ArrayList<>();

    private final Map<String, List<String>> pieces = new HashMap<>();

    private XpsPackage(OfficeZip zip) {
        this.zip = zip;
    }

    static boolean repairedIs(Path source) {
        try (OfficeZip zip = OfficeZip.open(source)) {
            for (Relationship r : zip.packageRelationships().all()) {
                if (r.type().equals(XPS_REPRESENTATION) || r.type().equals(OXPS_REPRESENTATION)) {
                    return true;
                }
            }
            return false;
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static XpsPackage open(Path source, int maxPages) throws IOException {
        OfficeZip zip = OfficeZip.open(source);
        XpsPackage pkg = new XpsPackage(zip);
        try {
            pkg.index();
            pkg.load(maxPages);
            return pkg;
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    boolean openXps() {
        return oxps;
    }

    List<Page> pages() {
        return Collections.unmodifiableList(pages);
    }

    List<String> documents() {
        return Collections.unmodifiableList(documents);
    }

    List<OutlineEntry> outline() {
        return Collections.unmodifiableList(outline);
    }

    List<String> notes() {
        List<String> all = new ArrayList<>(zip.notes());
        all.addAll(notes);
        return all;
    }

    OfficeZip zip() {
        return zip;
    }

    private void index() {
        for (String name : zip.partNames()) {
            Matcher m = PIECE.matcher(name);
            if (m.find()) {
                String logical = name.substring(0, m.start()).toLowerCase(Locale.ROOT);
                pieces.computeIfAbsent(logical, k -> new ArrayList<>()).add(name);
            }
        }
        for (List<String> list : pieces.values()) {
            list.sort((a, b) -> Integer.compare(pieceIndex(a), pieceIndex(b)));
        }
    }

    private static int pieceIndex(String name) {
        Matcher m = PIECE.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    boolean exists(String part) {
        return part != null && (zip.exists(part) || pieces.containsKey(OfficeZip.canonical(part).toLowerCase(Locale.ROOT)));
    }

    long size(String part) {
        if (zip.exists(part)) {
            return zip.size(part);
        }
        List<String> list = pieces.get(OfficeZip.canonical(part).toLowerCase(Locale.ROOT));
        if (list == null) {
            return -1;
        }
        long total = 0;
        for (String p : list) {
            total += Math.max(0, zip.size(p));
        }
        return total;
    }

    InputStream open(String part) throws IOException {
        if (zip.exists(part)) {
            return zip.open(part);
        }
        List<String> list = pieces.get(OfficeZip.canonical(part).toLowerCase(Locale.ROOT));
        if (list == null) {
            throw new FileNotFoundException("The document has no part " + OfficeZip.canonical(part));
        }
        List<InputStream> streams = new ArrayList<>();
        for (String p : list) {
            streams.add(zip.open(p));
        }
        return new SequenceInputStream(Collections.enumeration(streams));
    }

    byte[] read(String part, long max) throws IOException {
        long size = size(part);
        if (size > max) {
            throw new IOException("The part " + part + " is larger than " + (max >> 20) + " MB");
        }
        try (InputStream in = open(part)) {
            byte[] data = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (data.length > max) {
                throw new IOException("The part " + part + " is larger than " + (max >> 20) + " MB");
            }
            return data;
        }
    }

    Document xml(String part) throws IOException {
        if (zip.exists(part)) {
            return zip.xml(part);
        }
        try (InputStream in = XmlElements.limit(open(part), OfficeZip.canonical(part), XmlElements.domElements())) {
            return SecureXml.parse(in);
        } catch (InterruptedIOException | FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("The document is damaged: " + part + " is not well-formed XML (" + e.getMessage()
                    + ")", e);
        }
    }

    String contentType(String part) {
        return zip.contentType(part);
    }

    private Relationship representation() throws IOException {
        Relationship found = null;
        for (Relationship r : zip.packageRelationships().all()) {
            if (r.type().equals(XPS_REPRESENTATION) || r.type().equals(OXPS_REPRESENTATION)) {
                if (!r.external() && r.part() != null && exists(r.part())) {
                    return r;
                }
                found = r;
            }
        }
        if (found != null) {
            throw new IOException("The XPS document is damaged: its document sequence " + found.target()
                    + " is missing");
        }
        throw new IOException("The file is not an XPS document: it names no fixed document sequence");
    }

    private void load(int maxPages) throws IOException {
        Relationship start = representation();
        oxps = start.type().equals(OXPS_REPRESENTATION);
        String startPart = start.part();
        Element root = xml(startPart).getDocumentElement();
        List<String> docs = new ArrayList<>();
        if ("FixedDocumentSequence".equals(root.getLocalName())) {
            for (Element ref : XpsXml.children(root, "DocumentReference")) {
                String target = source(startPart, XpsXml.attr(ref, "Source"));
                if (target != null && docs.size() < MAX_DOCUMENTS) {
                    docs.add(target);
                }
            }
        } else if ("FixedDocument".equals(root.getLocalName())) {
            docs.add(startPart);
        } else {
            throw new IOException("The XPS document is damaged: its start part is a " + root.getLocalName()
                    + ", not a fixed document sequence");
        }
        int lost = 0;
        for (String doc : docs) {
            OfficeZip.checkNotInterrupted();
            Element fixed;
            try {
                fixed = xml(doc).getDocumentElement();
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                lost++;
                continue;
            }
            if (!"FixedDocument".equals(fixed.getLocalName())) {
                lost++;
                continue;
            }
            int index = documents.size();
            documents.add(doc);
            int before = pages.size();
            for (Element content : XpsXml.children(fixed, "PageContent")) {
                String page = source(doc, XpsXml.attr(content, "Source"));
                if (page == null || !exists(page)) {
                    lost++;
                    continue;
                }
                Set<String> targets = new LinkedHashSet<>();
                Element holder = XpsXml.child(content, "PageContent.LinkTargets");
                if (holder != null) {
                    for (Element t : XpsXml.children(holder, "LinkTarget")) {
                        String name = XpsXml.attr(t, "Name");
                        if (name != null && targets.size() < MAX_LINK_TARGETS) {
                            targets.add(name);
                        }
                    }
                }
                pages.add(new Page(page, index, XpsXml.number(XpsXml.attr(content, "Width"), 0),
                        XpsXml.number(XpsXml.attr(content, "Height"), 0), targets));
                if (maxPages > 0 && pages.size() >= maxPages) {
                    break;
                }
            }
            if (pages.size() > before) {
                readOutline(doc, pages.get(before).part());
            }
            if (maxPages > 0 && pages.size() >= maxPages) {
                break;
            }
        }
        if (lost > 0) {
            notes.add(lost == 1 ? "A page or document of the XPS file is missing or damaged and was left out"
                    : lost + " pages or documents of the XPS file are missing or damaged and were left out");
        }
        if (pages.isEmpty()) {
            throw new IOException("The XPS document has no pages that can be read");
        }
    }

    private void readOutline(String doc, String firstPage) {
        try {
            List<Relationship> rels = new ArrayList<>();
            for (Relationship r : zip.relationships(doc).all()) {
                if (r.type().endsWith("/documentstructure") && !r.external() && r.part() != null) {
                    rels.add(r);
                }
            }
            for (Relationship r : rels) {
                if (!exists(r.part())) {
                    continue;
                }
                Element root = xml(r.part()).getDocumentElement();
                Element holder = XpsXml.child(root, "DocumentStructure.Outline");
                Element outlineRoot = holder == null ? null : XpsXml.child(holder, "DocumentOutline");
                if (outlineRoot == null) {
                    continue;
                }
                for (Element e : XpsXml.children(outlineRoot, "OutlineEntry")) {
                    if (outline.size() >= MAX_OUTLINE) {
                        return;
                    }
                    String target = XpsXml.attr(e, "OutlineTarget");
                    String title = e.getAttribute("Description");
                    int level = (int) Math.max(1, Math.min(32, XpsXml.number(XpsXml.attr(e, "OutlineLevel"), 1)));
                    String key = target == null ? firstPage : known(destination(r.part(), target), doc);
                    if (key != null && title != null && !title.isBlank()) {
                        outline.add(new OutlineEntry(title.strip(), level, key));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            notes.add("The outline of the XPS document could not be read, so the PDF has no bookmarks for it");
        }
    }

    private String source(String from, String value) {
        String target = OfficeZip.resolve(from, value);
        if ((target == null || !exists(target)) && value != null && value.indexOf('#') >= 0) {
            String literal = value.strip().startsWith("/") ? value.strip()
                    : from.substring(0, from.lastIndexOf('/') + 1) + value.strip();
            if (exists(literal)) {
                return OfficeZip.canonical(literal);
            }
        }
        return target;
    }

    Document page(String part) throws IOException {
        try {
            return xml(part);
        } catch (InterruptedIOException | FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            if (SecureXml.refusedDoctype(e)) {
                throw e;
            }
            byte[] raw = read(part, 64L << 20);
            byte[] fixed = XpsRepair.uniqueAttributes(raw);
            if (fixed != null) {
                try {
                    Document d = SecureXml.parse(XmlElements.limit(new java.io.ByteArrayInputStream(fixed), part,
                            XmlElements.domElements()));
                    notes.add("A damaged XPS page was repaired before it was converted");
                    return d;
                } catch (InterruptedIOException x) {
                    throw x;
                } catch (IOException x) {
                    raw = fixed;
                }
            }
            byte[] kept = XmlSalvage.salvage(raw);
            if (kept == null || kept == raw) {
                throw e;
            }
            notes.add("A damaged XPS page was converted only up to the damage");
            return SecureXml.parse(XmlElements.limit(new java.io.ByteArrayInputStream(kept), part,
                    XmlElements.domElements()));
        }
    }

    String known(String key, String doc) {
        if (key == null) {
            return null;
        }
        int hash = key.indexOf('#');
        String part = hash < 0 ? key : key.substring(0, hash);
        if (exists(part)) {
            return key;
        }
        return hash < 0 ? doc.toLowerCase(Locale.ROOT) : doc.toLowerCase(Locale.ROOT) + key.substring(hash);
    }

    static String destination(String sourcePart, String uri) {
        if (uri == null) {
            return null;
        }
        String u = uri.strip();
        int hash = u.indexOf('#');
        String path = hash < 0 ? u : u.substring(0, hash);
        String fragment = hash < 0 ? null : u.substring(hash + 1);
        String part = path.isEmpty() ? OfficeZip.canonical(sourcePart) : OfficeZip.resolve(sourcePart, path);
        if (part == null) {
            return null;
        }
        String key = part.toLowerCase(Locale.ROOT);
        return fragment == null || fragment.isEmpty() ? key : key + "#" + fragment;
    }

    Map<String, Integer> pageIndex() {
        Map<String, Integer> map = new TreeMap<>();
        for (int i = 0; i < pages.size(); i++) {
            map.putIfAbsent(pages.get(i).part().toLowerCase(Locale.ROOT), i);
        }
        return map;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
