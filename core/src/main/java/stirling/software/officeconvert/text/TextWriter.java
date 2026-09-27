package stirling.software.officeconvert.text;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.sink.ListLabels;
import stirling.software.officeconvert.sink.SpillBuffer;

public final class TextWriter implements DocSink, Closeable {

    private static final Picture.MediaRef NO_MEDIA = new Picture.MediaRef("none", "image/png", 1, 1);

    private final OutputStream out;
    private final SpillBuffer body = new SpillBuffer();
    private final TextBlocks blocks = new TextBlocks();
    private final Map<Integer, List<Paragraph>> notes = new TreeMap<>();
    private final Map<Integer, String> referenced = new LinkedHashMap<>();
    private List<Paragraph> footer = List.of();
    private boolean started;

    public TextWriter(OutputStream target) {
        this.out = new KeepOpen(target);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) {
        return NO_MEDIA;
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return NO_MEDIA;
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet running) {
        if (running == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Paragraph p : running.header()) {
                blocks.running(sb, p);
            }
            if (!sb.isEmpty()) {
                body.append(sb).append("\n");
                started = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        footer = running.footer();
    }

    @Override
    public void block(Block block) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        if (block instanceof Paragraph p && Links.pageOf(p.bookmark) > 0 && started) {
            notes(sb, false);
        }
        List<Integer> refs = new ArrayList<>();
        if (block instanceof Paragraph p) {
            blocks.paragraph(sb, p, refs);
        } else {
            blocks.table(sb, (Table) block, refs);
        }
        for (int id : refs) {
            referenced.putIfAbsent(id, blocks.marker(id));
        }
        started |= !sb.isEmpty();
        body.append(sb);
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    private void notes(StringBuilder sb, boolean all) {
        List<Integer> done = new ArrayList<>();
        for (Map.Entry<Integer, String> ref : referenced.entrySet()) {
            if (notes.containsKey(ref.getKey())) {
                done.add(ref.getKey());
            }
        }
        if (done.isEmpty() && (!all || notes.isEmpty())) {
            return;
        }
        blocks.separate();
        for (int id : done) {
            blocks.note(sb, referenced.remove(id), notes.remove(id));
        }
        if (all) {
            for (Map.Entry<Integer, List<Paragraph>> n : notes.entrySet()) {
                blocks.note(sb, blocks.marker(n.getKey()), n.getValue());
            }
            notes.clear();
        }
        blocks.separate();
    }

    @Override
    public void finish(Section last, HeaderFooterSet running, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        StringBuilder sb = new StringBuilder();
        notes(sb, true);
        StringBuilder foot = new StringBuilder();
        for (Paragraph p : footer) {
            blocks.running(foot, p);
        }
        if (!foot.isEmpty()) {
            sb.append('\n').append(foot);
        }
        body.append(sb);
        ListLabels labels = new ListLabels(numbering);
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
        body.copyTo(w, key -> TextBlocks.label(labels, key));
        w.flush();
    }

    @Override
    public void close() throws IOException {
        try (body) {
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
