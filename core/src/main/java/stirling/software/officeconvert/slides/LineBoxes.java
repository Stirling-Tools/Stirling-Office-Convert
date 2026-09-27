package stirling.software.officeconvert.slides;

import java.util.Map;

public final class LineBoxes {

    private static final Map<String, Float> ASCENT_SHARE = Map.ofEntries(
            Map.entry("Arial", 0.810f),
            Map.entry("Arial Narrow", 0.814f),
            Map.entry("Arial Black", 0.781f),
            Map.entry("Times New Roman", 0.805f),
            Map.entry("Courier New", 0.735f),
            Map.entry("Calibri", 0.780f),
            Map.entry("Calibri Light", 0.780f),
            Map.entry("Candara", 0.780f),
            Map.entry("Constantia", 0.780f),
            Map.entry("Corbel", 0.780f),
            Map.entry("Cambria", 0.811f),
            Map.entry("Consolas", 0.786f),
            Map.entry("Georgia", 0.807f),
            Map.entry("Verdana", 0.827f),
            Map.entry("Tahoma", 0.829f),
            Map.entry("Garamond", 0.766f),
            Map.entry("Trebuchet MS", 0.809f),
            Map.entry("Segoe UI", 0.811f),
            Map.entry("Segoe UI Light", 0.811f),
            Map.entry("Segoe UI Semibold", 0.811f),
            Map.entry("Segoe UI Symbol", 0.811f),
            Map.entry("Century Gothic", 0.815f),
            Map.entry("Palatino Linotype", 0.778f),
            Map.entry("Book Antiqua", 0.773f),
            Map.entry("Comic Sans MS", 0.791f),
            Map.entry("Lucida Sans Unicode", 0.714f),
            Map.entry("Lucida Console", 0.789f),
            Map.entry("Franklin Gothic Medium", 0.808f),
            Map.entry("Gill Sans MT", 0.801f),
            Map.entry("Impact", 0.827f),
            Map.entry("MS Gothic", 0.859f),
            Map.entry("MS PGothic", 0.859f),
            Map.entry("SimSun", 0.859f),
            Map.entry("SimHei", 0.859f),
            Map.entry("Microsoft YaHei", 0.802f),
            Map.entry("Yu Gothic", 0.765f),
            Map.entry("Malgun Gothic", 0.818f));

    private static final float DEFAULT_SHARE = 0.8f;

    private LineBoxes() {}

    public static float baseline(float height, float size, String font) {
        float single = single(size);
        if (height > single) {
            return 0.75f * height;
        }
        float descent = single * (1 - ascentShare(font));
        return Math.max(0.75f * height, height - descent);
    }

    public static float settle(float height, float size) {
        float single = single(size);
        return Math.abs(height / single - 1) < 0.006f ? single : height;
    }

    public static float single(float size) {
        return 1.2f * size;
    }

    static float ascentShare(String font) {
        return font == null ? DEFAULT_SHARE : ASCENT_SHARE.getOrDefault(font, DEFAULT_SHARE);
    }
}
