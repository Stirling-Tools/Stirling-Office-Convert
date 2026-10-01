package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.DocumentProperties;
import org.apache.poi.hwpf.usermodel.Range;

final class DocWriter {

    static final int COMPATIBILITY = 11;

    private static final String CT = "application/vnd.openxmlformats-officedocument.wordprocessingml.";

    private final Conv c;

    private final boolean defused;

    DocWriter(HWPFDocument doc, OutputStream out, boolean defused) {
        this.c = new Conv(new Source(doc), new Zip(out));
        this.defused = defused;
    }

    DocPackage.Outcome write() throws IOException {
        Rels rels = new Rels();
        Sections sections = new Sections(c, rels);
        c.notes = new Notes(c);
        sections.headers();
        StringBuilder body = new StringBuilder(1 << 16).append(Xml.HEAD).append("<w:document").append(Xml.NAMESPACES)
                .append("><w:body>");
        Range main = c.src.doc.getRange();
        new Story(c, rels, Story.Kind.MAIN, 0, sections).write(main.getStartOffset(), main.getEndOffset(), body);
        body.append(sections.last()).append("</w:body></w:document>");
        c.zip.put("word/document.xml", body);
        body = null;
        StringBuilder types = new StringBuilder(Xml.HEAD)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                .append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
                .append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>")
                .append("<Default Extension=\"bmp\" ContentType=\"image/bmp\"/>")
                .append("<Default Extension=\"tiff\" ContentType=\"image/tiff\"/>")
                .append("<Default Extension=\"emf\" ContentType=\"image/x-emf\"/>")
                .append("<Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/>")
                .append("<Override PartName=\"/word/document.xml\" ContentType=\"").append(CT)
                .append("document.main+xml\"/>");
        for (String o : sections.overrides) {
            types.append(o);
        }
        part(types, rels, "styles", c.styles.part());
        if (!c.lists.isEmpty()) {
            part(types, rels, "numbering", c.lists.part());
        }
        part(types, rels, "settings", settings());
        if (c.notes.hasFootnotes()) {
            c.notes.write(true);
            rels.add("footnotes", "footnotes.xml");
            types.append("<Override PartName=\"/word/footnotes.xml\" ContentType=\"").append(CT)
                    .append("footnotes+xml\"/>");
        }
        if (c.notes.hasEndnotes()) {
            c.notes.write(false);
            rels.add("endnotes", "endnotes.xml");
            types.append("<Override PartName=\"/word/endnotes.xml\" ContentType=\"").append(CT)
                    .append("endnotes+xml\"/>");
        }
        c.zip.put("word/_rels/document.xml.rels", rels.part());
        String core = core();
        if (core != null) {
            types.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-")
                    .append("package.core-properties+xml\"/>");
        }
        c.zip.put("[Content_Types].xml", types.append("</Types>"));
        if (core != null) {
            c.zip.put("docProps/core.xml", core);
        }
        c.zip.put("_rels/.rels", Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\"><Relationship Id=\"rId1\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/>" + (core == null ? "" : "<Relationship Id=\"rId2\" Type=\"http://"
                + "schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/"
                + "core.xml\"/>") + "</Relationships>");
        c.zip.finish();
        if (defused || c.media.dropped) {
            c.lost = true;
            c.warn("Some pictures were too large to convert and were left out");
        }
        return new DocPackage.Outcome(c.warnings, c.lost);
    }

    private String core() {
        SummaryInformation info;
        try {
            info = c.src.doc.getSummaryInformation();
        } catch (RuntimeException e) {
            return null;
        }
        if (info == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        element(b, "dc:title", info.getTitle());
        element(b, "dc:creator", info.getAuthor());
        element(b, "dc:subject", info.getSubject());
        element(b, "cp:keywords", info.getKeywords());
        if (b.isEmpty()) {
            return null;
        }
        return Xml.HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/"
                + "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" + b + "</cp:coreProperties>";
    }

    private static void element(StringBuilder b, String name, String value) {
        if (value != null && !value.isBlank() && value.length() < 4096) {
            b.append('<').append(name).append('>').append(Xml.esc(value.strip())).append("</").append(name).append('>');
        }
    }

    private void part(StringBuilder types, Rels rels, String name, String xml) throws IOException {
        c.zip.put("word/" + name + ".xml", xml);
        rels.add(name, name + ".xml");
        types.append("<Override PartName=\"/word/").append(name).append(".xml\" ContentType=\"").append(CT)
                .append(name).append("+xml\"/>");
    }

    private String settings() {
        StringBuilder b = new StringBuilder(Xml.HEAD).append("<w:settings").append(Xml.NAMESPACES).append('>');
        DocumentProperties dop;
        try {
            dop = c.src.doc.getDocProperties();
        } catch (RuntimeException e) {
            dop = null;
        }
        if (dop != null) {
            if (dop.isFMirrorMargins()) {
                b.append("<w:mirrorMargins/>");
            }
            if (dop.isFAutoHyphen()) {
                b.append("<w:autoHyphenation/>");
            }
            int tab = dop.getDxaTab();
            b.append("<w:defaultTabStop w:val=\"").append(tab > 0 ? tab : 720).append("\"/>");
            if (dop.isFFacingPages()) {
                b.append("<w:evenAndOddHeaders/>");
            }
        }
        b.append("<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/")
                .append("office/word\" w:val=\"").append(COMPATIBILITY).append("\"/></w:compat>");
        return b.append("</w:settings>").toString();
    }
}
