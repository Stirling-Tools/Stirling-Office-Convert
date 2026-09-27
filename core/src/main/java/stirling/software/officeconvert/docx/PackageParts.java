package stirling.software.officeconvert.docx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.build.DocSink.HeaderFooterSet;
import stirling.software.officeconvert.extract.FontNames;

final class PackageParts {

    static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private PackageParts() {}

    static String settings(HeaderFooterSet hf, boolean footnotes) {
        boolean evenOdd = hf != null && (!hf.evenHeader().isEmpty() || !hf.evenFooter().isEmpty());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"720\"/>"
                + (evenOdd ? "<w:evenAndOddHeaders/>" : "")
                + "<w:characterSpacingControl w:val=\"doNotCompress\"/>"
                + (footnotes ? "<w:footnotePr><w:footnote w:id=\"-1\"/><w:footnote w:id=\"0\"/></w:footnotePr>" : "")
                + "<w:compat>"
                + "<w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/>"
                + "<w:compatSetting w:name=\"overrideTableStyleFontSizeAndJustification\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                + "<w:compatSetting w:name=\"enableOpenTypeFeatures\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                + "<w:compatSetting w:name=\"doNotFlipMirrorIndents\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"1\"/>"
                + "</w:compat></w:settings>";
    }

    static String fontTable(Iterable<String> fonts) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:fonts xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        for (String f : fonts) {
            boolean symbol = FontNames.isSymbolFamily(f);
            boolean mono = FontNames.looksMono(f);
            boolean serif = FontNames.looksSerif(f);
            String family = symbol ? "auto" : mono ? "modern" : serif ? "roman" : "swiss";
            sb.append("<w:font w:name=\"").append(Xml.esc(f)).append("\">")
                    .append("<w:charset w:val=\"").append(symbol ? "02" : "00").append("\"/>")
                    .append("<w:family w:val=\"").append(family).append("\"/>")
                    .append("<w:pitch w:val=\"").append(mono ? "fixed" : "variable").append("\"/></w:font>");
        }
        sb.append("</w:fonts>");
        return sb.toString();
    }

    private static final Pattern REL_ID = Pattern.compile("r:(?:id|embed)=\"(rId\\d+)\"");

    static String subPartRels(String xml, PartContext ctx) {
        StringBuilder sb = new StringBuilder();
        Matcher m = REL_ID.matcher(xml);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String id = m.group(1);
            if (seen.add(id)) {
                ctx.linkRels.forEach((url, rel) -> {
                    if (rel.equals(id)) {
                        sb.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(REL)
                                .append("hyperlink\" Target=\"").append(Xml.esc(url)).append("\" TargetMode=\"External\"/>");
                    }
                });
                ctx.imageRels.forEach((name, rel) -> {
                    if (rel.equals(id)) {
                        sb.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(REL)
                                .append("image\" Target=\"media/").append(name).append("\"/>");
                    }
                });
            }
        }
        return sb.isEmpty() ? null : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + sb + "</Relationships>";
    }

    static String documentRels(boolean numbering, boolean footnotes, Map<String, String> partRels, PartContext ctx) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + REL + "settings\" Target=\"settings.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + REL + "fontTable\" Target=\"fontTable.xml\"/>");
        if (numbering) {
            sb.append("<Relationship Id=\"rId4\" Type=\"" + REL + "numbering\" Target=\"numbering.xml\"/>");
        }
        if (footnotes) {
            sb.append("<Relationship Id=\"rId5\" Type=\"" + REL + "footnotes\" Target=\"footnotes.xml\"/>");
        }
        for (Map.Entry<String, String> e : partRels.entrySet()) {
            String[] kn = e.getValue().split("\\|");
            sb.append("<Relationship Id=\"").append(e.getKey()).append("\" Type=\"").append(REL).append(kn[0])
                    .append("\" Target=\"").append(kn[1]).append("\"/>");
        }
        for (Map.Entry<String, String> e : ctx.imageRels.entrySet()) {
            sb.append("<Relationship Id=\"").append(e.getValue()).append("\" Type=\"").append(REL)
                    .append("image\" Target=\"media/").append(e.getKey()).append("\"/>");
        }
        for (Map.Entry<String, String> e : ctx.linkRels.entrySet()) {
            sb.append("<Relationship Id=\"").append(e.getValue()).append("\" Type=\"").append(REL)
                    .append("hyperlink\" Target=\"").append(Xml.esc(e.getKey())).append("\" TargetMode=\"External\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    static String contentTypes(boolean numbering, boolean footnotes, Map<String, String> partRels) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>"
                + "<Override PartName=\"/word/fontTable.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        if (numbering) {
            sb.append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>");
        }
        if (footnotes) {
            sb.append("<Override PartName=\"/word/footnotes.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml\"/>");
        }
        for (String v : partRels.values()) {
            String[] kn = v.split("\\|");
            sb.append("<Override PartName=\"/word/").append(kn[1]).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.")
                    .append(kn[0]).append("+xml\"/>");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    static String core(String title, String author) {
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + (title == null || title.isBlank() ? "" : "<dc:title>" + Xml.esc(title) + "</dc:title>")
                + (author == null || author.isBlank() ? "" : "<dc:creator>" + Xml.esc(author) + "</dc:creator>")
                + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created>"
                + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>"
                + "</cp:coreProperties>";
    }
}
