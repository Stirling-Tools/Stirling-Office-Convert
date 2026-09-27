package stirling.software.officeconvert.sheet;

public record CellStyle(
        float fontSize,
        boolean bold,
        boolean italic,
        boolean underline,
        boolean strike,
        int rgb,
        int fill,
        Border top,
        Border bottom,
        Border left,
        Border right,
        HAlign horizontal,
        VAlign vertical,
        boolean wrap,
        NumberFormat format) {

    public record Border(float width, int rgb) {

        public static final Border NONE = new Border(0, -1);

        public boolean visible() {
            return width > 0;
        }
    }

    public enum HAlign {
        GENERAL,
        LEFT,
        CENTER,
        RIGHT
    }

    public enum VAlign {
        BOTTOM,
        CENTER,
        TOP
    }

    public static final CellStyle DEFAULT = new CellStyle(0, false, false, false, false, -1, -1, Border.NONE,
            Border.NONE, Border.NONE, Border.NONE, HAlign.GENERAL, VAlign.BOTTOM, false, NumberFormat.GENERAL);

    public CellStyle {
        top = top == null ? Border.NONE : top;
        bottom = bottom == null ? Border.NONE : bottom;
        left = left == null ? Border.NONE : left;
        right = right == null ? Border.NONE : right;
        format = format == null ? NumberFormat.GENERAL : format;
    }

    public CellStyle withFormat(NumberFormat f) {
        return new CellStyle(fontSize, bold, italic, underline, strike, rgb, fill, top, bottom, left, right, horizontal,
                vertical, wrap, f);
    }

    public CellStyle withWrap(boolean on) {
        return new CellStyle(fontSize, bold, italic, underline, strike, rgb, fill, top, bottom, left, right, horizontal,
                vertical, on, format);
    }

    public CellStyle withBold(boolean on) {
        return new CellStyle(fontSize, on, italic, underline, strike, rgb, fill, top, bottom, left, right, horizontal,
                vertical, wrap, format);
    }

    public CellStyle withAlignment(HAlign h, VAlign v) {
        return new CellStyle(fontSize, bold, italic, underline, strike, rgb, fill, top, bottom, left, right, h, v, wrap,
                format);
    }

    public CellStyle frameOnly() {
        return new CellStyle(fontSize, false, false, false, false, -1, fill, top, bottom, left, right, HAlign.GENERAL,
                vertical, false, NumberFormat.GENERAL);
    }

    public float size() {
        return fontSize > 0 ? fontSize : 11f;
    }
}
