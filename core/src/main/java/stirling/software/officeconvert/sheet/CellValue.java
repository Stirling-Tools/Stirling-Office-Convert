package stirling.software.officeconvert.sheet;

public record CellValue(Kind kind, String text, double number, NumberFormat format) {

    public enum Kind {
        TEXT,
        NUMBER,
        DATE
    }

    public CellValue {
        text = text == null ? "" : text;
        format = format == null ? NumberFormat.GENERAL : format;
    }

    public static CellValue text(String text) {
        return new CellValue(Kind.TEXT, text, 0, NumberFormat.GENERAL);
    }

    public static CellValue number(String text, double value, NumberFormat format) {
        return new CellValue(Kind.NUMBER, text, value, format);
    }

    public static CellValue date(String text, double serial, NumberFormat format) {
        return new CellValue(Kind.DATE, text, serial, format);
    }

    public boolean isText() {
        return kind == Kind.TEXT;
    }

    public boolean isEmpty() {
        return kind == Kind.TEXT && text.isEmpty();
    }
}
