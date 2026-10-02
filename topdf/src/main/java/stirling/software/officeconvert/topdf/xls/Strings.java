package stirling.software.officeconvert.topdf.xls;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Font;

// The shared string table: plain strings once each, rich ones with their runs
final class Strings {

    private final HSSFWorkbook wb;

    private final Map<String, Integer> plain = new HashMap<>();

    private final List<String> items = new ArrayList<>();

    private long chars;

    Strings(HSSFWorkbook wb) {
        this.wb = wb;
    }

    int add(HSSFRichTextString s) {
        String text = s.getString();
        if (s.numFormattingRuns() == 0) {
            Integer known = plain.get(text);
            if (known != null) {
                return known;
            }
            plain.put(text, items.size());
            items.add("<si><t xml:space=\"preserve\">" + Xml.text(text) + "</t></si>");
        } else {
            items.add("<si>" + runs(s, text) + "</si>");
        }
        chars += text.length();
        return items.size() - 1;
    }

    long size() {
        return chars * 2 + items.size() * 40L;
    }

    private String runs(HSSFRichTextString s, String text) {
        StringBuilder b = new StringBuilder();
        int n = s.numFormattingRuns();
        int first = Math.min(text.length(), Math.max(0, s.getIndexOfFormattingRun(0)));
        if (first > 0) {
            b.append("<r><t xml:space=\"preserve\">").append(Xml.text(text.substring(0, first))).append("</t></r>");
        }
        for (int i = 0; i < n; i++) {
            int start = Math.min(text.length(), Math.max(0, s.getIndexOfFormattingRun(i)));
            int end = i + 1 < n ? Math.min(text.length(), Math.max(start, s.getIndexOfFormattingRun(i + 1)))
                    : text.length();
            if (end <= start) {
                continue;
            }
            b.append("<r>").append(props(StylesPart.fontAt(wb, s.getFontOfFormattingRun(i))))
                    .append("<t xml:space=\"preserve\">").append(Xml.text(text.substring(start, end)))
                    .append("</t></r>");
        }
        return b.toString();
    }

    private static String props(HSSFFont f) {
        if (f == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<rPr>");
        if (f.getBold()) {
            b.append("<b/>");
        }
        if (f.getItalic()) {
            b.append("<i/>");
        }
        if (f.getStrikeout()) {
            b.append("<strike/>");
        }
        b.append(StylesPart.color("color", f.getColor(), true));
        b.append("<sz val=\"").append(StylesPart.size(f)).append("\"/>");
        b.append("<rFont val=\"").append(Xml.attr(f.getFontName())).append("\"/>");
        String u = StylesPart.underline(f.getUnderline());
        if (u != null) {
            b.append("<u val=\"").append(u).append("\"/>");
        }
        if (f.getTypeOffset() == Font.SS_SUPER) {
            b.append("<vertAlign val=\"superscript\"/>");
        } else if (f.getTypeOffset() == Font.SS_SUB) {
            b.append("<vertAlign val=\"subscript\"/>");
        }
        return b.append("</rPr>").toString();
    }

    void write(Parts parts) throws IOException {
        try (Parts.Part p = parts.open("xl/sharedStrings.xml")) {
            p.append(Xml.HEAD).append("<sst xmlns=\"").append(Xml.MAIN).append("\" count=\"")
                    .append(Integer.toString(items.size())).append("\" uniqueCount=\"")
                    .append(Integer.toString(items.size())).append("\">");
            for (String item : items) {
                p.write(item);
            }
            p.write("</sst>");
        }
    }
}
