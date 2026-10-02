package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** An Excel binary workbook (.xlsb, [MS-XLSB]) rewritten as the SpreadsheetML package the XLSX renderer draws: its
 * binary workbook, sheets, styles, strings and tables become XML; drawings, charts, pictures and themes, already XML
 * or media, are copied. Cached formula results only; macros, external links, pivot caches, comments and query
 * definitions are left out. */
public final class XlsbPackage {

    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    private static final String CT = "application/vnd.openxmlformats-officedocument.spreadsheetml.";

    private static final String BINARY = "application/vnd.ms-excel.";

    private enum Kind {
        WORKBOOK(CT + "sheet.main+xml"),
        WORKSHEET(CT + "worksheet+xml"),
        CHARTSHEET(CT + "chartsheet+xml"),
        STYLES(CT + "styles+xml"),
        STRINGS(CT + "sharedStrings+xml"),
        TABLE(CT + "table+xml"),
        COPY(null),
        DROP(null);

        final String type;

        Kind(String type) {
            this.type = type;
        }
    }

    private static final int MAX_TYPES_BYTES = 1 << 20;

    private XlsbPackage() {}

    /** Whether the zip package's main part is a binary workbook. */
    public static boolean is(Path file) {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = in.readNBytes(2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            java.util.zip.ZipEntry types = zip.getEntry("[Content_Types].xml");
            if (types == null || types.getSize() > MAX_TYPES_BYTES) {
                return false;
            }
            try (InputStream in = zip.getInputStream(types)) {
                String xml = new String(in.readNBytes(MAX_TYPES_BYTES), java.nio.charset.StandardCharsets.UTF_8);
                return xml.toLowerCase(Locale.ROOT).contains("sheet.binary.macroenabled.main");
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 24;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(Path source, OutputStream out) throws IOException {
        try (OfficeZip zip = OfficeZip.open(source)) {
            return new XlsbPackage.Writer(zip, new Parts(out)).write();
        }
    }

    private static final class Writer {

        private final OfficeZip zip;

        private final Parts parts;

        private final Map<String, Kind> kinds = new LinkedHashMap<>();

        private final Map<String, String> names = new LinkedHashMap<>();

        private final List<String> warnings = new ArrayList<>();

        private final Set<String> dropped = new LinkedHashSet<>();

        private boolean lost;

        Writer(OfficeZip zip, Parts parts) {
            this.zip = zip;
            this.parts = parts;
        }

        Outcome write() throws IOException {
            String main = zip.mainPart();
            for (String part : zip.partNames()) {
                if (part.equalsIgnoreCase(OfficeZip.CONTENT_TYPES) || part.toLowerCase(Locale.ROOT).endsWith(".rels")) {
                    continue;
                }
                Kind k = part.equals(main) ? Kind.WORKBOOK : kind(part, zip.contentType(part));
                kinds.put(part, k);
                names.put(part, k == Kind.COPY || k == Kind.DROP ? part : xmlName(part));
                if (k == Kind.DROP) {
                    dropped.add(part);
                }
            }
            Styles styles = Styles.read(open(zip.relationships(main).first("styles")));
            Workbook book = Workbook.read(zip.open(main));
            Set<String> written = new LinkedHashSet<>();
            for (Map.Entry<String, Kind> e : kinds.entrySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("Conversion interrupted");
                }
                String part = e.getKey();
                String name = names.get(part).substring(1);
                switch (e.getValue()) {
                    case WORKSHEET -> worksheet(part, name, styles);
                    case CHARTSHEET -> parts.put(name, Tables.chartSheet(zip.open(part)));
                    case STYLES -> parts.put(name, styles.xml());
                    case STRINGS -> RichStrings.write(zip.open(part), styles, parts, name);
                    case TABLE -> table(part, name);
                    case COPY -> copy(part, name);
                    default -> {
                        continue;
                    }
                }
                written.add(part);
            }
            Set<String> sheetIds = sheetIds(main);
            parts.put(names.get(main).substring(1), book.xml(sheetIds));
            written.add(main);
            if (book.sheets.size() > sheetIds.size()) {
                warnings.add("Left out " + (book.sheets.size() - sheetIds.size()) + " macro or dialog sheets");
                lost = true;
            }
            for (String source : relationshipSources(written)) {
                String rels = relationships(source);
                if (rels != null) {
                    String target = source.equals("/") ? OfficeZip.PACKAGE_RELS
                            : OfficeZip.relsPartFor(names.get(source));
                    parts.put(target.substring(1), rels);
                }
            }
            parts.put(OfficeZip.CONTENT_TYPES.substring(1), contentTypes(written));
            parts.finish();
            if (dropped.stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).contains("/pivottables/"))) {
                warnings.add("Pivot table styles were left out; their values are kept");
            }
            return new Outcome(warnings, lost);
        }

        private InputStream open(Relationship r) throws IOException {
            return r == null || r.part() == null || !zip.exists(r.part()) ? null : zip.open(r.part());
        }

        private static Kind kind(String part, String type) {
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            String p = part.toLowerCase(Locale.ROOT);
            if (t.startsWith(BINARY + "worksheet")) {
                return Kind.WORKSHEET;
            }
            if (t.startsWith(BINARY + "chartsheet")) {
                return Kind.CHARTSHEET;
            }
            if (t.startsWith(BINARY + "styles")) {
                return Kind.STYLES;
            }
            if (t.startsWith(BINARY + "sharedstrings")) {
                return Kind.STRINGS;
            }
            if (t.startsWith(BINARY + "table")) {
                return Kind.TABLE;
            }
            if (p.endsWith(".bin") && !t.contains("printersettings")) {
                return Kind.DROP;
            }
            if (t.contains("vbaproject") || t.contains("activex") || p.contains("/externallinks/")) {
                return Kind.DROP;
            }
            return Kind.COPY;
        }

        private String xmlName(String part) {
            String base = part.toLowerCase(Locale.ROOT).endsWith(".bin") ? part.substring(0, part.length() - 4) : part;
            String name = base + ".xml";
            for (int i = 2; zip.exists(name) || names.containsValue(name); i++) {
                name = base + "-" + i + ".xml";
            }
            return name;
        }

        private void worksheet(String part, String name, Styles styles) throws IOException {
            Sheet sheet;
            try (Parts.Part p = parts.open(name); InputStream in = zip.open(part)) {
                sheet = new Sheet(styles, p);
                sheet.write(in);
            }
            if (sheet.truncated) {
                lost = true;
                warnings.add("Sheet " + part.substring(part.lastIndexOf('/') + 1) + " is too large; only its first rows"
                        + " were converted");
            }
            if (sheet.skippedRules() > 0) {
                warnings.add("Left out " + sheet.skippedRules() + " conditional formatting rules that depend on"
                        + " formulas");
            }
            if (sheet.printsComments && hasComments(part)) {
                warnings.add("Comments set to print were left out");
                lost = true;
            }
        }

        private boolean hasComments(String part) throws IOException {
            for (Relationship r : zip.relationships(part).all()) {
                if (r.typeName().equals("comments")) {
                    return true;
                }
            }
            return false;
        }

        private void table(String part, String name) throws IOException {
            String xml;
            try (InputStream in = zip.open(part)) {
                xml = Tables.xml(in);
            }
            if (xml == null) {
                dropped.add(part);
                return;
            }
            parts.put(name, xml);
        }

        private void copy(String part, String name) throws IOException {
            try (InputStream in = zip.open(part)) {
                parts.put(name, in);
            }
        }

        private Set<String> sheetIds(String main) throws IOException {
            Set<String> ids = new LinkedHashSet<>();
            for (Relationship r : zip.relationships(main).all()) {
                Kind k = r.part() == null ? null : kinds.get(r.part());
                if (k == Kind.WORKSHEET || k == Kind.CHARTSHEET) {
                    ids.add(r.id());
                }
            }
            return ids;
        }

        private List<String> relationshipSources(Set<String> written) {
            List<String> out = new ArrayList<>();
            out.add("/");
            out.addAll(written);
            return out;
        }

        private String relationships(String source) throws IOException {
            List<Relationship> all;
            try {
                all = zip.relationships(source).all();
            } catch (IOException e) {
                return null;
            }
            if (all.isEmpty()) {
                return null;
            }
            StringBuilder b = new StringBuilder(Xml.HEAD).append("<Relationships xmlns=\"").append(Xml.PKG_REL)
                    .append("\">");
            for (Relationship r : all) {
                String target;
                String mode = "";
                if (r.external() || r.part() == null) {
                    target = r.target();
                    mode = " TargetMode=\"External\"";
                } else {
                    Kind k = kinds.get(r.part());
                    if (k == null || dropped.contains(r.part())) {
                        continue;
                    }
                    target = names.get(r.part());
                }
                b.append("<Relationship Id=\"").append(Xml.attr(r.id())).append("\" Type=\"").append(Xml.attr(r.type()))
                        .append("\" Target=\"").append(Xml.attr(target)).append('"').append(mode).append("/>");
            }
            return b.append("</Relationships>").toString();
        }

        private String contentTypes(Set<String> written) {
            StringBuilder b = new StringBuilder(Xml.HEAD).append("<Types xmlns=\"")
                    .append("http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"")
                    .append("application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
            for (String part : written) {
                Kind k = kinds.get(part);
                String type = k.type != null ? k.type : zip.contentType(part);
                if (type == null || dropped.contains(part)) {
                    continue;
                }
                b.append("<Override PartName=\"").append(Xml.attr(names.get(part))).append("\" ContentType=\"")
                        .append(Xml.attr(type)).append("\"/>");
            }
            return b.append("</Types>").toString();
        }
    }
}
