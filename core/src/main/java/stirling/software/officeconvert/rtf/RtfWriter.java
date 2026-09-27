package stirling.software.officeconvert.rtf;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

public final class RtfWriter implements DocSink, Closeable {

    private final OutputStream out;
    private final MediaStore store;
    private final NoteHold notes = new NoteHold();
    private final RtfTables tables = new RtfTables();
    private final RtfShapes.Counter ids = new RtfShapes.Counter();
    private final SpillBuffer body = new SpillBuffer();
    private final Map<String, String> resolved = new HashMap<>();
    private RtfBody writer;
    private String running = "";
    private boolean facing;
    private boolean titlePage;

    private int sectionIndex = -1;
    private boolean sectionOpen;
    private Section first;

    public RtfWriter(OutputStream target) {
        this(target, Pictures.COMPACT);
    }

    public RtfWriter(OutputStream target, Pictures pictures) {
        this.out = new KeepOpen(target);
        this.store = new MediaStore(pictures);
    }

    private final RtfShapes.Images images = new RtfShapes.Images() {
        @Override
        public Picture.MediaRef shaped(Picture pic) throws IOException {
            return store.shaped(pic);
        }

        @Override
        public byte[] bytes(Picture.MediaRef ref) throws IOException {
            return store.bytes(ref);
        }
    };

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
        writer = new RtfBody(tables, styles, notes, images, ids);
        if (set == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            facing = !set.evenHeader().isEmpty() || !set.evenFooter().isEmpty();
            titlePage = set.titlePage();
            running(sb, facing ? "headerr" : "header", set.header());
            running(sb, "headerl", facing ? set.evenHeader() : List.of());
            running(sb, facing ? "footerr" : "footer", set.footer());
            running(sb, "footerl", facing ? set.evenFooter() : List.of());
            if (titlePage) {
                sb.append("{\\headerf \\pard\\plain \\par}{\\footerf \\pard\\plain \\par}");
            }
            running = sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void running(StringBuilder sb, String kind, List<Paragraph> paras) throws IOException {
        if (paras == null || paras.isEmpty()) {
            return;
        }
        sb.append('{').append('\\').append(kind).append(' ');
        for (Paragraph p : paras) {
            writer.paragraph(sb, p, RtfBody.End.PAR, false, false);
        }
        sb.append('}');
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
            throw new UncheckedIOException(e);
        }
    }

    private void write(Block b) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        if (!sectionOpen) {
            sectionIndex++;
            sectionOpen = true;
            sb.append('\0').append('S').append(sectionIndex).append('\0');
        }
        if (b instanceof Paragraph p) {
            boolean closes = p.endsSection != null;
            writer.paragraph(sb, p, closes ? RtfBody.End.SECT : RtfBody.End.PAR, false, true);
            if (closes) {
                closeSection(p.endsSection);
            }
        } else {
            writer.tables().table(sb, (Table) b);
        }
        body.append(sb);
    }

    private void closeSection(Section s) {
        StringBuilder sb = new StringBuilder("\\sectd\\ltrsect");
        if (sectionIndex == 0) {
            first = s;
        } else {
            sb.append(s.continuous ? "\\sbknone" : "\\sbkpage");
        }
        RtfParts.page(sb, s);
        if (sectionIndex == 0) {
            if (s.titlePage || titlePage) {
                sb.append("\\titlepg");
            }
            if (s.pageNumberStart != 1) {
                sb.append("\\pgnrestart\\pgnstarts").append(Math.max(0, s.pageNumberStart));
            }
        }
        sb.append(' ');
        if (sectionIndex == 0) {
            sb.append(running);
        }
        resolved.put("S" + sectionIndex, sb.toString());
        sectionOpen = false;
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        for (Block b : notes.drain()) {
            write(b);
        }
        if (sectionOpen) {
            closeSection(last);
        }
        StringBuilder head = new StringBuilder("{\\rtf1\\ansi\\ansicpg1252\\uc1\\deff0\\deflang1033\\deflangfe1033\\adeflang1025\n");
        StringBuilder sheet = new StringBuilder();
        RtfParts.stylesheet(sheet, styles, writer.styleNumbers, writer);
        StringBuilder lists = new StringBuilder();
        RtfParts.lists(lists, numbering, styles, writer);
        tables.fontTable(head);
        head.append('\n');
        tables.colourTable(head);
        head.append('\n').append("{\\*\\defchp \\f0\\fs").append(RtfText.halfPoints(styles.normal.size()))
                .append("}{\\*\\defpap \\ql\\li0\\ri0\\sl240\\slmult1\\nowidctlpar}\n").append(sheet).append('\n').append(lists)
                .append('\n');
        RtfParts.info(head, title, author);
        head.append('\n');
        RtfParts.document(head, first != null ? first : last, facing);
        head.append('\n');
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII), 1 << 16);
        w.write(head.toString());
        body.copyTo(w, resolved::get);
        w.write("}\n");
        w.flush();
    }

    @Override
    public void close() throws IOException {
        try (store; body) {
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
