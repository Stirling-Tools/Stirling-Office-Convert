package stirling.software.officeconvert.topdf.flat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.SecureXml;

/** A Flat OPC document (the single-file XML that Word, Excel and PowerPoint 2007 and later save, pkg:package): its
 * parts unpacked into the zip package they describe. */
public final class FlatOpc {

    static final String NS = "http://schemas.microsoft.com/office/2006/xmlPackage";

    public static final long MAX_BYTES = 256L << 20;

    private static final int MAX_PARTS = 10_000;

    private FlatOpc() {}

    public static boolean is(Path file) {
        return Sniff.root(file, NS, "package");
    }

    public static void unpack(Path source, OutputStream out) throws IOException {
        if (Files.size(source) > MAX_BYTES) {
            throw new IOException("The document is too large: a flat XML document over " + (MAX_BYTES >> 20) + " MB");
        }
        Document doc;
        try (InputStream in = Files.newInputStream(source)) {
            doc = SecureXml.parse(in);
        }
        Map<String, byte[]> parts = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (Node n = doc.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element part) || !NS.equals(part.getNamespaceURI()) || !"part".equals(part.getLocalName())) {
                continue;
            }
            if (parts.size() >= MAX_PARTS) {
                break;
            }
            String name = part.getAttributeNS(NS, "name");
            if (name.isEmpty() || !name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                continue;
            }
            byte[] data = data(part);
            if (data == null) {
                continue;
            }
            parts.put(name.substring(1), data);
            String type = part.getAttributeNS(NS, "contentType");
            if (!type.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(".rels")) {
                types.put(name, type);
            }
        }
        if (parts.isEmpty()) {
            throw new IOException("The flat XML document holds no parts");
        }
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas."
                + "openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/"
                + "vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/"
                + "xml\"/>");
        types.forEach((name, type) -> ct.append("<Override PartName=\"").append(escape(name)).append("\" ContentType=\"")
                .append(escape(type)).append("\"/>"));
        ct.append("</Types>");
        ZipOutputStream zip = new ZipOutputStream(new java.io.FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        });
        zip.setLevel(Deflater.BEST_SPEED);
        zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
        zip.write(ct.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            zip.putNextEntry(new ZipEntry(e.getKey()));
            zip.write(e.getValue());
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    private static byte[] data(Element part) {
        for (Node n = part.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !NS.equals(e.getNamespaceURI())) {
                continue;
            }
            if ("xmlData".equals(e.getLocalName())) {
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element root) {
                        return DomWriter.write(root).getBytes(StandardCharsets.UTF_8);
                    }
                }
                return null;
            }
            if ("binaryData".equals(e.getLocalName())) {
                try {
                    byte[] raw = Base64.getMimeDecoder().decode(e.getTextContent());
                    return "DeflateCompression".equals(part.getAttributeNS(NS, "compression")) ? inflate(raw) : raw;
                } catch (IllegalArgumentException | IOException x) {
                    return null;
                }
            }
        }
        return null;
    }

    private static byte[] inflate(byte[] raw) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(raw),
                inflater)) {
            byte[] out = in.readNBytes((int) MAX_BYTES);
            return out;
        } finally {
            inflater.end();
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
