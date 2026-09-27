package stirling.software.officeconvert.odt;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.MediaStore;
import stirling.software.officeconvert.sink.NoteHold;
import stirling.software.officeconvert.sink.SpillBuffer;
import stirling.software.officeconvert.sink.ZipParts;

public final class OdtWriter implements DocSink, Closeable {

    private final boolean flat;
    private final OutputStream out;
    private final ZipOutputStream zip;
    private final MediaStore store;
    private final OdtMedia media;
    private final NoteHold notes = new NoteHold();
    private final OdtStyles content = new OdtStyles("");
    private final OdtStyles master = new OdtStyles("M");
    private final OdtLists lists = new OdtLists();
    private final OdtPages pages = new OdtPages();
    private final SpillBuffer body = new SpillBuffer();
    private final Map<String, String> resolved = new HashMap<>();
    private OdtBody writer;
    private OdtPages.Running running = new OdtPages.Running(null, null, null, null, false);

    private int sectionIndex = -1;
    private boolean sectionOpen;
    private String firstStyle;
    private String firstElement;
    private Section previous;
    private String previousColumns;

    public OdtWriter(OutputStream target, boolean flat) throws IOException {
        this(target, flat, Pictures.COMPACT);
    }

    public OdtWriter(OutputStream target, boolean flat, Pictures pictures) throws IOException {
        this.flat = flat;
        this.store = new MediaStore(pictures);
        this.out = new BufferedOutputStream(new KeepOpen(target), 1 << 16);
        this.media = new OdtMedia(store, flat);
        if (flat) {
            zip = null;
        } else {
            zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
            zip.setLevel(6);
            ZipParts.stored(zip, "mimetype", OdtParts.MIME.getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) throws IOException {
        return store.add(bytes, ext, pixelWidth, pixelHeight, key);
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return store.get(key);
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet set) {
        writer = new OdtBody(content, styles, media, notes, lists);
        if (set == null) {
            return;
        }
        try {
            OdtBody side = new OdtBody(master, styles, media, null, new OdtLists());
            String header = running(side, set.header());
            String headerLeft = running(side, set.evenHeader());
            String footer = running(side, set.footer());
            String footerLeft = running(side, set.evenFooter());
            String empty = "<text:p text:style-name=\"Standard\"/>";
            running = new OdtPages.Running(header == null && headerLeft != null ? empty : header, headerLeft,
                    footer == null && footerLeft != null ? empty : footer, footerLeft, set.titlePage());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String running(OdtBody side, List<Paragraph> paras) throws IOException {
        if (paras == null || paras.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : paras) {
            side.paragraph(sb, p, OdtBody.Place.NESTED, false);
        }
        return sb.toString();
    }

    @Override
    public void block(Block block) throws IOException {
        for (Block b : notes.block(block)) {
            write(b);
        }
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        try {
            for (Block b : notes.note(id, paragraphs)) {
                write(b);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void write(Block b) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        boolean first = !sectionOpen;
        if (first) {
            sectionIndex++;
            sectionOpen = true;
            sb.append('\0').append('S').append(sectionIndex).append('\0');
        }
        String own;
        if (b instanceof Paragraph p) {
            own = writer.paragraph(sb, p, OdtBody.Place.FLOW, first);
        } else {
            lists.close(sb);
            own = writer.tables().table(sb, (Table) b, first);
        }
        if (first) {
            firstStyle = own;
            firstElement = b instanceof Table ? "style:table-properties" : "style:paragraph-properties";
        }
        if (b instanceof Paragraph p && p.endsSection != null) {
            closeSection(sb, p.endsSection);
        }
        body.append(sb);
    }

    private void closeSection(StringBuilder sb, Section s) {
        lists.close(sb);
        String key = "S" + sectionIndex;
        if (s.columns.size() > 1) {
            String style = content.own("section", null, columns(s));
            resolved.put(key, "<text:section text:style-name=\"" + style + "\" text:name=\"Section" + (sectionIndex + 1) + "\">");
            sb.append("</text:section>");
            if (previousColumns != null && !s.continuous) {
                balance(previousColumns, false);
            }
            previousColumns = style;
        } else {
            if (previousColumns != null && !s.continuous) {
                balance(previousColumns, false);
            }
            previousColumns = null;
        }
        String masterPage = null;
        String attrs = "";
        if (previous == null) {
            masterPage = pages.master(s);
            if (s.pageNumberStart != 1) {
                attrs = " style:page-number=\"" + Math.max(1, s.pageNumberStart) + "\"";
            }
        } else if (!s.continuous) {
            if (OdtPages.sameGeometry(previous, s)) {
                attrs = " fo:break-before=\"page\"";
            } else {
                masterPage = pages.master(s);
            }
        }
        if (firstStyle != null) {
            content.settle(firstStyle, masterPage, firstElement, attrs);
        }
        previous = s;
        sectionOpen = false;
    }

    private void balance(String sectionStyle, boolean on) {
        content.settle(sectionStyle, null, "style:section-properties", on ? "" : " text:dont-balance-text-columns=\"true\"");
    }

    private static String columns(Section s) {
        List<float[]> cols = s.columns;
        boolean even = cols.stream().allMatch(c -> Math.abs(c[0] - cols.getFirst()[0]) < 0.5f
                && (c == cols.getLast() || Math.abs(c[1] - cols.getFirst()[1]) < 0.5f));
        StringBuilder sb = new StringBuilder("<style:section-properties style:editable=\"false\"><style:columns fo:column-count=\"")
                .append(cols.size()).append('"');
        if (even) {
            sb.append(" fo:column-gap=\"").append(OdtXml.pt(cols.getFirst()[1])).append('"');
        }
        sb.append('>');
        float before = 0;
        for (int i = 0; i < cols.size(); i++) {
            float after = i + 1 < cols.size() ? cols.get(i)[1] : 0;
            float rel = cols.get(i)[0] + before / 2 + after / 2;
            sb.append("<style:column style:rel-width=\"").append(Math.max(1, Math.round(rel * 20))).append("*\" fo:start-indent=\"")
                    .append(OdtXml.pt(before / 2)).append("\" fo:end-indent=\"").append(OdtXml.pt(after / 2)).append("\"/>");
            before = after;
        }
        return sb.append("</style:columns></style:section-properties>").toString();
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        for (Block b : notes.drain()) {
            write(b);
        }
        if (sectionOpen) {
            StringBuilder sb = new StringBuilder();
            closeSection(sb, last);
            body.append(sb);
        }
        if (previousColumns != null) {
            balance(previousColumns, false);
        }
        Set<String> fonts = new LinkedHashSet<>();
        StringBuilder common = new StringBuilder();
        OdtParts.commonStyles(common, styles, fonts);
        StringBuilder listStyles = new StringBuilder();
        OdtLists.styles(listStyles, numbering, styles.normal, content.fonts);
        StringBuilder pageLayouts = new StringBuilder();
        StringBuilder masters = new StringBuilder();
        pages.write(pageLayouts, masters, running);
        fonts.addAll(content.fonts);
        fonts.addAll(master.fonts);

        if (flat) {
            Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
            StringBuilder head = new StringBuilder(OdtXml.HEAD).append("<office:document").append(OdtXml.NS)
                    .append(" office:mimetype=\"").append(OdtParts.MIME).append("\">");
            OdtParts.meta(head, title, author);
            OdtParts.settings(head);
            OdtStyles.fontFaces(head, fonts);
            head.append(common).append("<office:automatic-styles>");
            content.write(head);
            head.append(listStyles).append(pageLayouts);
            master.write(head);
            head.append("</office:automatic-styles><office:master-styles>").append(masters)
                    .append("</office:master-styles><office:body><office:text>");
            w.write(head.toString());
            body.copyTo(w, resolved::get);
            w.write("</office:text></office:body></office:document>");
            w.flush();
            out.flush();
            return;
        }
        zip.putNextEntry(new ZipEntry("content.xml"));
        Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);
        StringBuilder head = new StringBuilder(OdtXml.HEAD).append("<office:document-content").append(OdtXml.NS).append('>');
        OdtStyles.fontFaces(head, fonts);
        head.append("<office:automatic-styles>");
        content.write(head);
        head.append(listStyles).append("</office:automatic-styles><office:body><office:text>");
        w.write(head.toString());
        body.copyTo(w, resolved::get);
        w.write("</office:text></office:body></office:document-content>");
        w.flush();
        zip.closeEntry();

        StringBuilder st = new StringBuilder(OdtXml.HEAD).append("<office:document-styles").append(OdtXml.NS).append('>');
        OdtStyles.fontFaces(st, fonts);
        st.append(common).append("<office:automatic-styles>").append(pageLayouts);
        master.write(st);
        st.append("</office:automatic-styles><office:master-styles>").append(masters)
                .append("</office:master-styles></office:document-styles>");
        entry("styles.xml", st.toString());
        StringBuilder meta = new StringBuilder(OdtXml.HEAD).append("<office:document-meta").append(OdtXml.NS).append('>');
        OdtParts.meta(meta, title, author);
        entry("meta.xml", meta.append("</office:document-meta>").toString());
        StringBuilder settings = new StringBuilder(OdtXml.HEAD).append("<office:document-settings").append(OdtXml.NS).append('>');
        OdtParts.settings(settings);
        entry("settings.xml", settings.append("</office:document-settings>").toString());
        List<Picture.MediaRef> used = media.used();
        for (Picture.MediaRef m : used) {
            ZipParts.picture(zip, "Pictures/" + m.name(), store.bytes(m));
        }
        entry("META-INF/manifest.xml", OdtParts.manifest(used));
        zip.finish();
    }

    private void entry(String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        try (store; body) {
            if (zip != null) {
                zip.close();
            } else {
                out.close();
            }
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
