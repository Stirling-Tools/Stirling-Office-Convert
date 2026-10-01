package stirling.software.officeconvert.topdf.rtf;

final class Shading {

    int background = -1;

    int foreground = -1;

    int percent;

    Shading copy() {
        Shading s = new Shading();
        s.set(this);
        return s;
    }

    void set(Shading o) {
        background = o.background;
        foreground = o.foreground;
        percent = o.percent;
    }

    void clear() {
        background = -1;
        foreground = -1;
        percent = 0;
    }

    String fill(ColorTable colors) {
        int bg = background >= 0 ? colors.rgb(background, 0xFFFFFF) : -1;
        int p = Math.max(0, Math.min(10_000, percent));
        if (p == 0) {
            return bg < 0 ? null : hex(bg);
        }
        int fg = foreground >= 0 ? colors.rgb(foreground, 0) : 0;
        int base = bg < 0 ? 0xFFFFFF : bg;
        int r = mix(fg >> 16 & 0xFF, base >> 16 & 0xFF, p);
        int g = mix(fg >> 8 & 0xFF, base >> 8 & 0xFF, p);
        int b = mix(fg & 0xFF, base & 0xFF, p);
        return hex(r << 16 | g << 8 | b);
    }

    String xml(ColorTable colors) {
        String fill = fill(colors);
        return fill == null ? "" : "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + fill + "\"/>";
    }

    private static int mix(int fg, int bg, int p) {
        return (fg * p + bg * (10_000 - p) + 5_000) / 10_000;
    }

    static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}
