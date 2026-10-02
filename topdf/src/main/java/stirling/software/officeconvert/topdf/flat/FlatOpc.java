package stirling.software.officeconvert.topdf.flat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

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
        if (SourceFile.size(source) > MAX_BYTES) {
            throw new IOException("The document is too large: a flat XML document over " + (MAX_BYTES >> 20) + " MB");
        }
        Document doc;
        try (InputStream in = SourceFile.open(source)) {
            doc = SecureXml.parse(in);
        }
        Map<String, Element> parts = new LinkedHashMap<>();
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
            parts.put(name, part);
        }
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
        long[] budget = {OfficeZip.Limits.DEFAULT.maxTotalBytes()};
        int written = 0;
        for (Map.Entry<String, Element> e : parts.entrySet()) {
            OfficeZip.checkNotInterrupted();
            String name = e.getKey();
            Part data = data(e.getValue(), budget);
            if (data == null) {
                continue;
            }
            zip.putNextEntry(new ZipEntry(name.substring(1)));
            data.writeTo(zip);
            zip.closeEntry();
            written++;
            String type = e.getValue().getAttributeNS(NS, "contentType");
            if (!type.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(".rels")) {
                types.put(name, type);
            }
        }
        if (written == 0) {
            throw new IOException("The flat XML document holds no parts");
        }
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas."
                + "openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/"
                + "vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/"
                + "xml\"/>");
        types.forEach((name, type) -> ct.append("<Override PartName=\"").append(escape(name)).append("\" ContentType=\"")
                .append(escape(type)).append("\"/>"));
        ct.append("</Types>");
        zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
        zip.write(ct.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    private record Part(byte[] bytes, boolean deflated) {

        void writeTo(OutputStream out) throws IOException {
            if (!deflated) {
                out.write(bytes);
                return;
            }
            Inflated.copy(bytes, out, Long.MAX_VALUE);
        }
    }

    private static Part data(Element part, long[] budget) throws IOException {
        for (Node n = part.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !NS.equals(e.getNamespaceURI())) {
                continue;
            }
            if ("xmlData".equals(e.getLocalName())) {
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element root) {
                        return charge(new Part(DomWriter.write(root).getBytes(StandardCharsets.UTF_8), false),
                                budget, -1);
                    }
                }
                return null;
            }
            if ("binaryData".equals(e.getLocalName())) {
                byte[] raw;
                try {
                    raw = Base64.getMimeDecoder().decode(e.getTextContent());
                } catch (IllegalArgumentException x) {
                    return null;
                }
                if (!"DeflateCompression".equals(part.getAttributeNS(NS, "compression"))) {
                    return charge(new Part(raw, false), budget, -1);
                }
                long size;
                try {
                    size = Inflated.copy(raw, OutputStream.nullOutputStream(), Math.min(MAX_BYTES, budget[0]) + 1);
                } catch (Inflated.TooLarge x) {
                    throw tooLarge(part, budget);
                } catch (IOException x) {
                    if (x instanceof java.io.InterruptedIOException i) {
                        throw i;
                    }
                    return null;
                }
                return charge(new Part(raw, true), budget, size);
            }
        }
        return null;
    }

    private static Part charge(Part p, long[] budget, long inflated) throws IOException {
        long size = inflated >= 0 ? inflated : p.bytes().length;
        if (size > budget[0]) {
            throw new OfficeZip.Oversized("The document is too large: over "
                    + (OfficeZip.Limits.DEFAULT.maxTotalBytes() >> 20) + " MB uncompressed");
        }
        budget[0] -= size;
        return p;
    }

    private static OfficeZip.Oversized tooLarge(Element part, long[] budget) {
        String name = part.getAttributeNS(NS, "name");
        return budget[0] < MAX_BYTES
                ? new OfficeZip.Oversized("The document is too large: over "
                        + (OfficeZip.Limits.DEFAULT.maxTotalBytes() >> 20) + " MB uncompressed")
                : new OfficeZip.Oversized("The document is too large: the part " + name + " is over "
                        + (MAX_BYTES >> 20) + " MB");
    }

    private static final class Inflated {

        static final class TooLarge extends IOException {
            TooLarge() {
                super("inflated past its limit");
            }
        }

        static long copy(byte[] raw, OutputStream out, long max) throws IOException {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
            try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                    new ByteArrayInputStream(raw), inflater)) {
                byte[] buf = new byte[1 << 16];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0;) {
                    total += n;
                    if (total > max) {
                        throw new TooLarge();
                    }
                    OfficeZip.checkNotInterrupted();
                    out.write(buf, 0, n);
                }
                return total;
            } finally {
                inflater.end();
            }
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
