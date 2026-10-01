package stirling.software.officeconvert.topdf.rtf;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.memory.Admission;

/** A Rich Text Format document rewritten as the WordprocessingML package the DOCX renderer draws. Field results are
 * kept as they were saved (fields are never updated), embedded objects show only their saved picture, and nothing the
 * document links to is followed. */
public final class RtfPackage {

    /** What the rewrite left out: warnings for the result, and whether content is missing. */
    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
            + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
            + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"";

    static final long BODY_LIMIT = 400L << 20;

    private static final byte[] MAGIC = {'{', '\\', 'r', 't', 'f'};

    static final class TooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("The document is too large", null, false, false);
        }
    }

    private RtfPackage() {}

    /** Whether the file starts like an RTF document, whatever its extension. */
    public static boolean isRtf(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (InputStream in = Files.newInputStream(file)) {
            return isRtf(in.readNBytes(64));
        }
    }

    static boolean isRtf(byte[] head) {
        int i = 0;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < head.length && (head[i] == ' ' || head[i] == '\t' || head[i] == '\r' || head[i] == '\n')) {
            i++;
        }
        if (head.length - i < MAGIC.length) {
            return false;
        }
        for (int k = 0; k < MAGIC.length; k++) {
            if (head[i + k] != MAGIC[k]) {
                return false;
            }
        }
        return true;
    }

    /** The heap rewriting an RTF file of this many bytes may need, for the shared memory gate. */
    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 3;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(InputStream in, OutputStream out) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        ZipOutputStream zip = new ZipOutputStream(keepOpen(out));
        zip.setLevel(Deflater.BEST_SPEED);
        Doc doc = new Doc();
        PropsXml props = new PropsXml(doc);
        zip.putNextEntry(new ZipEntry("word/document.xml"));
        Writer w = new Head(new BufferedWriter(new OutputStreamWriter(keepOpen(zip), StandardCharsets.UTF_8), 1 << 16),
                () -> Xml.HEAD + "<w:document " + NS + ">" + (doc.background < 0 ? ""
                        : "<w:background w:color=\"" + Shading.hex(doc.background) + "\"/>") + "<w:body>");
        Rels bodyRels = new Rels("rId");
        Story body = new Story(bodyRels, doc.colors, w, BODY_LIMIT);
        Content content = new Content(doc, props, body);
        RtfReader reader = new RtfReader(new BufferedInputStream(in, 1 << 16), doc, content);
        reader.read();
        w.write(content.finalSectPr());
        w.write("</w:body></w:document>");
        w.flush();
        zip.closeEntry();
        PackageParts.write(zip, doc, props, content, bodyRels);
        zip.finish();
        zip.flush();
        List<String> warnings = new ArrayList<>(reader.warnings);
        if (doc.media.lost) {
            warnings.add("Some pictures were left out: the document holds too many or too large pictures");
        }
        return new Outcome(warnings, reader.lost || doc.media.lost);
    }

    private static final class Head extends FilterWriter {
        private Supplier<String> head;

        Head(Writer out, Supplier<String> head) {
            super(out);
            this.head = head;
        }

        private void start() throws IOException {
            if (head != null) {
                String h = head.get();
                head = null;
                out.write(h);
            }
        }

        @Override
        public void write(int c) throws IOException {
            start();
            out.write(c);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            start();
            out.write(cbuf, off, len);
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            start();
            out.write(str, off, len);
        }
    }

    private static OutputStream keepOpen(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        };
    }
}
