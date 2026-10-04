package stirling.software.officeconvert.topdf.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class RawXlsx {

    static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final String REL = R + "/";

    static final String NS = "xmlns=\"" + MAIN + "\" xmlns:r=\"" + R + "\"";

    private final Map<String, String> parts = new LinkedHashMap<>();

    private final Map<String, List<String>> rels = new LinkedHashMap<>();

    private final List<String> overrides = new ArrayList<>();

    private final List<String> defaults = new ArrayList<>();

    private final Map<String, byte[]> binaries = new LinkedHashMap<>();

    private final List<String> sheets = new ArrayList<>();

    private String styles;

    private String sharedStrings;

    private String workbookExtra = "";

    RawXlsx sheet(String name, String xml) {
        int n = sheets.size() + 1;
        sheets.add("<sheet name=\"" + name + "\" sheetId=\"" + n + "\" r:id=\"rIdS" + n + "\"/>");
        part("xl/worksheets/sheet" + n + ".xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
                xml.startsWith("<worksheet") ? xml : "<worksheet " + NS + ">" + xml + "</worksheet>");
        rel("xl/workbook.xml", "rIdS" + n, "worksheet", "worksheets/sheet" + n + ".xml");
        return this;
    }

    RawXlsx chartsheet(String name, String xml) {
        int n = sheets.size() + 1;
        sheets.add("<sheet name=\"" + name + "\" sheetId=\"" + n + "\" r:id=\"rIdS" + n + "\"/>");
        part("xl/chartsheets/sheet" + n + ".xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.chartsheet+xml",
                xml);
        rel("xl/workbook.xml", "rIdS" + n, "chartsheet", "chartsheets/sheet" + n + ".xml");
        return this;
    }

    RawXlsx styles(String inner) {
        styles = "<styleSheet " + NS + ">" + inner + "</styleSheet>";
        return this;
    }

    RawXlsx sharedStrings(String... items) {
        StringBuilder b = new StringBuilder("<sst " + NS + " count=\"" + items.length + "\" uniqueCount=\""
                + items.length + "\">");
        for (String i : items) {
            b.append("<si>").append(i).append("</si>");
        }
        sharedStrings = b.append("</sst>").toString();
        return this;
    }

    RawXlsx workbookExtra(String xml) {
        workbookExtra = xml;
        return this;
    }

    RawXlsx part(String name, String contentType, String xml) {
        parts.put(name, xml.startsWith("<?xml") ? xml
                : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml);
        if (contentType != null) {
            overrides.add("<Override PartName=\"/" + name + "\" ContentType=\"" + contentType + "\"/>");
        }
        return this;
    }

    RawXlsx binary(String name, String extension, String contentType, byte[] data) {
        binaries.put(name, data);
        String d = "<Default Extension=\"" + extension + "\" ContentType=\"" + contentType + "\"/>";
        if (!defaults.contains(d)) {
            defaults.add(d);
        }
        return this;
    }

    RawXlsx rel(String source, String id, String type, String target) {
        rels.computeIfAbsent(source, k -> new ArrayList<>()).add("<Relationship Id=\"" + id + "\" Type=\""
                + (type.startsWith("http") ? type : REL + type) + "\" Target=\"" + target + "\"/>");
        return this;
    }

    byte[] bytes() {
        if (styles != null) {
            part("xl/styles.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml", styles);
            rel("xl/workbook.xml", "rIdStyles", "styles", "styles.xml");
        }
        if (sharedStrings != null) {
            part("xl/sharedStrings.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml",
                    sharedStrings);
            rel("xl/workbook.xml", "rIdStrings", "sharedStrings", "sharedStrings.xml");
        }
        part("xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "<workbook " + NS + "><sheets>" + String.join("", sheets) + "</sheets>" + workbookExtra + "</workbook>");
        rel("", "rId1", "officeDocument", "xl/workbook.xml");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream out = new ZipOutputStream(bytes)) {
            put(out, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\""
                    + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                    + " Extension=\"xml\" ContentType=\"application/xml\"/>" + String.join("", defaults)
                    + String.join("", overrides) + "</Types>");
            for (Map.Entry<String, List<String>> e : rels.entrySet()) {
                String src = e.getKey();
                int slash = src.lastIndexOf('/');
                String name = src.isEmpty() ? "_rels/.rels"
                        : src.substring(0, slash + 1) + "_rels/" + src.substring(slash + 1) + ".rels";
                put(out, name, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships"
                        + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + String.join("", e.getValue()) + "</Relationships>");
            }
            for (Map.Entry<String, String> e : parts.entrySet()) {
                put(out, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, byte[]> e : binaries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
            out.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void put(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    static String inline(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }

    static String number(String ref, int style, String value) {
        return "<c r=\"" + ref + "\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><v>" + value + "</v></c>";
    }
}
