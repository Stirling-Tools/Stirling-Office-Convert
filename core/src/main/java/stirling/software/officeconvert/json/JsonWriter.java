package stirling.software.officeconvert.json;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.sink.MediaStore;

/** Writes the document as JSON (see {@code docs/json-format.md}); the body streams out block by block. */
public final class JsonWriter implements DocSink, Closeable {

    public static final String FORMAT = "stirling-office-convert";

    public static final int VERSION = 1;

    private final Writer out;
    private final MediaStore media;
    private final Map<Integer, List<Paragraph>> notes = new TreeMap<>();
    private HeaderFooterSet running;
    private boolean first = true;

    public JsonWriter(OutputStream target, Pictures pictures) throws IOException {
        this.out = new BufferedWriter(new OutputStreamWriter(new KeepOpen(target), StandardCharsets.UTF_8), 1 << 16);
        this.media = new MediaStore(pictures);
        out.write("{\"format\":\"" + FORMAT + "\",\"version\":" + VERSION + ",\"body\":[");
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
        this.running = running;
    }

    @Override
    public void block(Block block) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        if (!first) {
            sb.append(',');
        }
        first = false;
        ModelJson.block(sb, block);
        out.write(sb.toString());
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        StringBuilder sb = new StringBuilder("]");
        if (title != null && !title.isBlank()) {
            Json.string(sb.append(",\"title\":"), title);
        }
        if (author != null && !author.isBlank()) {
            Json.string(sb.append(",\"author\":"), author);
        }
        if (last != null) {
            ModelJson.section(sb.append(",\"page\":"), last);
        }
        ModelJson.styles(sb.append(",\"styles\":"), styles);
        HeaderFooterSet hf = running != null ? running : runningSet;
        if (hf != null) {
            running(sb, "header", hf.header());
            running(sb, "footer", hf.footer());
            running(sb, "evenHeader", hf.evenHeader());
            running(sb, "evenFooter", hf.evenFooter());
            if (hf.titlePage()) {
                sb.append(",\"titlePage\":true");
            }
        }
        if (numbering != null && !numbering.isEmpty()) {
            ModelJson.lists(sb.append(",\"lists\":"), numbering);
        }
        if (!notes.isEmpty()) {
            sb.append(",\"footnotes\":[");
            boolean firstNote = true;
            for (Map.Entry<Integer, List<Paragraph>> n : notes.entrySet()) {
                sb.append(firstNote ? "{\"id\":" : ",{\"id\":").append(n.getKey()).append(",\"paragraphs\":");
                ModelJson.paragraphs(sb, n.getValue());
                sb.append('}');
                firstNote = false;
            }
            sb.append(']');
        }
        sb.append(",\"media\":[");
        out.write(sb.toString());
        boolean firstMedia = true;
        for (Picture.MediaRef ref : media.all()) {
            StringBuilder m = new StringBuilder(firstMedia ? "" : ",");
            new JsonOut(m).str("id", ref.name()).str("contentType", ref.contentType()).always("pixelWidth", ref.pixelWidth())
                    .always("pixelHeight", ref.pixelHeight()).key("data");
            out.write(m.toString());
            out.write('"');
            out.write(Base64.getEncoder().encodeToString(media.bytes(ref)));
            out.write("\"}");
            firstMedia = false;
        }
        out.write("]}");
        out.flush();
    }

    private static void running(StringBuilder sb, String name, List<Paragraph> paragraphs) {
        if (paragraphs != null && !paragraphs.isEmpty()) {
            ModelJson.paragraphs(sb.append(",\"").append(name).append("\":"), paragraphs);
        }
    }

    @Override
    public void close() throws IOException {
        try (media) {
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
