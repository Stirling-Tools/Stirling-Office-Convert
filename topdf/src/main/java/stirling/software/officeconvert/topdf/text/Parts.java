package stirling.software.officeconvert.topdf.text;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Parts {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    static final String RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final String PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships";

    private Parts() {}

    static void put(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((HEAD + xml).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static Writer open(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Writer w = new BufferedWriter(new OutputStreamWriter(keepOpen(zip), StandardCharsets.UTF_8), 1 << 16);
        w.write(HEAD);
        return w;
    }

    static void escape(StringBuilder out, int cp) {
        switch (cp) {
            case '&' -> out.append("&amp;");
            case '<' -> out.append("&lt;");
            case '>' -> out.append("&gt;");
            case '"' -> out.append("&quot;");
            default -> {
                if (Character.isBmpCodePoint(cp)) {
                    out.append((char) cp);
                } else {
                    out.append(Character.highSurrogate(cp)).append(Character.lowSurrogate(cp));
                }
            }
        }
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\t' || TextScanner.visible(cp)) {
                escape(b, cp);
            }
        }
        return b.toString();
    }

    static OutputStream keepOpen(OutputStream out) {
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
