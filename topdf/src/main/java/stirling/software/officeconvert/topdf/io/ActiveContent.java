package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.topdf.pdf.SafeLinks;

public final class ActiveContent {

    public enum Kind {
        MACRO,
        ACTIVE_X,
        OLE_OBJECT,
        EMBEDDED_PACKAGE,
        DATA_CONNECTION,
        QUERY_TABLE,
        EXTERNAL_WORKBOOK,
        WEB_EXTENSION,
        ALT_CHUNK,
        ATTACHED_TEMPLATE,
        FRAME,
        SUBDOCUMENT,
        CUSTOM_UI,
        MEDIA,
        MAIL_MERGE,
        EXTERNAL_TARGET
    }

    public static final Set<String> COMPUTED_FIELDS = Set.of("PAGE", "NUMPAGES", "SECTIONPAGES");

    private static final Map<String, Kind> RELATIONSHIPS = new HashMap<>();

    private static final Map<String, Kind> CONTENT_TYPES = new HashMap<>();

    static {
        relationship(Kind.MACRO, "vbaProject", "vbaProjectSignature", "vbaProjectSignatureAgile",
                "vbaProjectSignatureV3", "wordVbaData", "keyMapCustomizations", "attachedToolbars", "xlMacrosheet",
                "xlIntlMacrosheet", "macrosheet", "intlMacrosheet");
        relationship(Kind.ACTIVE_X, "control", "activeXControlBinary", "activeXControl");
        relationship(Kind.OLE_OBJECT, "oleObject");
        relationship(Kind.EMBEDDED_PACKAGE, "package");
        relationship(Kind.DATA_CONNECTION, "connections", "volatileDependencies", "xmlMaps", "powerPivotData",
                "slideUpdateInfo", "slideUpdateUrl", "externalLinkPath", "externalLinkLongPath", "xlExternalLinkPath",
                "xlPathMissing", "xlStartup", "xlAlternateStartup", "xlLibrary", "customData");
        relationship(Kind.QUERY_TABLE, "queryTable");
        relationship(Kind.EXTERNAL_WORKBOOK, "externalLink");
        relationship(Kind.WEB_EXTENSION, "webextension", "webextensiontaskpanes", "taskpanes");
        relationship(Kind.ALT_CHUNK, "aFChunk");
        relationship(Kind.ATTACHED_TEMPLATE, "attachedTemplate");
        relationship(Kind.FRAME, "frame");
        relationship(Kind.SUBDOCUMENT, "subDocument");
        relationship(Kind.CUSTOM_UI, "extensibility", "userCustomization", "customUI");
        relationship(Kind.MEDIA, "media", "video", "audio");
        relationship(Kind.MAIL_MERGE, "mailMergeSource", "mailMergeHeaderSource", "recipientData",
                "mailMergeRecipientData");

        contentType(Kind.MACRO, "application/vnd.ms-office.vbaproject", "application/vnd.ms-office.vbaprojectsignature",
                "application/vnd.ms-office.vbaprojectsignatureagile", "application/vnd.ms-office.vbaprojectsignaturev3",
                "application/vnd.ms-word.vbadata+xml", "application/vnd.ms-word.keymapcustomizations+xml",
                "application/vnd.ms-word.attachedtoolbars", "application/vnd.ms-excel.macrosheet+xml",
                "application/vnd.ms-excel.intlmacrosheet+xml");
        contentType(Kind.ACTIVE_X, "application/vnd.ms-office.activex+xml", "application/vnd.ms-office.activex");
        contentType(Kind.OLE_OBJECT, "application/vnd.openxmlformats-officedocument.oleobject",
                "application/vnd.ms-office.oleobject");
        contentType(Kind.DATA_CONNECTION, "application/vnd.openxmlformats-officedocument.spreadsheetml.connections+xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.volatiledependencies+xml",
                "application/vnd.openxmlformats-officedocument.presentationml.slideupdateinfo+xml",
                "application/vnd.ms-excel.datamodel", "application/vnd.ms-office.datamashup");
        contentType(Kind.QUERY_TABLE, "application/vnd.openxmlformats-officedocument.spreadsheetml.querytable+xml");
        contentType(Kind.EXTERNAL_WORKBOOK,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.externallink+xml");
        contentType(Kind.WEB_EXTENSION, "application/vnd.ms-office.webextension+xml",
                "application/vnd.ms-office.webextensiontaskpanes+xml");
        contentType(Kind.ALT_CHUNK, "application/xhtml+xml", "text/html", "message/rfc822", "application/rtf",
                "text/rtf");
        contentType(Kind.MAIL_MERGE, "application/vnd.openxmlformats-officedocument.wordprocessingml.mailmergerecipientdata+xml");
    }

    private ActiveContent() {}

    public static Kind of(Relationship r) {
        if (r == null) {
            return null;
        }
        Kind kind = ofType(r.type());
        if (kind != null) {
            return kind;
        }
        return r.external() && !isHyperlink(r) ? Kind.EXTERNAL_TARGET : null;
    }

    public static Kind ofType(String relationshipType) {
        if (relationshipType == null) {
            return null;
        }
        return RELATIONSHIPS.get(Relationship.typeName(relationshipType.strip()).toLowerCase(Locale.ROOT));
    }

    public static Kind ofPart(String partName, String contentType) {
        if (contentType != null) {
            String type = contentType.strip().toLowerCase(Locale.ROOT);
            int semi = type.indexOf(';');
            if (semi >= 0) {
                type = type.substring(0, semi).strip();
            }
            Kind kind = CONTENT_TYPES.get(type);
            if (kind != null) {
                return kind;
            }
            if (type.startsWith("video/") || type.startsWith("audio/")) {
                return Kind.MEDIA;
            }
        }
        if (partName == null) {
            return null;
        }
        String name = partName.replace('\\', '/').toLowerCase(Locale.ROOT);
        String file = name.substring(name.lastIndexOf('/') + 1);
        if (file.startsWith("vbaproject") || file.startsWith("vbadata") || file.endsWith(".bas")) {
            return Kind.MACRO;
        }
        if (name.contains("/activex/")) {
            return Kind.ACTIVE_X;
        }
        if (name.contains("/customui/")) {
            return Kind.CUSTOM_UI;
        }
        if (name.contains("/webextensions/")) {
            return Kind.WEB_EXTENSION;
        }
        if (name.contains("/externallinks/")) {
            return Kind.EXTERNAL_WORKBOOK;
        }
        if (name.contains("/querytables/") || file.equals("connections.xml")) {
            return name.contains("/querytables/") ? Kind.QUERY_TABLE : Kind.DATA_CONNECTION;
        }
        if (file.startsWith("oleobject") && file.endsWith(".bin")) {
            return Kind.OLE_OBJECT;
        }
        return null;
    }

    public static Kind ofPart(OfficeZip zip, String partName) {
        return ofPart(partName, zip.contentType(partName));
    }

    public static Map<Kind, Set<String>> scan(OfficeZip zip) {
        Map<Kind, Set<String>> found = new EnumMap<>(Kind.class);
        for (String name : zip.partNames()) {
            String part = OfficeZip.canonical(name);
            String source = sourceOf(part);
            if (source == null) {
                Kind kind = ofPart(zip, part);
                if (kind != null && (kind != Kind.EMBEDDED_PACKAGE || !part.contains("/charts/"))) {
                    found.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(part);
                }
                continue;
            }
            Relationships rels;
            try {
                rels = zip.relationships(source);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            for (Relationship r : rels.all()) {
                Kind kind = of(r);
                if (kind == null || kind == Kind.EMBEDDED_PACKAGE && source.contains("/charts/")) {
                    continue;
                }
                String what = r.part() != null ? r.part() : clip(r.target());
                found.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(what);
            }
        }
        return found;
    }

    public static List<String> describe(Map<Kind, Set<String>> found) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Kind, Set<String>> e : found.entrySet()) {
            List<String> items = new ArrayList<>(e.getValue());
            String shown = String.join(", ", items.subList(0, Math.min(3, items.size())));
            String more = items.size() > 3 ? " and " + (items.size() - 3) + " more" : "";
            out.add("Skipped active content: " + label(e.getKey()) + " (" + shown + more + ")");
        }
        return out;
    }

    static String label(Kind kind) {
        return switch (kind) {
            case MACRO -> "macros, never run";
            case ACTIVE_X -> "ActiveX controls, stored picture only";
            case OLE_OBJECT -> "embedded OLE objects, stored preview only";
            case EMBEDDED_PACKAGE -> "embedded files, never opened";
            case DATA_CONNECTION -> "data connections, never refreshed";
            case QUERY_TABLE -> "query tables, never refreshed";
            case EXTERNAL_WORKBOOK -> "links to other workbooks, cached values only";
            case WEB_EXTENSION -> "web add-ins, never loaded";
            case ALT_CHUNK -> "imported HTML or RTF chunks, left out";
            case ATTACHED_TEMPLATE -> "attached template, never loaded";
            case FRAME -> "frames, never loaded";
            case SUBDOCUMENT -> "subdocuments, never loaded";
            case CUSTOM_UI -> "custom ribbon UI, ignored";
            case MEDIA -> "audio and video, poster frame only";
            case MAIL_MERGE -> "mail merge data sources, never read";
            case EXTERNAL_TARGET -> "linked files and pictures, never fetched";
        };
    }

    static String sourceOf(String relsPart) {
        int at = relsPart.lastIndexOf("/_rels/");
        if (at < 0 || !relsPart.endsWith(".rels")) {
            return null;
        }
        String file = relsPart.substring(at + "/_rels/".length(), relsPart.length() - ".rels".length());
        return file.isEmpty() ? "/" : relsPart.substring(0, at) + "/" + file;
    }

    private static String clip(String s) {
        if (s == null) {
            return "(no target)";
        }
        String t = s.strip();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    public static boolean mayFollow(Relationship r) {
        return r != null && !r.external() && r.part() != null && of(r) == null;
    }

    public static boolean isHyperlink(Relationship r) {
        return r != null && "hyperlink".equalsIgnoreCase(r.typeName());
    }

    public static String hyperlink(Relationship r) {
        return isHyperlink(r) && r.external() ? SafeLinks.safeUrl(r.target()) : null;
    }

    public static String fieldName(String instruction) {
        if (instruction == null) {
            return "";
        }
        String s = instruction.strip();
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '\\'
                && s.charAt(end) != '"') {
            end++;
        }
        return s.substring(0, end).toUpperCase(Locale.ROOT);
    }

    public static boolean computedField(String instruction) {
        return COMPUTED_FIELDS.contains(fieldName(instruction));
    }

    private static void relationship(Kind kind, String... typeNames) {
        for (String t : typeNames) {
            RELATIONSHIPS.put(t.toLowerCase(Locale.ROOT), kind);
        }
    }

    private static void contentType(Kind kind, String... types) {
        for (String t : types) {
            CONTENT_TYPES.put(t.toLowerCase(Locale.ROOT), kind);
        }
    }
}
