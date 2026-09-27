package stirling.software.officeconvert.docx;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.SpillFile;
import stirling.software.officeconvert.sink.ZipParts;

public final class DocxWriter implements DocSink, Closeable {

    private static final String NS =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                    + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                    + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                    + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
                    + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\""
                    + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"";

    private static final long MEDIA_MEMORY_LIMIT = 4L * 1024 * 1024;

    private final ZipOutputStream zip;
    private final Writer out;
    private final Map<Object, Picture.MediaRef> mediaByKey = new HashMap<>();
    private final List<Object[]> mediaFiles = new ArrayList<>();
    private final PartContext ctx = new PartContext();
    private BodyXml body;
    private long mediaBytes;
    private final SpillFile spill = new SpillFile("office-convert-media");
    private HeaderFooterSet running;
    private final StringBuilder footnotes = new StringBuilder();

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        footnotes.append("<w:footnote w:id=\"").append(id).append("\">");
        for (Paragraph p : paragraphs) {
            body.paragraph(footnotes, p, false);
        }
        footnotes.append("</w:footnote>");
    }

    private boolean hasFootnotes() {
        return !footnotes.isEmpty();
    }

    public DocxWriter(OutputStream target) throws IOException {
        this.zip = new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
        this.zip.setLevel(6);
        this.out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);
        zip.putNextEntry(new ZipEntry("word/document.xml"));
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.write("<w:document " + NS + "><w:body>");
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet running) {
        this.body = new BodyXml(ctx, styles);
        this.running = running;
        ctx.fonts.add(styles.normal.font());
        if (running != null) {
            addPart("header", running.header(), "default");
            addPart("footer", running.footer(), "default");
            addPart("header", running.evenHeader(), "even");
            addPart("footer", running.evenFooter(), "even");
            if (running.titlePage()) {
                ctx.headerRelIds.put("header|first", "rIdHf" + ctx.headerRelIds.size());
                ctx.headerRelIds.put("footer|first", "rIdHf" + ctx.headerRelIds.size());
            }
        }
    }

    private void addPart(String kind, List<Paragraph> content, String type) {
        if (content != null && !content.isEmpty()) {
            ctx.headerRelIds.put(kind + "|" + type, "rIdHf" + ctx.headerRelIds.size());
        }
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return mediaByKey.get(key);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key)
            throws IOException {
        Picture.MediaRef existing = mediaByKey.get(key);
        if (existing != null) {
            return existing;
        }
        String name = "image" + (mediaFiles.size() + 1) + "." + ext;
        Picture.MediaRef ref =
                new Picture.MediaRef(name, "jpeg".equals(ext) ? "image/jpeg" : "image/png", pixelWidth, pixelHeight);
        if (mediaBytes + bytes.length > MEDIA_MEMORY_LIMIT) {
            mediaFiles.add(new Object[] {name, spill.append(bytes)});
        } else {
            mediaBytes += bytes.length;
            mediaFiles.add(new Object[] {name, bytes});
        }
        mediaByKey.put(key, ref);
        return ref;
    }

    @Override
    public void block(Block block) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        if (block instanceof Paragraph p) {
            body.paragraph(sb, p, true);
        } else if (block instanceof Table t) {
            body.table(sb, t);
        }
        out.write(sb.toString());
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styleSheet,
            String title, String author) throws IOException {
        StringBuilder sb = new StringBuilder();
        body.sectPr(sb, last);
        out.write(sb.toString());
        out.write("</w:body></w:document>");
        out.flush();
        zip.closeEntry();

        Map<String, String> partRels = new LinkedHashMap<>();
        HeaderFooterSet hf = running;
        if (hf != null) {
            writeHeaderPart("header", "default", hf.header(), partRels);
            writeHeaderPart("footer", "default", hf.footer(), partRels);
            writeHeaderPart("header", "even", hf.evenHeader(), partRels);
            writeHeaderPart("footer", "even", hf.evenFooter(), partRels);
            if (hf.titlePage()) {
                writeHeaderPart("header", "first", List.of(new Paragraph()), partRels);
                writeHeaderPart("footer", "first", List.of(new Paragraph()), partRels);
            }
        }
        boolean hasNumbering = numbering != null && !numbering.isEmpty();
        if (hasNumbering) {
            entry("word/numbering.xml", NumberingPart.xml(numbering, styleSheet));
        }
        if (hasFootnotes()) {
            String sep = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:separator/></w:r></w:p></w:footnote>"
                    + "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>";
            subPart("word/footnotes.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:footnotes " + NS + ">" + sep + footnotes + "</w:footnotes>");
        }
        entry("word/styles.xml", StylesPart.xml(styleSheet));
        entry("word/settings.xml", PackageParts.settings(hf, hasFootnotes()));
        entry("word/fontTable.xml", PackageParts.fontTable(ctx.fonts));
        for (Object[] m : mediaFiles) {
            ZipParts.picture(zip, "word/media/" + m[0], m[1] instanceof byte[] b ? b : spill.read((SpillFile.Block) m[1]));
        }
        entry("word/_rels/document.xml.rels", PackageParts.documentRels(hasNumbering, hasFootnotes(), partRels, ctx));
        entry("[Content_Types].xml", PackageParts.contentTypes(hasNumbering, hasFootnotes(), partRels));
        entry("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"" + PackageParts.REL + "officeDocument\" Target=\"word/document.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                        + "<Relationship Id=\"rId3\" Type=\"" + PackageParts.REL + "extended-properties\" Target=\"docProps/app.xml\"/>"
                        + "</Relationships>");
        entry("docProps/core.xml", PackageParts.core(title, author));
        entry("docProps/app.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                        + "<Application>Stirling-PDF</Application></Properties>");
        zip.finish();
    }

    private void writeHeaderPart(String kind, String type, List<Paragraph> content, Map<String, String> partRels)
            throws IOException {
        String relId = ctx.headerRelIds.get(kind + "|" + type);
        if (relId == null || content == null || content.isEmpty()) {
            return;
        }
        String name = kind + (partRels.size() + 1) + ".xml";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:")
                .append(kind.equals("header") ? "hdr" : "ftr").append(' ').append(NS).append('>');
        for (Paragraph p : content) {
            body.paragraph(sb, p, false);
        }
        sb.append("</w:").append(kind.equals("header") ? "hdr" : "ftr").append('>');
        subPart("word/" + name, sb.toString());
        partRels.put(relId, kind + "|" + name);
    }

    private void subPart(String name, String xml) throws IOException {
        entry(name, xml);
        String rels = PackageParts.subPartRels(xml, ctx);
        if (rels != null) {
            int slash = name.lastIndexOf('/');
            entry(name.substring(0, slash) + "/_rels/" + name.substring(slash + 1) + ".rels", rels);
        }
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            spill.close();
        }
    }

    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
