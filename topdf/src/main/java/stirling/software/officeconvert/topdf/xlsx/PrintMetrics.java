package stirling.software.officeconvert.topdf.xlsx;

final class PrintMetrics {

    static final double PX = 72.0 / 600;

    static final double ORIGIN = 4 * PX;

    private final FontMeasure font;

    private final double size;

    private final int screenDigit;

    private final int printerDigit;

    private final int printerRowPx;

    PrintMetrics(FontMeasure font, double size) {
        this.font = font;
        this.size = size > 0 ? size : 11;
        this.screenDigit = font.screenDigit(this.size);
        this.printerDigit = font.printerDigit(this.size);
        this.printerRowPx = font.printerLinePx(this.size);
    }

    FontMeasure font() {
        return font;
    }

    int screenDigit() {
        return screenDigit;
    }

    int printerDigit() {
        return printerDigit;
    }

    int printerDigit(double scale) {
        return scale == 1 ? printerDigit : font.printerDigit(size * scale);
    }

    // Excel prints a column at its whole screen pixels scaled by the printer's digit width over the screen's
    double columnPoints(double widthChars) {
        if (!(widthChars > 0)) {
            return 0;
        }
        long px = Math.round(screenColumnPixels(widthChars) * printerDigit / screenDigit);
        return px * PX;
    }

    double screenColumnPixels(double widthChars) {
        if (!(widthChars > 0)) {
            return 0;
        }
        double w = Math.min(widthChars, 255);
        return Math.floor((256 * w + Math.floor(128.0 / screenDigit)) / 256 * screenDigit);
    }

    double defaultColumnChars(double baseColWidth) {
        double base = baseColWidth > 0 ? baseColWidth : 8;
        int px = (int) Math.ceil((base * screenDigit + 5) / 8.0) * 8;
        return Math.floor(px * 256.0 / screenDigit) / 256;
    }

    double rowFactor(double screenDefaultRowPt) {
        double pt = screenDefaultRowPt > 0 ? screenDefaultRowPt : estimatedScreenRowPt();
        return printerRowPx / (pt * 600 / 72);
    }

    double estimatedScreenRowPt() {
        return font.screenLinePx(size) * 0.75;
    }

    double printerRowPoints() {
        return printerRowPx * PX;
    }
}
