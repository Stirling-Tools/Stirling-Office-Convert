package stirling.software.officeconvert.topdf.ofd;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.BoundedZip;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

final class OfdPackage implements Closeable {

    static final long MAX_XML_BYTES = 64L << 20;

    static final long MAX_MEDIA_BYTES = 64L << 20;

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private final BoundedZip zip;

    private final Map<String, ZipEntry> entries = new HashMap<>();

    private final Map<String, ZipEntry> folded = new HashMap<>();

    private OfdPackage(BoundedZip zip) {
        this.zip = zip;
        for (ZipEntry e : zip.entries()) {
            if (e.isDirectory()) {
                continue;
            }
            String n = BoundedZip.normalised(e.getName());
            entries.putIfAbsent(n, e);
            folded.putIfAbsent(n.toLowerCase(Locale.ROOT), e);
        }
    }

    static OfdPackage open(Path file) throws IOException {
        return new OfdPackage(BoundedZip.open(file, OfficeZip.Limits.DEFAULT));
    }

    static boolean is(Path file) {
        try {
            byte[] head = SourceFile.head(file, 2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try (BoundedZip z = BoundedZip.open(file)) {
            ZipEntry root = z.entry("OFD.xml");
            if (root == null) {
                for (ZipEntry e : z.entries()) {
                    if (BoundedZip.normalised(e.getName()).equalsIgnoreCase("OFD.xml")) {
                        root = e;
                        break;
                    }
                }
            }
            if (root == null || root.isDirectory()) {
                return false;
            }
            String head;
            try (InputStream in = z.open(root, MAX_XML_BYTES)) {
                head = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            }
            return head.contains("OFD") && (head.contains("ofdspec") || head.contains("DocBody"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String resolve(String base, String loc) {
        if (loc == null) {
            return null;
        }
        String l = loc.trim().replace('\\', '/');
        if (l.isEmpty() || SCHEME.matcher(l).find()) {
            return null;
        }
        String joined;
        if (l.startsWith("/")) {
            joined = l;
        } else {
            int slash = base == null ? -1 : base.lastIndexOf('/');
            joined = (slash < 0 ? "" : base.substring(0, slash + 1)) + l;
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String p : joined.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.addLast(p);
        }
        return String.join("/", parts);
    }

    static String dir(String part) {
        int slash = part.lastIndexOf('/');
        return slash < 0 ? "" : part.substring(0, slash + 1);
    }

    private ZipEntry entry(String part) {
        if (part == null) {
            return null;
        }
        ZipEntry e = entries.get(part);
        return e != null ? e : folded.get(part.toLowerCase(Locale.ROOT));
    }

    boolean exists(String part) {
        return entry(part) != null;
    }

    byte[] read(String part, long max) throws IOException {
        ZipEntry e = entry(part);
        if (e == null) {
            throw new IOException("The OFD package has no part " + part);
        }
        return zip.read(e, max);
    }

    Element xml(String part) throws IOException {
        Document doc = SecureXml.parse(new ByteArrayInputStream(read(part, MAX_XML_BYTES)));
        return doc.getDocumentElement();
    }

    String name(String part) {
        ZipEntry e = entry(part);
        return e == null ? part : BoundedZip.normalised(e.getName());
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
