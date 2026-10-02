package stirling.software.officeconvert.ods;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.sheet.CellStyle;
import stirling.software.officeconvert.sheet.CellStyle.Border;
import stirling.software.officeconvert.sheet.CellStyle.HAlign;
import stirling.software.officeconvert.sheet.CellStyle.VAlign;
import stirling.software.officeconvert.sheet.NumberFormat;
import stirling.software.officeconvert.sheet.NumberFormat.DatePart;
import stirling.software.officeconvert.sheet.SheetXml;

final class OdsStyles {

    static final String FONT = "Calibri";

    private record Page(boolean landscape, boolean a4, String header, String footer, boolean rtl) {}

    private record Key(CellStyle style, String font, boolean rtl) {}

    private final Map<String, String> columns = new LinkedHashMap<>();
    private final Map<String, String> rows = new LinkedHashMap<>();
    private final Map<Key, String> cells = new LinkedHashMap<>();
    private final Map<NumberFormat, String> numbers = new LinkedHashMap<>();
    private final Map<Page, String> pages = new LinkedHashMap<>();
    private final StringBuilder cellXml = new StringBuilder();
    private final StringBuilder numberXml = new StringBuilder();

    String column(float points) {
        String width = inches(points);
        return columns.computeIfAbsent(width, w -> "co" + (columns.size() + 1));
    }

    String row(float points) {
        String height = Float.isNaN(points) || points <= 0 ? "" : inches(Math.min(409f, points));
        return rows.computeIfAbsent(height, h -> "ro" + (rows.size() + 1));
    }

    String table(boolean landscape, boolean a4, String header, String footer, boolean rtl) {
        return pages.computeIfAbsent(new Page(landscape, a4, blank(header), blank(footer), rtl), p -> "ta" + (pages.size() + 1));
    }

    String cell(CellStyle s) {
        return cell(s, null);
    }

    String cell(CellStyle s, String text) {
        Key key = new Key(s, Scripts.cellFont(FONT, text, null), text != null && Scripts.rightToLeft(text));
        String known = cells.get(key);
        if (known != null) {
            return known;
        }
        String name = "ce" + (cells.size() + 1);
        cells.put(key, name);
        String data = s.format().kind() == NumberFormat.Kind.GENERAL ? null : number(s.format());
        cellXml.append("<style:style style:name=\"").append(name)
                .append("\" style:family=\"table-cell\" style:parent-style-name=\"Default\"");
        if (data != null) {
            cellXml.append(" style:data-style-name=\"").append(data).append('"');
        }
        cellXml.append("><style:table-cell-properties");
        if (s.fill() >= 0) {
            cellXml.append(" fo:background-color=\"").append(hex(s.fill())).append('"');
        }
        border(cellXml, "top", s.top());
        border(cellXml, "bottom", s.bottom());
        border(cellXml, "left", s.left());
        border(cellXml, "right", s.right());
        if (s.wrap()) {
            cellXml.append(" fo:wrap-option=\"wrap\"");
        }
        cellXml.append(" style:vertical-align=\"")
                .append(s.vertical() == VAlign.TOP ? "top" : s.vertical() == VAlign.CENTER ? "middle" : "bottom")
                .append("\"/>");
        if (s.horizontal() != HAlign.GENERAL || key.rtl()) {
            cellXml.append("<style:paragraph-properties");
            if (s.horizontal() != HAlign.GENERAL) {
                String left = key.rtl() ? "end" : "start";
                String right = key.rtl() ? "start" : "end";
                cellXml.append(" fo:text-align=\"")
                        .append(s.horizontal() == HAlign.LEFT ? left : s.horizontal() == HAlign.CENTER ? "center" : right).append('"');
            }
            if (key.rtl()) {
                cellXml.append(" style:writing-mode=\"rl-tb\"");
            }
            cellXml.append("/>");
        }
        cellXml.append("<style:text-properties");
        if (key.font().equals(FONT)) {
            cellXml.append(" style:font-name=\"").append(FONT).append('"');
        } else {
            String f = key.font().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
            cellXml.append(" fo:font-family=\"").append(f).append("\" style:font-family-asian=\"").append(f)
                    .append("\" style:font-family-complex=\"").append(f).append('"');
        }
        cellXml.append(" fo:font-size=\"").append(points(Math.round(s.size() * 2f) / 2f)).append('"');
        if (s.bold()) {
            cellXml.append(" fo:font-weight=\"bold\"");
        }
        if (s.italic()) {
            cellXml.append(" fo:font-style=\"italic\"");
        }
        if (s.underline()) {
            cellXml.append(" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\""
                    + " style:text-underline-color=\"font-color\"");
        }
        if (s.strike()) {
            cellXml.append(" style:text-line-through-style=\"solid\"");
        }
        if (s.rgb() >= 0) {
            cellXml.append(" fo:color=\"").append(hex(s.rgb())).append('"');
        }
        cellXml.append("/></style:style>");
        return name;
    }

    private String number(NumberFormat f) {
        String known = numbers.get(f);
        if (known != null) {
            return known;
        }
        String name = "N" + (numbers.size() + 1);
        numbers.put(f, name);
        switch (f.kind()) {
            case DATE -> date(name, f);
            case PERCENT, NUMBER -> numeric(name, f);
            default -> { }
        }
        return name;
    }

    private void numeric(String name, NumberFormat f) {
        String element = f.kind() == NumberFormat.Kind.PERCENT ? "number:percentage-style" : "number:number-style";
        if (f.parentheses()) {
            part(name + "P0", element, f, "", "", null);
            part(name, element, f, "(", ")", "<style:map style:condition=\"value()&gt;=0\" style:apply-style-name=\""
                    + name + "P0\"/>");
        } else if (f.plus()) {
            part(name + "P0", element, f, "+", "", null);
            part(name + "P1", element, f, "-", "", null);
            part(name, element, f, "", "", "<style:map style:condition=\"value()&gt;0\" style:apply-style-name=\"" + name
                    + "P0\"/><style:map style:condition=\"value()&lt;0\" style:apply-style-name=\"" + name + "P1\"/>");
        } else {
            part(name, element, f, "", "", null);
        }
    }

    private void part(String name, String element, NumberFormat f, String open, String close, String maps) {
        numberXml.append('<').append(element).append(" style:name=\"").append(name).append("\">");
        text(numberXml, open + f.prefix());
        numberXml.append("<number:number number:decimal-places=\"").append(f.decimals())
                .append("\" number:min-decimal-places=\"").append(f.decimals())
                .append("\" number:min-integer-digits=\"1\"");
        if (f.grouping()) {
            numberXml.append(" number:grouping=\"true\"");
        }
        numberXml.append("/>");
        text(numberXml, f.suffix() + (f.kind() == NumberFormat.Kind.PERCENT ? "%" : "") + close);
        if (maps != null) {
            numberXml.append(maps);
        }
        numberXml.append("</").append(element).append('>');
    }

    private void date(String name, NumberFormat f) {
        String element = f.hasDay() ? "number:date-style" : "number:time-style";
        numberXml.append('<').append(element).append(" style:name=\"").append(name).append('"');
        if (f.monthNames() || f.date().stream().anyMatch(p -> p.field() == NumberFormat.Field.AM_PM)) {
            numberXml.append(" number:language=\"en\" number:country=\"US\"");
        }
        numberXml.append('>');
        for (DatePart p : f.date()) {
            switch (p.field()) {
                case YEAR -> numberXml.append(p.width() <= 2 ? "<number:year/>" : "<number:year number:style=\"long\"/>");
                case MONTH -> numberXml.append(switch (p.width()) {
                    case 1 -> "<number:month/>";
                    case 2 -> "<number:month number:style=\"long\"/>";
                    case 3 -> "<number:month number:textual=\"true\"/>";
                    default -> "<number:month number:textual=\"true\" number:style=\"long\"/>";
                });
                case DAY -> numberXml.append(p.width() <= 1 ? "<number:day/>" : "<number:day number:style=\"long\"/>");
                case HOUR -> numberXml.append(p.width() <= 1 ? "<number:hours/>" : "<number:hours number:style=\"long\"/>");
                case MINUTE -> numberXml.append("<number:minutes number:style=\"long\"/>");
                case SECOND -> numberXml.append("<number:seconds number:style=\"long\"/>");
                case AM_PM -> numberXml.append("<number:am-pm/>");
                case TEXT -> text(numberXml, p.literal());
            }
        }
        numberXml.append("</").append(element).append('>');
    }

    private static void text(StringBuilder sb, String s) {
        if (!s.isEmpty()) {
            sb.append("<number:text>");
            SheetXml.escape(sb, s);
            sb.append("</number:text>");
        }
    }

    String contentStyles() {
        StringBuilder sb = new StringBuilder(4096);
        columns.forEach((width, name) -> sb.append("<style:style style:name=\"").append(name)
                .append("\" style:family=\"table-column\"><style:table-column-properties fo:break-before=\"auto\""
                        + " style:column-width=\"").append(width).append("\"/></style:style>"));
        rows.forEach((height, name) -> {
            sb.append("<style:style style:name=\"").append(name).append("\" style:family=\"table-row\">")
                    .append("<style:table-row-properties fo:break-before=\"auto\"");
            if (height.isEmpty()) {
                sb.append(" style:row-height=\"0.2083in\" style:use-optimal-row-height=\"true\"");
            } else {
                sb.append(" style:row-height=\"").append(height).append("\" style:use-optimal-row-height=\"false\"");
            }
            sb.append("/></style:style>");
        });
        pages.forEach((page, name) -> sb.append("<style:style style:name=\"").append(name)
                .append("\" style:family=\"table\" style:master-page-name=\"mp").append(name.substring(2))
                .append("\"><style:table-properties table:display=\"true\" style:writing-mode=\"")
                .append(page.rtl() ? "rl-tb" : "lr-tb").append("\"/></style:style>"));
        sb.append(numberXml).append(cellXml);
        return sb.toString();
    }

    String pageLayouts() {
        StringBuilder sb = new StringBuilder();
        pages.forEach((page, name) -> {
            float w = page.a4() ? 8.27f : 8.5f;
            float h = page.a4() ? 11.69f : 11f;
            sb.append("<style:page-layout style:name=\"pm").append(name.substring(2)).append("\">")
                    .append("<style:page-layout-properties fo:page-width=\"")
                    .append(format(page.landscape() ? h : w)).append("in\" fo:page-height=\"")
                    .append(format(page.landscape() ? w : h)).append("in\" style:print-orientation=\"")
                    .append(page.landscape() ? "landscape" : "portrait")
                    .append("\" fo:margin-top=\"0.5in\" fo:margin-bottom=\"0.5in\" fo:margin-left=\"0.5in\""
                            + " fo:margin-right=\"0.5in\"/>")
                    .append("<style:header-style/><style:footer-style/></style:page-layout>");
        });
        return sb.toString();
    }

    String masterPages() {
        StringBuilder sb = new StringBuilder();
        pages.forEach((page, name) -> {
            String n = name.substring(2);
            sb.append("<style:master-page style:name=\"mp").append(n).append("\" style:page-layout-name=\"pm").append(n)
                    .append("\">");
            running(sb, "header", page.header());
            running(sb, "footer", page.footer());
            sb.append("</style:master-page>");
        });
        return sb.toString();
    }

    private static void running(StringBuilder sb, String kind, String text) {
        if (text.isEmpty()) {
            sb.append("<style:").append(kind).append(" style:display=\"false\"/>");
            return;
        }
        sb.append("<style:").append(kind).append("><style:region-center>");
        for (String line : text.split("\n")) {
            sb.append("<text:p>");
            SheetXml.escape(sb, line);
            sb.append("</text:p>");
        }
        sb.append("</style:region-center></style:").append(kind).append('>');
    }

    private static void border(StringBuilder sb, String side, Border b) {
        if (!b.visible()) {
            return;
        }
        float w = b.width() > 2f ? 2.5f : b.width() > 1f ? 1.5f : 0.75f;
        sb.append(" fo:border-").append(side).append("=\"").append(points(w)).append(" solid ")
                .append(hex(Math.max(0, b.rgb()))).append('"');
    }

    private static String blank(String s) {
        return s == null ? "" : s.strip();
    }

    private static String inches(float points) {
        return format(Math.max(0.01f, points / 72f)) + "in";
    }

    private static String points(float pt) {
        return (pt == Math.rint(pt) ? Integer.toString((int) pt) : Float.toString(pt)) + "pt";
    }

    private static String format(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }
}
