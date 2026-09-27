package stirling.software.officeconvert.xlsx;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.sheet.SheetXml;
import stirling.software.officeconvert.sheet.SpillBuffer;

final class SharedStrings implements AutoCloseable {

    private static final int DEDUPE_LIMIT = 100_000;

    private static final Pattern ESCAPE_LIKE = Pattern.compile("_x[0-9A-Fa-f]{4}_");

    private final Map<String, Integer> index = new HashMap<>();
    private final SpillBuffer buffer = new SpillBuffer(4L << 20);
    private final Writer out = new BufferedWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), 1 << 14);
    private final StringBuilder sb = new StringBuilder();
    private int references;
    private int entries;

    int add(String s) throws IOException {
        references++;
        Integer known = index.get(s);
        if (known != null) {
            return known;
        }
        sb.setLength(0);
        sb.append("<si><t");
        if (!s.equals(s.strip()) || s.indexOf('\n') >= 0) {
            sb.append(" xml:space=\"preserve\"");
        }
        sb.append('>');
        SheetXml.escape(sb, encode(s));
        sb.append("</t></si>");
        out.write(sb.toString());
        if (index.size() < DEDUPE_LIMIT) {
            index.put(s, entries);
        }
        return entries++;
    }

    static String encode(String s) {
        String t = ESCAPE_LIKE.matcher(s).replaceAll(r -> Matcher.quoteReplacement("_x005F" + r.group()));
        StringBuilder b = null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\t') {
                if (b == null) {
                    b = new StringBuilder(t.substring(0, i));
                }
                if (c != '\r') {
                    b.append(String.format(Locale.ROOT, "_x%04X_", (int) c));
                }
            } else if (b != null) {
                b.append(c);
            }
        }
        return b == null ? t : b.toString();
    }

    void writeTo(OutputStream zip) throws IOException {
        out.flush();
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" + references
                + "\" uniqueCount=\"" + entries + "\">";
        zip.write(head.getBytes(StandardCharsets.UTF_8));
        buffer.writeTo(zip);
        zip.write("</sst>".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        buffer.close();
    }
}
