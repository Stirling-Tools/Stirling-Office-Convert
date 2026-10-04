package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.poi.ooxml.POIXMLDocument;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipEntrySource;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

public final class PoiPackages {

    private PoiPackages() {}

    public static OPCPackage open(OfficeZip zip) throws IOException {
        Objects.requireNonNull(zip, "zip");
        PoiLimits.apply();
        PoiXml.install();
        try {
            Map<String, byte[]> repairs = SlideLinks.repairs(zip);
            Set<String> hidden = repairs.isEmpty() ? UndrawnParts.of(zip) : Set.of();
            if (!hidden.isEmpty()) {
                repairs = UndrawnParts.unlinked(zip, hidden);
            }
            return OPCPackage.open(new Parts(zip, repairs, hidden));
        } catch (InvalidFormatException | RuntimeException e) {
            throw damagedOr(zip, e);
        }
    }

    public static XSSFWorkbook workbook(OfficeZip zip) throws IOException {
        return load(zip, "spreadsheetml", "workbook", XSSFWorkbook::new);
    }

    public static XMLSlideShow slideShow(OfficeZip zip) throws IOException {
        return load(zip, "presentationml", "presentation", XMLSlideShow::new);
    }

    public static XWPFDocument document(OfficeZip zip) throws IOException {
        return load(zip, "wordprocessingml", "Word document", XWPFDocument::new);
    }

    @FunctionalInterface
    private interface Loader<T> {
        T load(OPCPackage pkg) throws IOException;
    }

    private static <T extends POIXMLDocument> T load(OfficeZip zip, String kind, String what,
            Loader<T> make) throws IOException {
        String type = zip.mainContentType();
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String ms = kind.equals("spreadsheetml") ? "application/vnd.ms-excel."
                : kind.equals("presentationml") ? "application/vnd.ms-powerpoint." : "application/vnd.ms-word.";
        if (!t.contains(kind) && !t.startsWith(ms)) {
            throw new IOException("The package is not a " + what + " (its main part is " + type + ")");
        }
        OPCPackage pkg = open(zip);
        try {
            return make.load(pkg);
        } catch (IOException | RuntimeException | StackOverflowError e) {
            pkg.revert();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            throw damagedOr(zip, e);
        }
    }

    // POI does not say which part it choked on: name the first XML part that is not well-formed, if any
    private static IOException damagedOr(OfficeZip zip, Throwable e) throws InterruptedIOException {
        if (!(e instanceof StackOverflowError)) {
            for (String part : zip.partNames()) {
                String lower = part.toLowerCase(Locale.ROOT);
                String type = zip.contentType(part);
                if (!lower.endsWith(".xml") && !lower.endsWith(".rels") && (type == null || !type.contains("xml"))) {
                    continue;
                }
                try {
                    zip.xml(part);
                } catch (OfficeZip.DamagedPart d) {
                    d.addSuppressed(e);
                    return d;
                } catch (InterruptedIOException i) {
                    throw i;
                } catch (IOException ignored) {
                    // a refused DOCTYPE or a missing part: POI's own message says more
                }
            }
        }
        return failure(e);
    }

    // POI reads the package through OfficeZip and its limits; parts without a content type, left out or in Office's
    // [trash] folder are skipped, and repaired relationship parts replace the stored ones
    private static final class Parts implements ZipEntrySource {

        private final OfficeZip zip;

        private final Map<String, byte[]> repaired;

        private final Map<String, ZipArchiveEntry> entries = new LinkedHashMap<>();

        private final Map<String, ZipArchiveEntry> byName = new HashMap<>();

        private volatile boolean closed;

        Parts(OfficeZip zip, Map<String, byte[]> repaired, Set<String> hidden) {
            this.zip = zip;
            this.repaired = repaired;
            for (String part : repaired.keySet()) {
                if (!zip.exists(part) && zip.contentType(part) != null) {
                    ZipArchiveEntry e = new ZipArchiveEntry(part.substring(1));
                    e.setSize(repaired.get(part).length);
                    entries.put(e.getName(), e);
                    byName.putIfAbsent(e.getName().toLowerCase(Locale.ROOT), e);
                }
            }
            for (String part : zip.partNames()) {
                if (part.startsWith("/[trash]/") || hidden.contains(part.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!part.equalsIgnoreCase(OfficeZip.CONTENT_TYPES) && zip.contentType(part) == null) {
                    zip.note("Left out the part " + part + ", which has no content type");
                    continue;
                }
                ZipArchiveEntry e = new ZipArchiveEntry(part.substring(1));
                byte[] fixed = repaired.get(part.toLowerCase(Locale.ROOT));
                e.setSize(fixed == null ? zip.size(part) : fixed.length);
                entries.put(e.getName(), e);
                byName.putIfAbsent(e.getName().toLowerCase(Locale.ROOT), e);
            }
        }

        @Override
        public Enumeration<? extends ZipArchiveEntry> getEntries() {
            return Collections.enumeration(entries.values());
        }

        @Override
        public ZipArchiveEntry getEntry(String path) {
            ZipArchiveEntry e = entries.get(path);
            return e != null || path == null ? e : byName.get(path.toLowerCase(Locale.ROOT));
        }

        @Override
        public InputStream getInputStream(ZipArchiveEntry entry) throws IOException {
            if (closed) {
                throw new IOException("The package is closed");
            }
            byte[] fixed = repaired.get("/" + entry.getName().toLowerCase(Locale.ROOT));
            return fixed != null ? new ByteArrayInputStream(fixed) : zip.open("/" + entry.getName());
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }

    private static String jdkLimit(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("JAXP000")) {
                int at = m.indexOf("jdk.xml.");
                if (at >= 0) {
                    int end = at;
                    while (end < m.length() && (Character.isLetterOrDigit(m.charAt(end)) || m.charAt(end) == '.')) {
                        end++;
                    }
                    while (m.charAt(end - 1) == '.') {
                        end--;
                    }
                    return m.substring(at, end);
                }
            }
        }
        return null;
    }

    private static IOException failure(Throwable e) {
        if (e instanceof UncheckedIOException u) {
            return u.getCause();
        }
        String limit = jdkLimit(e);
        if (limit != null) {
            return new IOException("The document exceeds the JVM's XML limit " + limit + ", which POI applies to slides"
                    + " and relationship parts; start the JVM with -D" + limit + "=... or call"
                    + " PoiXml.raiseProcessLimits() once at startup", e);
        }
        if (e instanceof IOException io) {
            return io;
        }
        return new IOException("The package cannot be read: " + reason(e), e);
    }

    // The innermost message, without the class names and line numbers the parsers put in front of it
    static String reason(Throwable e) {
        Throwable root = e;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 16) {
            root = root.getCause();
        }
        String m = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        m = m.replaceAll("(?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error)(?:: |; )", "")
                .replaceAll("lineNumber: \\d+; columnNumber: \\d+; ", "").strip();
        return m.isEmpty() ? root.getClass().getSimpleName() : m;
    }
}
