package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.field.DatePicture;
import stirling.software.officeconvert.topdf.field.FieldCase;
import stirling.software.officeconvert.topdf.field.StoredFields;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;

final class FieldValues {

    static final FieldValues NONE = new FieldValues(Map.of(), Map.of(), Map.of(), null);

    private static final int MAX_PROPERTIES = 1000;

    private final Map<String, String> core;

    private final Map<String, String> app;

    private final Map<String, String> custom;

    private final String fileName;

    private FieldValues(Map<String, String> core, Map<String, String> app, Map<String, String> custom,
            String fileName) {
        this.core = core;
        this.app = app;
        this.custom = custom;
        this.fileName = fileName;
    }

    static FieldValues read(OfficeZip zip, String fileName) throws IOException {
        Map<String, String> core = new HashMap<>();
        Map<String, String> app = new HashMap<>();
        Map<String, String> custom = new HashMap<>();
        children(zip, "core-properties", core);
        children(zip, "extended-properties", app);
        Element props = root(zip, "custom-properties");
        int n = 0;
        for (Node p = props == null ? null : props.getFirstChild(); p != null && n < MAX_PROPERTIES;
                p = p.getNextSibling()) {
            if (p instanceof Element e && "property".equals(e.getLocalName()) && e.hasAttribute("name")) {
                custom.putIfAbsent(e.getAttribute("name").toLowerCase(Locale.ROOT), e.getTextContent());
                n++;
            }
        }
        String name = fileName;
        if (name != null) {
            name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        }
        return new FieldValues(core, app, custom, name);
    }

    private static void children(OfficeZip zip, String type, Map<String, String> into) throws IOException {
        Element root = root(zip, type);
        int n = 0;
        for (Node c = root == null ? null : root.getFirstChild(); c != null && n++ < MAX_PROPERTIES;
                c = c.getNextSibling()) {
            if (c instanceof Element e && e.getLocalName() != null) {
                into.putIfAbsent(e.getLocalName(), e.getTextContent());
            }
        }
    }

    private static Element root(OfficeZip zip, String type) throws IOException {
        Relationship r = zip.packageRelationships().first(type);
        if (r == null || r.part() == null || !zip.exists(r.part())) {
            return null;
        }
        try {
            return zip.xml(r.part()).getDocumentElement();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    String text(List<String> args) {
        if (args.isEmpty()) {
            return null;
        }
        String kind = args.get(0).toUpperCase(Locale.ROOT);
        if (!StoredFields.stored(kind)) {
            return null;
        }
        if (DatePicture.dated(kind)) {
            int[] t = switch (kind) {
                case "CREATEDATE" -> time(core.get("created"));
                case "PRINTDATE" -> time(core.get("lastPrinted"));
                default -> {
                    int[] saved = time(core.get("modified"));
                    yield saved != null ? saved : time(core.get("created"));
                }
            };
            String date = DatePicture.text(kind, args, t);
            return date == null ? null : FieldCase.apply(date, args);
        }
        String value = switch (kind) {
            case "AUTHOR" -> core.get("creator");
            case "TITLE" -> core.get("title");
            case "SUBJECT" -> core.get("subject");
            case "KEYWORDS" -> core.get("keywords");
            case "COMMENTS" -> core.get("description");
            case "LASTSAVEDBY" -> core.get("lastModifiedBy");
            case "REVNUM" -> core.get("revision");
            case "TEMPLATE" -> app.get("Template");
            case "NUMWORDS" -> app.get("Words");
            case "NUMCHARS" -> app.get("Characters");
            case "EDITTIME" -> app.get("TotalTime");
            case "FILENAME" -> fileName;
            case "DOCPROPERTY" -> args.size() > 1 ? property(args.get(1)) : null;
            default -> null;
        };
        value = DocumentInfo.clean(value);
        return value == null ? null : FieldCase.apply(value, args);
    }

    private String property(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String builtin = switch (key) {
            case "title" -> core.get("title");
            case "subject" -> core.get("subject");
            case "author" -> core.get("creator");
            case "keywords" -> core.get("keywords");
            case "comments" -> core.get("description");
            case "category" -> core.get("category");
            case "lastsavedby" -> core.get("lastModifiedBy");
            case "revisionnumber" -> core.get("revision");
            case "template" -> app.get("Template");
            case "company" -> app.get("Company");
            case "manager" -> app.get("Manager");
            case "numberofwords" -> app.get("Words");
            case "numberofcharacters" -> app.get("Characters");
            case "totaleditingtime" -> app.get("TotalTime");
            default -> null;
        };
        return builtin != null ? builtin : custom.get(key);
    }

    static int[] time(String w3c) {
        if (w3c == null) {
            return null;
        }
        String s = w3c.strip();
        LocalDateTime t;
        try {
            t = OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                t = LocalDateTime.parse(s);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
        if (t.getYear() < 1 || t.getYear() > 9999) {
            return null;
        }
        return new int[] {t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), t.getMinute(),
            t.getSecond()};
    }
}
