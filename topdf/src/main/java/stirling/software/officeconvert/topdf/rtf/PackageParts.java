package stirling.software.officeconvert.topdf.rtf;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PackageParts {

    private static final String CT = "application/vnd.openxmlformats-officedocument.wordprocessingml.";

    private PackageParts() {}

    static void write(ZipOutputStream zip, Doc doc, PropsXml props, Content content, Rels body) throws IOException {
        StringBuilder overrides = new StringBuilder();
        override(overrides, "/word/document.xml", CT + "document.main+xml");
        put(zip, "word/styles.xml", StylesXml.write(doc, props));
        body.add("styles", "styles.xml", false);
        override(overrides, "/word/styles.xml", CT + "styles+xml");
        if (!doc.lists.lists.isEmpty()) {
            put(zip, "word/numbering.xml", NumberingXml.write(doc, props));
            body.add("numbering", "numbering.xml", false);
            override(overrides, "/word/numbering.xml", CT + "numbering+xml");
        }
        put(zip, "word/settings.xml", settings(doc));
        body.add("settings", "settings.xml", false);
        override(overrides, "/word/settings.xml", CT + "settings+xml");
        put(zip, "word/fontTable.xml", fonts(doc));
        body.add("fontTable", "fontTable.xml", false);
        override(overrides, "/word/fontTable.xml", CT + "fontTable+xml");
        notes(zip, content, body, overrides, false);
        notes(zip, content, body, overrides, true);
        for (Content.Part p : content.parts) {
            String tag = p.footer() ? "ftr" : "hdr";
            Chunks xml = p.story().chunks();
            Writer w = entry(zip, "word/" + p.name());
            w.write(Xml.HEAD + "<w:" + tag + " " + RtfPackage.NS + ">");
            write(w, xml);
            w.write("</w:" + tag + ">");
            close(zip, w);
            if (!p.story().rels.empty()) {
                put(zip, "word/_rels/" + p.name() + ".rels", p.story().rels.xml());
            }
            override(overrides, "/word/" + p.name(), CT + (p.footer() ? "footer+xml" : "header+xml"));
        }
        Set<String> extensions = new LinkedHashSet<>();
        for (Media.Item m : doc.media.items) {
            zip.putNextEntry(new ZipEntry("word/" + m.name()));
            zip.write(m.data());
            zip.closeEntry();
            extensions.add(m.name().substring(m.name().lastIndexOf('.') + 1));
        }
        put(zip, "word/_rels/document.xml.rels", body.xml());
        put(zip, "docProps/core.xml", core(doc));
        override(overrides, "/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml");
        put(zip, "_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "relationships\"><Relationship Id=\"rId1\" Type=\"" + Rels.NS + "/officeDocument\" Target=\""
                + "word/document.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/"
                + "2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>");
        StringBuilder types = new StringBuilder(Xml.HEAD)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.")
                .append("relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (String ext : extensions) {
            types.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(mime(ext))
                    .append("\"/>");
        }
        put(zip, "[Content_Types].xml", types.append(overrides).append("</Types>").toString());
    }

    private static void notes(ZipOutputStream zip, Content content, Rels body, StringBuilder overrides,
            boolean endnotes) throws IOException {
        if (content.notes.stream().noneMatch(n -> n.endnote() == endnotes)) {
            return;
        }
        String tag = endnotes ? "endnote" : "footnote";
        String name = tag + "s.xml";
        String sep = "<w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r>";
        Writer w = entry(zip, "word/" + name);
        w.write(Xml.HEAD + "<w:" + tag + "s " + RtfPackage.NS + "><w:" + tag
                + " w:type=\"separator\" w:id=\"0\">" + sep + "<w:separator/></w:r></w:p></w:" + tag + "><w:" + tag
                + " w:type=\"continuationSeparator\" w:id=\"1\">" + sep + "<w:continuationSeparator/></w:r></w:p></w:"
                + tag + ">");
        for (Content.NoteOut n : content.notes) {
            if (n.endnote() == endnotes) {
                w.write("<w:" + tag + " w:id=\"" + n.id() + "\">");
                write(w, n.xml());
                w.write("</w:" + tag + ">");
            }
        }
        w.write("</w:" + tag + "s>");
        close(zip, w);
        if (!content.notesRels.empty()) {
            put(zip, "word/_rels/" + name + ".rels", content.notesRels.xml());
        }
        body.add(tag + "s", name, false);
        override(overrides, "/word/" + name, CT + tag + "s+xml");
    }

    private static void write(Writer w, Chunks xml) throws IOException {
        if (xml.isEmpty()) {
            w.write("<w:p/>");
        } else {
            xml.writeTo(w);
        }
    }

    private static Writer entry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        return new BufferedWriter(new OutputStreamWriter(RtfPackage.keepOpen(zip), StandardCharsets.UTF_8), 1 << 16);
    }

    private static void close(ZipOutputStream zip, Writer w) throws IOException {
        w.close();
        zip.closeEntry();
    }

    private static String settings(Doc doc) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:settings ").append(RtfPackage.NS).append('>');
        if (doc.autoHyphenation) {
            b.append("<w:autoHyphenation/>");
        }
        b.append("<w:defaultTabStop w:val=\"").append(doc.defaultTab).append("\"/>");
        if (doc.background >= 0) {
            b.append("<w:displayBackgroundShape/>");
        }
        if (doc.facingPages) {
            b.append("<w:evenAndOddHeaders/>");
        }
        b.append("<w:footnotePr><w:footnote w:id=\"0\"/><w:footnote w:id=\"1\"/></w:footnotePr>")
                .append("<w:endnotePr><w:endnote w:id=\"0\"/><w:endnote w:id=\"1\"/></w:endnotePr>");
        return b.append("</w:settings>").toString();
    }

    private static String fonts(Doc doc) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:fonts ").append(RtfPackage.NS).append('>');
        Set<String> seen = new LinkedHashSet<>();
        for (FontTable.Font f : doc.fonts.all()) {
            if (f.name.isBlank() || !seen.add(f.name)) {
                continue;
            }
            b.append("<w:font w:name=\"").append(Xml.attr(f.name)).append("\">");
            if (f.alt != null && !f.alt.isBlank()) {
                b.append("<w:altName w:val=\"").append(Xml.attr(f.alt)).append("\"/>");
            }
            if (f.charset >= 0) {
                b.append("<w:charset w:val=\"").append(String.format("%02X", f.charset & 0xFF)).append("\"/>");
            }
            if (f.family != null) {
                String fam = switch (f.family) {
                    case "roman", "swiss", "modern", "script", "decorative" -> f.family;
                    case "decor" -> "decorative";
                    default -> "auto";
                };
                b.append("<w:family w:val=\"").append(fam).append("\"/>");
            }
            if (f.pitch > 0) {
                b.append("<w:pitch w:val=\"").append(f.pitch == 1 ? "fixed" : "variable").append("\"/>");
            }
            b.append("</w:font>");
        }
        return b.append("</w:fonts>").toString();
    }

    private static String core(Doc doc) {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<cp:coreProperties xmlns:cp=\"http://schemas.")
                .append("openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/")
                .append("elements/1.1/\">");
        element(b, "dc:title", doc.title);
        element(b, "dc:subject", doc.subject);
        element(b, "dc:creator", doc.author);
        element(b, "cp:keywords", doc.keywords);
        return b.append("</cp:coreProperties>").toString();
    }

    private static void element(StringBuilder b, String tag, String value) {
        if (value != null && !value.isBlank()) {
            b.append('<').append(tag).append('>');
            Xml.text(value.strip(), b);
            b.append("</").append(tag).append('>');
        }
    }

    private static String mime(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tiff" -> "image/tiff";
            case "emf" -> "image/x-emf";
            case "wmf" -> "image/x-wmf";
            case "pict" -> "image/pict";
            default -> "application/octet-stream";
        };
    }

    private static void override(StringBuilder b, String part, String type) {
        b.append("<Override PartName=\"").append(part).append("\" ContentType=\"").append(type).append("\"/>");
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
