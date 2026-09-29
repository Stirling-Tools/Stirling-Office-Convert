package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;

final class ValueFormatter {

    private final DataFormatter formatter = new DataFormatter(Locale.US);

    private final ExcelColors colors;

    private final boolean date1904;

    private final SharedStrings strings;

    private final SystemDates dates;

    ValueFormatter(ExcelColors colors, boolean date1904, SharedStrings strings) {
        this(colors, date1904, strings, SystemDates.US);
    }

    ValueFormatter(ExcelColors colors, boolean date1904, SharedStrings strings, SystemDates dates) {
        this.colors = colors;
        this.date1904 = date1904;
        this.strings = strings == null ? SharedStrings.NONE : strings;
        this.dates = dates == null ? SystemDates.US : dates;
        formatter.setUseCachedValuesForFormulaCells(true);
    }

    SystemDates dates() {
        return dates;
    }

    CellText display(RawRow.Cell cell, CellFormat format) {
        String type = cell.type() == null ? "n" : cell.type();
        String v = cell.value();
        FontSpec font = format.font();
        try {
            switch (type) {
                case "s" -> {
                    if (v == null) {
                        return null;
                    }
                    return text(strings.get(Integer.parseInt(v.trim())).runs(font, colors), format);
                }
                case "inlineStr" -> {
                    RichText rt = cell.inline();
                    if (rt == null) {
                        return v == null ? null : text(List.of(new TextRun(RichText.unescape(v), font)), format);
                    }
                    return text(rt.runs(font, colors), format);
                }
                case "str" -> {
                    return v == null ? null : text(List.of(new TextRun(RichText.unescape(v), font)), format);
                }
                case "b" -> {
                    if (v == null) {
                        return null;
                    }
                    boolean b = v.trim().equals("1") || v.trim().equalsIgnoreCase("true");
                    return new CellText(CellText.Kind.BOOLEAN, List.of(new TextRun(b ? "TRUE" : "FALSE", font)), null,
                            false, 0);
                }
                case "e" -> {
                    return v == null ? null
                            : new CellText(CellText.Kind.ERROR, List.of(new TextRun(v, font)), null, false, 0);
                }
                case "d" -> {
                    if (v == null) {
                        return null;
                    }
                    java.time.LocalDateTime when = v.contains("T") ? java.time.LocalDateTime.parse(v.trim())
                            : java.time.LocalDate.parse(v.trim()).atStartOfDay();
                    return number(DateUtil.getExcelDate(when, date1904), format);
                }
                default -> {
                    if (v == null || v.isBlank()) {
                        return null;
                    }
                    return number(Double.parseDouble(v.trim()), format);
                }
            }
        } catch (RuntimeException e) {
            return v == null || v.isEmpty() ? null
                    : new CellText(CellText.Kind.TEXT, List.of(new TextRun(v, font)), null, false, 0);
        }
    }

    private CellText text(List<TextRun> runs, CellFormat format) {
        String section = FormatCode.textSection(format.formatString());
        Color color = null;
        if (section != null && !section.equals("@")) {
            color = FormatCode.color(section);
            StringBuilder all = new StringBuilder();
            for (TextRun r : runs) {
                all.append(r.text());
            }
            runs = List.of(new TextRun(FormatCode.applyText(section, all.toString()), format.font()));
        }
        return new CellText(CellText.Kind.TEXT, runs, color, false, 0);
    }

    CellText number(double value, CellFormat format) {
        FontSpec font = format.font();
        String fmt = dates.builtin(format.formatIndex(), format.formatString());
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new CellText(CellText.Kind.ERROR, List.of(new TextRun("#NUM!", font)), null, false, value);
        }
        if (FormatCode.isGeneral(fmt)) {
            return new CellText(CellText.Kind.NUMBER, List.of(new TextRun(general(value, 11), font)), null, true, value);
        }
        int index = FormatCode.numberSectionIndex(fmt, value);
        String section = FormatCode.sections(fmt).get(index);
        Color color = FormatCode.color(section);
        boolean generalSection = FormatCode.isGeneral(section.replaceAll("\\[[^\\]]*\\]", ""));
        String text;
        try {
            if (ExcelFormat.isDate(section)) {
                text = ExcelFormat.format(value, section, true, date1904);
                if (text == null) {
                    text = "#".repeat(8);
                }
            } else if (generalSection) {
                text = general(FormatCode.sections(fmt).size() > 1 ? Math.abs(value) : value, 11);
            } else {
                text = ExcelFormat.format(value, section, index != 1, date1904);
                if (text == null) {
                    text = formatter.formatRawCellContents(value, format.formatIndex(), FormatCode.withMarkers(fmt),
                            date1904);
                    if (text.indexOf('/') < 0) {
                        text = text + blankFraction(section);
                    }
                }
            }
        } catch (RuntimeException e) {
            text = general(value, 11);
        }
        return new CellText(CellText.Kind.NUMBER, List.of(new TextRun(text, font)), color, generalSection, value);
    }

    // Excel keeps a fraction that rounds to nothing as blank space as wide as its placeholders
    static String blankFraction(String section) {
        String plain = section.replaceAll("\\[[^\\]]*\\]|\"[^\"]*\"", "");
        int slash = plain.indexOf('/');
        if (slash < 1 || !placeholder(plain.charAt(slash - 1))) {
            return "";
        }
        int start = slash;
        while (start > 0 && placeholder(plain.charAt(start - 1))) {
            start--;
        }
        while (start > 0 && plain.charAt(start - 1) == ' ') {
            start--;
        }
        int end = slash + 1;
        while (end < plain.length() && (placeholder(plain.charAt(end)) || Character.isDigit(plain.charAt(end)))) {
            end++;
        }
        StringBuilder b = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = plain.charAt(i);
            b.append(c == ' ' ? ' ' : (char) (FormatCode.SPACE_BASE + (placeholder(c) ? '0' : c)));
        }
        return b.toString();
    }

    private static boolean placeholder(char c) {
        return c == '?' || c == '#' || c == '0';
    }

    static String general(double value, int maxChars) {
        if (value == 0) {
            return "0";
        }
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        int room = Math.max(1, maxChars - sign.length());
        if (abs >= 1e11 || abs < 1e-9) {
            return sign + scientific(abs, room);
        }
        if (Math.rint(abs) == abs && abs < 1e11) {
            String s = new BigDecimal(abs).toPlainString();
            return s.length() <= room ? sign + s : sign + scientific(abs, room);
        }
        int intDigits = abs < 1 ? 1 : (int) Math.floor(Math.log10(abs)) + 1;
        int decimals = Math.max(0, room - intDigits - 1);
        BigDecimal bd = new BigDecimal(abs);
        String best = null;
        for (int d = decimals; d >= 0; d--) {
            BigDecimal r = bd.setScale(d, RoundingMode.HALF_UP);
            String s = r.stripTrailingZeros().toPlainString();
            if (s.length() <= room) {
                best = s;
                break;
            }
        }
        if (best == null) {
            return sign + scientific(abs, room);
        }
        if (best.equals("0") && abs > 0) {
            return sign + scientific(abs, room);
        }
        return sign + best;
    }

    static String scientific(double abs, int room) {
        int exp = (int) Math.floor(Math.log10(abs));
        double mant = abs / Math.pow(10, exp);
        String e = (exp < 0 ? "E-" : "E+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
        int digits = Math.max(0, Math.min(5, room - e.length() - 2));
        BigDecimal m = new BigDecimal(mant).round(new MathContext(digits + 1, RoundingMode.HALF_UP));
        if (m.compareTo(BigDecimal.TEN) >= 0) {
            exp++;
            e = (exp < 0 ? "E-" : "E+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
            m = m.divide(BigDecimal.TEN);
        }
        String ms = m.setScale(Math.min(digits, Math.max(0, m.scale())), RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
        return ms + e;
    }
}
