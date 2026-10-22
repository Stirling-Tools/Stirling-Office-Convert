package stirling.software.officeconvert.markdown;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.sink.ListLabels;

final class MarkdownBlocks {

    interface Pictures {
        String uri(Picture picture) throws IOException;
    }

    static final char MARK = '\0';

    private static final Pattern LINE_START = Pattern.compile("^(\\s*)[#>+=-]");

    private static final Pattern NUMBERED = Pattern.compile("^(\\s*\\d{1,9})[.)](\\s|$)");

    private static final Pattern MONO = Pattern.compile("(?i).*(mono|courier|consol|menlo|code).*");

    private static final String BULLET = String.valueOf((char) 0x2022);

    private final Pictures pictures;
    private final List<Integer> footnotes = new ArrayList<>();
    private boolean lastList;
    private boolean first = true;

    MarkdownBlocks(Pictures pictures) {
        this.pictures = pictures;
    }

    List<Integer> footnotes() {
        return footnotes;
    }

    void paragraph(StringBuilder sb, Paragraph p) throws IOException {
        List<Inline> before = new ArrayList<>();
        List<Inline> after = new ArrayList<>();
        for (Inline in : p.inlines) {
            if (isFloat(in)) {
                (below(in, p) ? after : before).add(in);
            }
        }
        floats(sb, before);
        int heading = heading(p.style);
        String indent = p.list == null ? "" : "    ".repeat(Math.max(0, Math.min(8, p.list.level())));
        String text = inline(p, heading > 0 ? " " : "  \n" + indent + (p.list == null ? "" : "  "), false, heading > 0);
        if (!text.isBlank()) {
            if (heading > 0) {
                block(sb, "#".repeat(heading) + " " + text.strip(), false);
            } else if (p.list != null) {
                block(sb, indent + MARK + "L" + p.list.numId() + ":" + p.list.level() + MARK + " " + text.strip(), true);
            } else {
                block(sb, startSafe(text.strip()), false);
            }
        }
        floats(sb, after);
    }

    void table(StringBuilder sb, Table t) throws IOException {
        int columns = 0;
        for (Table.Row row : t.rows) {
            int n = 0;
            for (Table.Cell c : row.cells) {
                n += Math.max(1, c.gridSpan);
            }
            columns = Math.max(columns, n);
        }
        if (columns == 0 || t.rows.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            List<String> cells = new ArrayList<>();
            for (Table.Cell c : row.cells) {
                cells.add(c.vMerge == 2 ? "" : cell(c, r == 0));
                for (int s = 1; s < c.gridSpan; s++) {
                    cells.add("");
                }
            }
            while (cells.size() < columns) {
                cells.add("");
            }
            out.append('|');
            for (String c : cells) {
                out.append(' ').append(c.isEmpty() ? " " : c).append(" |");
            }
            out.append('\n');
            if (r == 0) {
                out.append('|');
                for (int i = 0; i < columns; i++) {
                    out.append(" --- |");
                }
                out.append('\n');
            }
        }
        block(sb, out.toString().stripTrailing(), false);
    }

    private String cell(Table.Cell c, boolean header) throws IOException {
        List<String> parts = new ArrayList<>();
        for (Paragraph p : c.paragraphs) {
            for (Inline in : p.inlines) {
                if (in instanceof Inline.Image im && pictures != null) {
                    String img = image(im.picture());
                    if (img != null) {
                        parts.add(img);
                    }
                }
            }
            String s = inline(p, "<br>", true, header).strip();
            if (!s.isEmpty()) {
                if (p.list != null) {
                    s = MARK + "L" + p.list.numId() + ":" + p.list.level() + MARK + " " + s;
                }
                parts.add(s);
            }
        }
        return String.join("<br>", parts).replace("\n", " ");
    }

    void note(StringBuilder sb, int id, List<Paragraph> paragraphs) throws IOException {
        List<String> parts = new ArrayList<>();
        for (Paragraph p : paragraphs) {
            String s = inline(p, " ", false, false).strip();
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        if (!parts.isEmpty()) {
            block(sb, "[^" + id + "]: " + String.join("\n    ", parts), false);
        }
    }

    private void floats(StringBuilder sb, List<Inline> inlines) throws IOException {
        for (Inline in : inlines) {
            if (in instanceof Inline.Image im) {
                String img = image(im.picture());
                if (img != null) {
                    block(sb, img, false);
                }
            } else if (in instanceof Inline.TextBox box) {
                for (Paragraph bp : box.paragraphs()) {
                    paragraph(sb, bp);
                }
            }
        }
    }

    private String image(Picture pic) throws IOException {
        if (pictures == null) {
            return null;
        }
        String uri = pictures.uri(pic);
        if (uri == null) {
            return null;
        }
        String alt = pic.description == null ? "" : pic.description.replaceAll("\\s+", " ").strip();
        return "![" + escape(alt, true) + "](" + uri + ")";
    }

    private void block(StringBuilder sb, String text, boolean list) {
        if (!first) {
            sb.append(list && lastList ? "\n" : "\n\n");
        }
        sb.append(text);
        first = false;
        lastList = list;
    }

    private String inline(Paragraph p, String lineBreak, boolean inTable, boolean plainWeight) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean join = p.align == Paragraph.Align.JUSTIFY;
        List<Inline> list = p.inlines;
        for (int i = 0; i < list.size(); i++) {
            Inline in = list.get(i);
            switch (in) {
                case Inline.Text t -> {
                    StringBuilder text = new StringBuilder(clean(t.text()));
                    while (i + 1 < list.size() && list.get(i + 1) instanceof Inline.Text n
                            && Objects.equals(n.style(), t.style()) && Objects.equals(n.link(), t.link())
                            && n.anchorPage() == t.anchorPage()) {
                        text.append(clean(n.text()));
                        i++;
                    }
                    run(sb, text.toString(), t.style(), Links.target(t), inTable, plainWeight);
                }
                case Inline.Tab tab -> sb.append(' ');
                case Inline.Break br -> sb.append(join ? (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-' ? "" : " ")
                        : lineBreak);
                case Inline.Image im -> {
                    if (!isFloat(in) && !inTable) {
                        String img = image(im.picture());
                        if (img != null) {
                            sb.append(img);
                        }
                    }
                }
                case Inline.FootnoteRef ref -> {
                    footnotes.add(ref.id());
                    sb.append("[^").append(ref.id()).append(']');
                }
                default -> { }
            }
        }
        return sb.toString();
    }

    private static void run(StringBuilder sb, String text, RunStyle s, String href, boolean inTable,
            boolean plainWeight) {
        if (text.isBlank()) {
            sb.append(text.isEmpty() ? "" : " ");
            return;
        }
        int lead = 0;
        while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) {
            lead++;
        }
        int trail = text.length();
        while (trail > lead && Character.isWhitespace(text.charAt(trail - 1))) {
            trail--;
        }
        String core = text.substring(lead, trail);
        String body;
        boolean code = s != null && s.font() != null && MONO.matcher(s.font()).matches();
        if (code) {
            String fence = core.contains("`") ? "``" : "`";
            body = fence + (core.startsWith("`") ? " " : "") + core + (core.endsWith("`") ? " " : "") + fence;
        } else {
            body = escape(core, inTable);
            if (s != null) {
                if (s.strike()) {
                    body = "~~" + body + "~~";
                }
                if (s.italic()) {
                    body = "*" + body + "*";
                }
                if (s.bold() && !plainWeight) {
                    body = "**" + body + "**";
                }
                if (s.vertAlign() > 0) {
                    body = "<sup>" + body + "</sup>";
                } else if (s.vertAlign() < 0) {
                    body = "<sub>" + body + "</sub>";
                }
            }
        }
        if (href != null && !href.startsWith("#")) {
            body = "[" + body + "](" + href.replace("(", "%28").replace(")", "%29").replace(" ", "%20") + ")";
        }
        sb.append(lead > 0 ? " " : "").append(body).append(trail < text.length() ? " " : "");
    }

    static String escape(String s, boolean inTable) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\', '*', '_', '`', '[', ']' -> sb.append('\\').append(c);
                case '<' -> sb.append("&lt;");
                case '|' -> sb.append(inTable ? "\\|" : "|");
                case '~' -> sb.append(i + 1 < s.length() && s.charAt(i + 1) == '~' ? "\\~" : "~");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String startSafe(String text) {
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (String line : text.split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            Matcher numbered = NUMBERED.matcher(line);
            Matcher m = LINE_START.matcher(line);
            if (numbered.find()) {
                out.append(numbered.group(1)).append('\\').append(line.substring(numbered.end(1)));
            } else if (m.find()) {
                out.append(m.group(1)).append('\\').append(line.substring(m.end(1)));
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '­' && c != '\0' && c != '\r' && c != '￾' && c != '￿') {
                sb.append(c == '\t' ? ' ' : c);
            }
        }
        return sb.toString();
    }

    static int heading(String style) {
        if (style != null && style.matches("Heading[1-6]")) {
            return style.charAt(7) - '0';
        }
        return "Title".equals(style) ? 1 : 0;
    }

    private static boolean isFloat(Inline in) {
        return in instanceof Inline.TextBox || in instanceof Inline.Image im && !im.picture().fromParagraph;
    }

    private static boolean below(Inline in, Paragraph p) {
        if (Float.isNaN(p.sourceBottom)) {
            return false;
        }
        if (in instanceof Inline.TextBox t) {
            return t.y() >= p.sourceBottom;
        }
        return in instanceof Inline.Image im && im.picture().y >= p.sourceBottom;
    }

    static String label(ListLabels labels, String key) {
        if (!key.startsWith("L")) {
            return "";
        }
        int colon = key.indexOf(':');
        try {
            String label = labels.next(Integer.parseInt(key.substring(1, colon)), Integer.parseInt(key.substring(colon + 1)))
                    .strip();
            if (label.isEmpty() || label.equals(BULLET) || label.chars().allMatch(c -> c >= 0xE000 && c <= 0xF8FF)
                    || label.length() == 1 && (!Character.isLetterOrDigit(label.charAt(0)) || label.equals("o"))) {
                return "-";
            }
            if (label.matches("\\d{1,9}[.)]")) {
                return label;
            }
            if (label.matches("\\d{1,9}")) {
                return label + ".";
            }
            return "- " + escape(label, false);
        } catch (RuntimeException e) {
            return "-";
        }
    }
}
