package stirling.software.officeconvert.topdf.odf;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.XmlSalvage;

public final class OdfDocument implements Closeable {

    public enum Kind {
        TEXT,
        SPREADSHEET,
        PRESENTATION
    }

    public static final String PASSWORD = "The document is password protected; remove the password and try again";

    static final int MAX_ENTRIES = 10_000;

    static final long MAX_XML_BYTES = 256L << 20;

    static final long MAX_FLAT_BYTES = 256L << 20;

    static final int MAX_PICTURE_BYTES = 64 << 20;

    static final long MAX_TOTAL_BYTES = 1L << 30;

    private final Kind kind;

    private final ZipFile zip;

    private final Map<String, ZipEntry> entries = new HashMap<>();

    private final Element content;

    private final Element styles;

    private final Element settings;

    private long budget = MAX_TOTAL_BYTES;

    private boolean damaged;

    private Element meta;

    private boolean metaRead;

    private OdfDocument(Kind kind, ZipFile zip, Element content, Element styles, Element settings) {
        this.kind = kind;
        this.zip = zip;
        this.content = content;
        this.styles = styles;
        this.settings = settings;
    }

    public Kind kind() {
        return kind;
    }

    Element content() {
        return content;
    }

    Element styles() {
        return styles;
    }

    Element settings() {
        return settings;
    }

    Element meta() {
        if (!metaRead) {
            metaRead = true;
            try {
                Element root = zip == null ? content : xml("meta.xml", false);
                meta = Dom.kid(root, Ns.OFFICE, "meta");
            } catch (IOException | RuntimeException e) {
                meta = null;
            }
        }
        return meta;
    }

    boolean damaged() {
        return damaged;
    }

    boolean flat() {
        return zip == null;
    }

    boolean hasMacros() {
        if (zip == null) {
            return Dom.kid(content, Ns.OFFICE, "scripts") != null
                    && !Dom.kids(Dom.kid(content, Ns.OFFICE, "scripts")).isEmpty();
        }
        for (String name : entries.keySet()) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.startsWith("basic/") || n.startsWith("scripts/")) {
                return true;
            }
        }
        Element scripts = Dom.kid(content, Ns.OFFICE, "scripts");
        return scripts != null && !Dom.kids(scripts).isEmpty();
    }

    public static long estimate(Path file) {
        try {
            long size = Files.size(file);
            if (size > 0 && isZip(file)) {
                long xml = 0;
                try (ZipFile z = new ZipFile(file.toFile())) {
                    for (String name : new String[] {"content.xml", "styles.xml"}) {
                        ZipEntry e = z.getEntry(name);
                        if (e != null && e.getSize() > 0) {
                            xml += e.getSize();
                        }
                    }
                }
                return (64L << 20) + Math.min(MAX_XML_BYTES * 2, xml) * 10 + size * 2;
            }
            return (64L << 20) + size * 12;
        } catch (IOException | RuntimeException e) {
            return 256L << 20;
        }
    }

    public static Kind sniff(Path file) {
        try {
            if (isZip(file)) {
                try (ZipFile z = new ZipFile(file.toFile())) {
                    return kindOf(mimetype(z));
                }
            }
            return flatKind(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static OdfDocument open(Path file) throws IOException {
        if (isZip(file)) {
            return openZip(file);
        }
        if (Files.size(file) > MAX_FLAT_BYTES) {
            throw new OfficeZip.Oversized("The document is too large: a flat OpenDocument file over "
                    + (MAX_FLAT_BYTES >> 20) + " MB");
        }
        Kind kind = flatKind(file);
        if (kind == null) {
            throw new IOException("The file is not an OpenDocument text, spreadsheet or presentation");
        }
        byte[] data = Files.readAllBytes(file);
        Document doc;
        boolean damaged = false;
        try {
            doc = SecureXml.parse(new ByteArrayInputStream(data));
        } catch (IOException e) {
            byte[] declared = withPrefixes(data, e);
            Document fixed = declared == data ? null : parseOrNull(declared);
            if (fixed != null) {
                doc = fixed;
            } else {
                byte[] salvaged = SecureXml.refusedDoctype(e) ? null : XmlSalvage.salvage(declared);
                if (salvaged == null || salvaged == declared) {
                    throw e;
                }
                doc = SecureXml.parse(new ByteArrayInputStream(salvaged));
                damaged = true;
            }
        }
        Element root = doc.getDocumentElement();
        OdfDocument d = new OdfDocument(kind, null, root, root, Dom.kid(root, Ns.OFFICE, "settings"));
        d.damaged = damaged;
        return d;
    }

    private static OdfDocument openZip(Path file) throws IOException {
        ZipFile z = new ZipFile(file.toFile());
        try {
            if (z.size() > MAX_ENTRIES) {
                throw new OfficeZip.Oversized("The document is too large: it has more than " + MAX_ENTRIES + " parts");
            }
            Kind kind = kindOf(mimetype(z));
            if (kind == null) {
                throw new IOException("The file is not an OpenDocument text, spreadsheet or presentation");
            }
            Map<String, ZipEntry> map = new HashMap<>();
            Enumeration<? extends ZipEntry> all = z.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (!e.isDirectory()) {
                    map.putIfAbsent(e.getName().replace('\\', '/').replaceFirst("^/+", ""), e);
                }
            }
            OdfDocument probe = new OdfDocument(kind, z, null, null, null);
            probe.entries.putAll(map);
            if (probe.encrypted()) {
                throw new IOException(PASSWORD);
            }
            Element content = probe.xml("content.xml", true);
            Element styles = probe.xml("styles.xml", false);
            Element settings = probe.xml("settings.xml", false);
            if (styles == null) {
                styles = content;
            }
            OdfDocument doc = new OdfDocument(kind, z, content, styles, settings);
            doc.entries.putAll(map);
            doc.budget = probe.budget;
            doc.damaged = probe.damaged;
            return doc;
        } catch (IOException | RuntimeException e) {
            z.close();
            throw e;
        }
    }

    private boolean encrypted() throws IOException {
        byte[] manifest = bytes("META-INF/manifest.xml", 16L << 20);
        if (manifest == null) {
            return false;
        }
        String m = new String(manifest, StandardCharsets.UTF_8);
        return m.contains("encryption-data");
    }

    private Element xml(String name, boolean required) throws IOException {
        byte[] data;
        try {
            data = bytes(name, MAX_XML_BYTES);
        } catch (OfficeZip.Oversized e) {
            throw e;
        } catch (IOException e) {
            if (required) {
                throw e;
            }
            return null;
        }
        if (data == null) {
            if (required) {
                throw new IOException("The document is damaged: it has no " + name);
            }
            return null;
        }
        try {
            return SecureXml.parse(new ByteArrayInputStream(data)).getDocumentElement();
        } catch (IOException e) {
            byte[] declared = withPrefixes(data, e);
            Document fixed = declared == data ? null : parseOrNull(declared);
            if (fixed != null) {
                return fixed.getDocumentElement();
            }
            data = declared;
            byte[] salvaged = SecureXml.refusedDoctype(e) ? null : XmlSalvage.salvage(data);
            if (salvaged != null && salvaged != data) {
                damaged = true;
                return SecureXml.parse(new ByteArrayInputStream(salvaged)).getDocumentElement();
            }
            if (required || SecureXml.refusedDoctype(e)) {
                throw new IOException("The document is damaged: " + name + " is not well-formed XML", e);
            }
            return null;
        }
    }

    private static byte[] withPrefixes(byte[] data, IOException e) {
        byte[] declared = OdfPrefixes.unbound(e) ? OdfPrefixes.declare(data) : null;
        return declared == null ? data : declared;
    }

    private static Document parseOrNull(byte[] data) {
        try {
            return SecureXml.parse(new ByteArrayInputStream(data));
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] bytes(String name, long max) throws IOException {
        ZipEntry e = entries.get(name);
        if (e == null) {
            return null;
        }
        if (e.getSize() > max) {
            throw new OfficeZip.Oversized("The document is too large: the part /" + name + " is over "
                    + (max >> 20) + " MB");
        }
        try (InputStream in = zip.getInputStream(e)) {
            byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16, Math.min(max, budget) + 1));
            if (data.length > max) {
                throw new OfficeZip.Oversized("The document is too large: the part /" + name + " is over "
                        + (max >> 20) + " MB");
            }
            if (data.length > budget) {
                throw new OfficeZip.Oversized("The document is too large: over " + (MAX_TOTAL_BYTES >> 20)
                        + " MB uncompressed");
            }
            budget -= data.length;
            return data;
        }
    }

    byte[] picture(String href) {
        String name = internal(href);
        if (name == null || zip == null) {
            return null;
        }
        try {
            return bytes(name, MAX_PICTURE_BYTES);
        } catch (IOException e) {
            return null;
        }
    }

    String chart(Element object) {
        Element inline = Dom.kid(object, Ns.OFFICE, "document");
        if (inline != null) {
            return OdfChart.part(inline, inline);
        }
        String dir = internal(Dom.attr(object, Ns.XLINK, "href"));
        if (dir == null || zip == null) {
            return null;
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        try {
            byte[] content = bytes(dir + "/content.xml", MAX_PICTURE_BYTES);
            if (content == null) {
                return null;
            }
            byte[] styles = bytes(dir + "/styles.xml", MAX_PICTURE_BYTES);
            Element c = SecureXml.parse(new ByteArrayInputStream(content)).getDocumentElement();
            Element st = styles == null ? null : SecureXml.parse(new ByteArrayInputStream(styles)).getDocumentElement();
            return OdfChart.part(c, st);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    boolean exists(String href) {
        String name = internal(href);
        return name != null && zip != null && entries.containsKey(name);
    }

    static byte[] binaryData(Element image) {
        Element bin = Dom.kid(image, Ns.OFFICE, "binary-data");
        if (bin == null) {
            return null;
        }
        String b64 = bin.getTextContent();
        if (b64 == null || b64.length() > MAX_PICTURE_BYTES * 4L / 3 + 4096) {
            return null;
        }
        try {
            return Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean external(String href) {
        return href != null && !href.isBlank() && internal(href) == null;
    }

    static String internal(String href) {
        if (href == null) {
            return null;
        }
        String h = href.trim();
        while (h.startsWith("./")) {
            h = h.substring(2);
        }
        if (h.isEmpty() || h.startsWith("/") || h.startsWith("\\") || h.startsWith("#")
                || h.matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.*")) {
            return null;
        }
        for (String seg : h.replace('\\', '/').split("/")) {
            if (seg.equals("..")) {
                return null;
            }
        }
        return h.replace('\\', '/');
    }

    private static boolean isZip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        }
    }

    private static String mimetype(ZipFile z) throws IOException {
        ZipEntry e = z.getEntry("mimetype");
        if (e != null && e.getSize() <= 512) {
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readNBytes(512), StandardCharsets.US_ASCII).trim();
            }
        }
        ZipEntry m = z.getEntry("META-INF/manifest.xml");
        if (m == null || m.getSize() > (4L << 20)) {
            return null;
        }
        Document doc;
        try (InputStream in = z.getInputStream(m)) {
            doc = SecureXml.parse(new ByteArrayInputStream(in.readNBytes(4 << 20)));
        }
        for (Element f : Dom.kids(doc.getDocumentElement(), Ns.MANIFEST, "file-entry")) {
            if ("/".equals(Dom.attr(f, Ns.MANIFEST, "full-path"))) {
                return Dom.attr(f, Ns.MANIFEST, "media-type");
            }
        }
        return null;
    }

    static Kind kindOf(String mimetype) {
        if (mimetype == null) {
            return null;
        }
        return switch (mimetype.trim().toLowerCase(Locale.ROOT)) {
            case "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template",
                    "application/vnd.oasis.opendocument.text-master",
                    "application/vnd.oasis.opendocument.text-master-template" -> Kind.TEXT;
            case "application/vnd.oasis.opendocument.spreadsheet",
                    "application/vnd.oasis.opendocument.spreadsheet-template" -> Kind.SPREADSHEET;
            case "application/vnd.oasis.opendocument.presentation",
                    "application/vnd.oasis.opendocument.presentation-template" -> Kind.PRESENTATION;
            default -> null;
        };
    }

    private static Kind flatKind(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(1024);
            String h = new String(head, StandardCharsets.UTF_8).replace("﻿", "").stripLeading();
            if (!h.startsWith("<")) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = SecureXml.reader(in);
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT) {
                        if (!Ns.OFFICE.equals(r.getNamespaceURI()) || !"document".equals(r.getLocalName())) {
                            return null;
                        }
                        return kindOf(r.getAttributeValue(Ns.OFFICE, "mimetype"));
                    }
                }
                return null;
            } finally {
                r.close();
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        if (zip != null) {
            zip.close();
        }
    }
}
