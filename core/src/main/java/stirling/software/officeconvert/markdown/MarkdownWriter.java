package stirling.software.officeconvert.markdown;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import stirling.software.officeconvert.sink.ListLabels;
import stirling.software.officeconvert.sink.MediaStore;
import stirling.software.officeconvert.sink.SpillBuffer;
import stirling.software.officeconvert.sink.ZipParts;

public final class MarkdownWriter implements DocSink, Closeable {

    public enum Layout {
        SINGLE_FILE,
        PACKAGE
    }

    public static final String PACKAGE_DOCUMENT = "document.md";

    private final OutputStream out;
    private final Layout layout;
    private final MediaStore media;
    private final MarkdownBlocks blocks;
    private final SpillBuffer body = new SpillBuffer();
    private final Map<Integer, List<Paragraph>> notes = new TreeMap<>();
    private final Map<String, String> files = new HashMap<>();
    private ZipOutputStream zip;

    public MarkdownWriter(OutputStream target, Pictures pictures, Layout layout) {
        this.out = new KeepOpen(target);
        this.layout = layout;
        this.media = new MediaStore(pictures);
        this.blocks = new MarkdownBlocks(this::uri);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key)
            throws IOException {
        return media.add(bytes, ext, pixelWidth, pixelHeight, key);
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return media.get(key);
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet running) {
        if (styles != null && styles.normal != null) {
            blocks.bodyFont(styles.normal.font());
        }
    }

    @Override
    public void block(Block block) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        if (block instanceof Paragraph p) {
            blocks.paragraph(sb, p);
        } else {
            blocks.table(sb, (Table) block);
        }
        body.append(sb);
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    private String uri(Picture pic) throws IOException {
        Picture.MediaRef ref = media.shaped(pic);
        if (ref == null) {
            return null;
        }
        if (layout == Layout.SINGLE_FILE) {
            return "data:" + ref.contentType() + ";base64," + Base64.getEncoder().encodeToString(media.bytes(ref));
        }
        String known = files.get(ref.name());
        if (known != null) {
            return known;
        }
        String ext = ref.contentType().equals("image/jpeg") ? "jpg" : ref.contentType().substring(6);
        String path = "images/picture" + (files.size() + 1) + "." + ext;
        ZipParts.picture(zip(), path, media.bytes(ref));
        files.put(ref.name(), path);
        return path;
    }

    private ZipOutputStream zip() {
        if (zip == null) {
            zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        }
        return zip;
    }

    @Override
    public void finish(Section last, HeaderFooterSet running, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        StringBuilder sb = new StringBuilder();
        blocks.flush(sb);
        List<Integer> referenced = new ArrayList<>(new LinkedHashSet<>(blocks.footnotes()));
        for (Integer id : notes.keySet()) {
            if (!referenced.contains(id)) {
                referenced.add(id);
            }
        }
        for (Integer id : referenced) {
            List<Paragraph> note = notes.get(id);
            if (note != null) {
                blocks.note(sb, id, note);
            }
        }
        body.append(sb);
        ListLabels labels = new ListLabels(numbering == null ? new Numbering() : numbering);
        OutputStream target = out;
        if (layout == Layout.PACKAGE) {
            zip().putNextEntry(new ZipEntry(PACKAGE_DOCUMENT));
            target = new KeepOpen(zip);
        }
        Writer w = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8), 1 << 16);
        body.copyTo(w, key -> MarkdownBlocks.label(labels, key));
        w.write('\n');
        w.flush();
        if (zip != null) {
            zip.closeEntry();
            zip.finish();
        }
    }

    @Override
    public void close() throws IOException {
        try (body; media) {
            out.close();
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
