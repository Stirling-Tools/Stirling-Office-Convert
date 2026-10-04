package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HeaderFooterText {

    record Context(int page, int pages, String sheet, String file, String date, String time) {}

    record Sections(List<List<TextRun>> left, List<List<TextRun>> center, List<List<TextRun>> right,
            boolean[] pictures) {

        Sections(List<List<TextRun>> left, List<List<TextRun>> center, List<List<TextRun>> right) {
            this(left, center, right, new boolean[3]);
        }

        boolean isEmpty() {
            return blank(left) && blank(center) && blank(right);
        }

        private static boolean blank(List<List<TextRun>> lines) {
            for (List<TextRun> line : lines) {
                for (TextRun r : line) {
                    if (!r.text().isBlank()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private HeaderFooterText() {}

    static Sections parse(String code, FontSpec base, Context ctx, ExcelColors colors) {
        List<List<List<TextRun>>> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<List<TextRun>> lines = new ArrayList<>();
            lines.add(new ArrayList<>());
            parts.add(lines);
        }
        if (code == null || code.isEmpty()) {
            return new Sections(parts.get(0), parts.get(1), parts.get(2));
        }
        int section = 1;
        boolean[] pictures = new boolean[3];
        FontSpec font = base;
        StringBuilder text = new StringBuilder();
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '\n' || c == '\r') {
                flush(parts.get(section), text, font);
                if (c == '\r' && i + 1 < n && code.charAt(i + 1) == '\n') {
                    i++;
                }
                parts.get(section).add(new ArrayList<>());
                continue;
            }
            if (c != '&' || i + 1 >= n) {
                text.append(c);
                continue;
            }
            char k = code.charAt(++i);
            switch (Character.toUpperCase(k)) {
                case '&' -> text.append('&');
                case 'L', 'C', 'R' -> {
                    flush(parts.get(section), text, font);
                    section = Character.toUpperCase(k) == 'L' ? 0 : Character.toUpperCase(k) == 'C' ? 1 : 2;
                    font = base;
                }
                case 'P' -> {
                    int[] adj = offset(code, i + 1);
                    text.append(ctx.page() + adj[0]);
                    i = adj[1] - 1;
                }
                case 'N' -> text.append(ctx.pages());
                case 'D' -> text.append(ctx.date());
                case 'T' -> text.append(ctx.time());
                case 'A' -> text.append(ctx.sheet());
                case 'F' -> text.append(ctx.file());
                case 'G' -> pictures[section] = true;
                case 'Z' -> {
                }
                case 'B' -> {
                    flush(parts.get(section), text, font);
                    font = font.bold(!font.bold());
                }
                case 'I' -> {
                    flush(parts.get(section), text, font);
                    font = new FontSpec(font.family(), font.size(), font.bold(), !font.italic(), font.underline(),
                            font.strike(), font.color(), font.offset());
                }
                case 'U', 'E' -> {
                    flush(parts.get(section), text, font);
                    FontSpec.Underline u = font.underline() == FontSpec.Underline.NONE
                            ? (Character.toUpperCase(k) == 'U' ? FontSpec.Underline.SINGLE : FontSpec.Underline.DOUBLE)
                            : FontSpec.Underline.NONE;
                    font = new FontSpec(font.family(), font.size(), font.bold(), font.italic(), u, font.strike(),
                            font.color(), font.offset());
                }
                case 'S' -> {
                    flush(parts.get(section), text, font);
                    font = new FontSpec(font.family(), font.size(), font.bold(), font.italic(), font.underline(),
                            !font.strike(), font.color(), font.offset());
                }
                case 'X', 'Y' -> {
                    flush(parts.get(section), text, font);
                    FontSpec.Offset o = Character.toUpperCase(k) == 'X' ? FontSpec.Offset.SUPER : FontSpec.Offset.SUB;
                    font = new FontSpec(font.family(), font.size(), font.bold(), font.italic(), font.underline(),
                            font.strike(), font.color(), font.offset() == o ? FontSpec.Offset.NONE : o);
                }
                case 'K' -> {
                    flush(parts.get(section), text, font);
                    int end = Math.min(n, i + 7);
                    String spec = code.substring(i + 1, end);
                    font = font.color(color(spec, font.color(), colors));
                    i = end - 1;
                }
                case '"' -> {
                    flush(parts.get(section), text, font);
                    int end = code.indexOf('"', i + 1);
                    if (end < 0) {
                        end = n;
                    }
                    font = fontCode(code.substring(i + 1, end), font);
                    i = end;
                }
                case 'O', 'H' -> {
                }
                default -> {
                    if (Character.isDigit(k)) {
                        int end = i;
                        while (end < n && end < i + 3 && Character.isDigit(code.charAt(end))) {
                            end++;
                        }
                        flush(parts.get(section), text, font);
                        try {
                            int size = Integer.parseInt(code.substring(i, end));
                            if (size >= 1 && size <= 409) {
                                font = font.size(size);
                            }
                        } catch (NumberFormatException ignored) {
                            font = font.size(font.size());
                        }
                        i = end - 1;
                    } else {
                        text.append('&').append(k);
                    }
                }
            }
        }
        flush(parts.get(section), text, font);
        return new Sections(parts.get(0), parts.get(1), parts.get(2), pictures);
    }

    private static int[] offset(String code, int at) {
        if (at + 1 < code.length() && (code.charAt(at) == '+' || code.charAt(at) == '-')
                && Character.isDigit(code.charAt(at + 1))) {
            int end = at + 1;
            while (end < code.length() && end < at + 6 && Character.isDigit(code.charAt(end))) {
                end++;
            }
            int v = Integer.parseInt(code.substring(at + 1, end));
            return new int[] {code.charAt(at) == '-' ? -v : v, end};
        }
        return new int[] {0, at};
    }

    private static void flush(List<List<TextRun>> lines, StringBuilder text, FontSpec font) {
        if (text.length() > 0) {
            lines.get(lines.size() - 1).add(new TextRun(text.toString(), font));
            text.setLength(0);
        }
    }

    private static FontSpec fontCode(String spec, FontSpec font) {
        String name = spec;
        String style = "";
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            name = spec.substring(0, comma).trim();
            style = spec.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
        }
        String family = name.isEmpty() || name.equals("-") ? font.family() : name;
        boolean bold = style.contains("bold") || style.contains("heavy") || style.contains("black");
        boolean italic = style.contains("italic") || style.contains("oblique");
        if (style.isEmpty() || style.equals("-")) {
            bold = font.bold();
            italic = font.italic();
        }
        return new FontSpec(family, font.size(), bold, italic, font.underline(), font.strike(), font.color(),
                font.offset());
    }

    private static Color color(String spec, Color current, ExcelColors colors) {
        if (spec.length() < 6) {
            return current;
        }
        try {
            if (spec.length() >= 6 && spec.matches("[0-9A-Fa-f]{6}")) {
                return new Color(Integer.parseInt(spec, 16));
            }
            int theme = Integer.parseInt(spec.substring(0, 2));
            char sign = spec.charAt(2);
            int amount = Integer.parseInt(spec.substring(3, 6));
            Color base = colors.themeColor(theme);
            if (base == null) {
                return current;
            }
            double tint = (sign == '-' ? -amount : amount) / 100.0;
            return tint == 0 ? base : ExcelColors.tint(base, Math.max(-1, Math.min(1, tint)));
        } catch (RuntimeException e) {
            return current;
        }
    }
}
