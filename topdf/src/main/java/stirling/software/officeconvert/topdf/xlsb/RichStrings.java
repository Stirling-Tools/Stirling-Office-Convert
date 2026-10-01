package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;

import stirling.software.officeconvert.topdf.xls.Parts;
import stirling.software.officeconvert.topdf.xls.Xml;

/** Rich strings (RichStr, [MS-XLSB] 2.5.125) as SpreadsheetML string items, and sharedStrings.bin as a whole. */
final class RichStrings {

    private static final int MAX_RUNS = 4096;

    private RichStrings() {}

    /** The content of an {@code <si>} or {@code <is>}: plain text, or runs with the font each one starts. */
    static String item(Data d, boolean rich, Styles styles) {
        int flags = rich ? d.u8() : 0;
        String text = d.string();
        if ((flags & 0x01) == 0 || d.remaining() < 4) {
            return t(text);
        }
        int count = d.i32();
        int n = Math.max(0, Math.min(count, Math.min(MAX_RUNS, d.remaining() / 4)));
        int[] at = new int[n];
        int[] font = new int[n];
        for (int i = 0; i < n; i++) {
            at[i] = d.u16();
            font[i] = d.u16();
        }
        if (n == 0) {
            return t(text);
        }
        StringBuilder b = new StringBuilder();
        if (at[0] > 0) {
            b.append("<r>").append(t(text.substring(0, Math.min(at[0], text.length())))).append("</r>");
        }
        for (int i = 0; i < n; i++) {
            int from = Math.min(at[i], text.length());
            int to = i + 1 < n ? Math.min(Math.max(at[i + 1], from), text.length()) : text.length();
            if (to <= from) {
                continue;
            }
            b.append("<r>").append(styles.runProperties(font[i])).append(t(text.substring(from, to))).append("</r>");
        }
        return b.isEmpty() ? t(text) : b.toString();
    }

    private static String t(String s) {
        return "<t xml:space=\"preserve\">" + Xml.text(s) + "</t>";
    }

    static void write(InputStream in, Styles styles, Parts parts, String name) throws IOException {
        try (Parts.Part p = parts.open(name)) {
            p.write(Xml.HEAD + "<sst xmlns=\"" + Xml.MAIN + "\">");
            if (in != null) {
                Records r = new Records(in);
                while (r.next()) {
                    if (r.type() == Ids.SI) {
                        p.write("<si>" + item(r.data(), true, styles) + "</si>");
                        if (p.full()) {
                            break;
                        }
                    }
                }
            }
            p.write("</sst>");
        }
    }
}
