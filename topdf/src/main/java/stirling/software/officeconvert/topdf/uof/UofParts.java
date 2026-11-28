package stirling.software.officeconvert.topdf.uof;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.sink.KeepOpen;

final class UofParts implements AutoCloseable {

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    static final String W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";

    static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private final ZipOutputStream zip;

    private final Map<String, String> overrides = new LinkedHashMap<>();

    private final Map<byte[], String> media = new IdentityHashMap<>();

    private final Map<String, String> mediaRels = new LinkedHashMap<>();

    private final Map<String, Set<String>> used = new LinkedHashMap<>();

    private String title;

    private long limit = MAX_CHARS;

    static final long MAX_CHARS = 256L << 20;

    private String author;

    UofParts(OutputStream out) {
        this.zip = new ZipOutputStream(new KeepOpen(out));
    }

    void limit(long inputBytes) {
        limit = Math.min(MAX_CHARS, (32L << 20) + 16 * inputBytes);
    }

    long limit() {
        return limit;
    }

    void info(String title, String author) {
        this.title = title;
        this.author = author;
    }

    void add(String name, String xml) throws IOException {
        add(name, xml.getBytes(StandardCharsets.UTF_8));
    }

    void add(String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    void wordPart(String name, String xml) throws IOException {
        add("word/" + name + ".xml", xml);
        Set<String> rels = new LinkedHashSet<>();
        String key = "r:embed=\"";
        for (int at = xml.indexOf(key); at >= 0; at = xml.indexOf(key, at + key.length())) {
            int from = at + key.length();
            int to = xml.indexOf('"', from);
            if (to < 0) {
                break;
            }
            String rel = xml.substring(from, to);
            if (mediaRels.containsKey(rel)) {
                rels.add(rel);
            }
        }
        used.put(name, rels);
    }

    void override(String part, String type) {
        overrides.put("/" + part, type);
    }

    String media(byte[] data, String format) throws IOException {
        String known = media.get(data);
        if (known != null) {
            return known;
        }
        String ext = extension(data, format);
        if (ext == null) {
            return null;
        }
        int n = media.size() + 1;
        String rel = "rImg" + n;
        add("word/media/image" + n + "." + ext, data);
        media.put(data, rel);
        mediaRels.put(rel, "media/image" + n + "." + ext);
        return rel;
    }

    static String extension(byte[] d, String format) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return "jpeg";
        }
        if (d.length > 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return "gif";
        }
        if (d.length > 2 && d[0] == 'B' && d[1] == 'M') {
            return "bmp";
        }
        if (d.length > 4 && (d[0] == 'I' && d[1] == 'I' || d[0] == 'M' && d[1] == 'M')) {
            return "tiff";
        }
        if (d.length > 44 && d[0] == 1 && d[40] == ' ' && d[41] == 'E' && d[42] == 'M' && d[43] == 'F') {
            return "emf";
        }
        if (d.length > 4 && (d[0] & 0xFF) == 0xD7 && (d[1] & 0xFF) == 0xCD) {
            return "wmf";
        }
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT);
        return f.equals("wmf") || f.equals("emf") ? f : null;
    }

    static String inlinePicture(String rel, long cx, long cy, int id) {
        return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + cx + "\" cy=\""
                + cy + "\"/><wp:docPr id=\"" + id + "\" name=\"Picture " + id + "\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"" + id + "\" name=\"Picture " + id + "\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip"
                + " r:embed=\"" + rel + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm>"
                + "<a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom"
                + " prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline>"
                + "</w:drawing>";
    }

    void wordPackage(List<String> headers) throws IOException {
        StringBuilder types = new StringBuilder(XML);
        types.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
                .append("<Default Extension=\"gif\" ContentType=\"image/gif\"/><Default Extension=\"bmp\" ContentType=\"image/bmp\"/>")
                .append("<Default Extension=\"tiff\" ContentType=\"image/tiff\"/><Default Extension=\"emf\" ContentType=\"image/x-emf\"/>")
                .append("<Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/>");
        String wml = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
        override("word/document.xml", wml + "document.main+xml");
        override("word/styles.xml", wml + "styles+xml");
        override("word/numbering.xml", wml + "numbering+xml");
        override("word/footnotes.xml", wml + "footnotes+xml");
        override("word/endnotes.xml", wml + "endnotes+xml");
        override("word/settings.xml", wml + "settings+xml");
        override("docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml");
        StringBuilder rels = new StringBuilder(XML);
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rS\" Type=\"").append(REL).append("styles\" Target=\"styles.xml\"/>")
                .append("<Relationship Id=\"rN\" Type=\"").append(REL).append("numbering\" Target=\"numbering.xml\"/>")
                .append("<Relationship Id=\"rF\" Type=\"").append(REL).append("footnotes\" Target=\"footnotes.xml\"/>")
                .append("<Relationship Id=\"rE\" Type=\"").append(REL).append("endnotes\" Target=\"endnotes.xml\"/>")
                .append("<Relationship Id=\"rT\" Type=\"").append(REL).append("settings\" Target=\"settings.xml\"/>");
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            boolean header = h.startsWith("header");
            override("word/" + h + ".xml", wml + (header ? "header+xml" : "footer+xml"));
            rels.append("<Relationship Id=\"rH").append(i + 1).append("\" Type=\"").append(REL)
                    .append(header ? "header" : "footer").append("\" Target=\"").append(h).append(".xml\"/>");
        }
        rels.append(images("document")).append("</Relationships>");
        for (Map.Entry<String, Set<String>> u : used.entrySet()) {
            if (!u.getKey().equals("document") && !u.getValue().isEmpty()) {
                add("word/_rels/" + u.getKey() + ".xml.rels", XML + "<Relationships"
                        + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + images(u.getKey())
                        + "</Relationships>");
            }
        }
        add("word/_rels/document.xml.rels", rels.toString());
        core();
        for (Map.Entry<String, String> o : overrides.entrySet()) {
            types.append("<Override PartName=\"").append(o.getKey()).append("\" ContentType=\"").append(o.getValue())
                    .append("\"/>");
        }
        types.append("</Types>");
        add("[Content_Types].xml", types.toString());
        add("_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/"
                + "core-properties\" Target=\"docProps/core.xml\"/></Relationships>");
    }

    private String images(String part) {
        StringBuilder rels = new StringBuilder();
        for (String rel : used.getOrDefault(part, Set.of())) {
            rels.append("<Relationship Id=\"").append(rel).append("\" Type=\"").append(REL)
                    .append("image\" Target=\"").append(mediaRels.get(rel)).append("\"/>");
        }
        return rels.toString();
    }

    private void core() throws IOException {
        StringBuilder b = new StringBuilder(XML);
        b.append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">");
        if (title != null && !title.isBlank()) {
            b.append("<dc:title>").append(Uof.esc(title.strip())).append("</dc:title>");
        }
        if (author != null && !author.isBlank()) {
            b.append("<dc:creator>").append(Uof.esc(author.strip())).append("</dc:creator>");
        }
        b.append("<dc:language>zh-CN</dc:language></cp:coreProperties>");
        add("docProps/core.xml", b.toString());
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
