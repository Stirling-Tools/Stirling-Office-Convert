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
        int border) {

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
        return new RunStyle(f, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, border);
    }

    public RunStyle withSize(float s) {
        return new RunStyle(font, s, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, border);
    }

    public RunStyle withBorder(int b) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, vertAlign, symbol, spacing, scale, smallCaps, b);
    }

    public RunStyle withVertAlign(int v) {
        return new RunStyle(font, size, bold, italic, underline, strike, rgb, highlight, v, symbol, spacing, scale, smallCaps, border);
    }
}
