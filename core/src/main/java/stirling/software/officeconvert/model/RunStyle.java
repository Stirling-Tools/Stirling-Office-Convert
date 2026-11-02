package stirling.software.officeconvert.model;

public record RunStyle(
        String font,
        float size,
        boolean bold,
        boolean italic,
        boolean underline,
        boolean strike,
        int rgb,
        int highlight,
        int vertAlign,
        boolean symbol,
        float spacing,
        int scale,
        boolean smallCaps,
        int border,
        float lineHeight,
        String math) {

    public RunStyle(String font, float size, boolean bold, boolean italic, boolean underline, boolean strike,
            int rgb, int highlight, int vertAlign, boolean symbol, float spacing, int scale, boolean smallCaps,
            int border, float lineHeight) {
        this(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps,
                border, lineHeight, null);
    }

    public RunStyle(String font, float size, boolean bold, boolean italic, boolean underline, boolean strike,
            int rgb, int highlight, int vertAlign, boolean symbol, float spacing, int scale, boolean smallCaps,
            int border) {
        this(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps,
                border, 0f);
    }

    public RunStyle(String font, float size, boolean bold, boolean italic, boolean underline, boolean strike,
            int rgb, int highlight, int vertAlign, boolean symbol, float spacing, int scale, boolean smallCaps) {
        this(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, -1);
    }

    public RunStyle(String font, float size, boolean bold, boolean italic, boolean underline, boolean strike,
            int rgb, int highlight, int vertAlign, boolean symbol) {
        this(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, 0f, 100, false);
    }

    public RunStyle(String font, float size, boolean bold, boolean italic, boolean underline, boolean strike,
            int rgb, int highlight, int vertAlign, boolean symbol, float spacing, int scale) {
        this(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, false);
    }

    public RunStyle withFont(String f) {
        return new RunStyle(f, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, border, lineHeight, math);
    }

    public RunStyle withSize(float s) {
        return new RunStyle(font, s, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, border, lineHeight, math);
    }

    public RunStyle withBorder(int b) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, b, lineHeight, math);
    }

    public RunStyle withVertAlign(int v) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, v, symbol, spacing, scale, smallCaps, border, lineHeight, math);
    }

    public RunStyle withLineHeight(float l) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, border, l, math);
    }

    public RunStyle withMath(String m) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale,
                smallCaps, border, lineHeight, m);
    }
}
