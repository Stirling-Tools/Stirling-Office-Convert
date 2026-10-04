package stirling.software.officeconvert.pdfa;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

record BaseFontName(String family, boolean bold, boolean italic) {

    private static final Pattern TAG = Pattern.compile("^[A-Z]{6}\\+");

    private static final Pattern STYLE = Pattern.compile(
            "(?i)[-,_ ]?(bold|semibold|demibold|demi|heavy|black|italic|oblique|ital|regular|roman|book|medium|normal|light|it|bd|bi|bdit)$");

    private static final Map<String, String> STANDARD = Map.ofEntries(
            Map.entry("helvetica", "Helvetica"), Map.entry("times", "Times"), Map.entry("courier", "Courier"),
            Map.entry("symbol", "Symbol"), Map.entry("zapfdingbats", "ZapfDingbats"),
            Map.entry("arial", "Arial"), Map.entry("arialmt", "Arial"), Map.entry("timesnewroman", "Times New Roman"),
            Map.entry("timesnewromanps", "Times New Roman"), Map.entry("timesnewromanpsmt", "Times New Roman"),
            Map.entry("couriernew", "Courier New"), Map.entry("couriernewps", "Courier New"),
            Map.entry("couriernewpsmt", "Courier New"));

    static BaseFontName parse(String baseFont, int flags, float weight) {
        String n = baseFont == null ? "" : TAG.matcher(baseFont.strip()).replaceFirst("").replaceAll("(PS)?MT$", "");
        boolean bold = weight >= 600 || (flags & (1 << 18)) != 0;
        boolean italic = (flags & (1 << 6)) != 0;
        for (int guard = 0; guard < 4; guard++) {
            var m = STYLE.matcher(n);
            if (!m.find() || m.start() == 0) {
                break;
            }
            String s = m.group(1).toLowerCase(Locale.ROOT);
            if (s.contains("bold") || s.startsWith("demi") || s.equals("heavy") || s.equals("black") || s.equals("bd")
                    || s.equals("bi") || s.equals("bdit")) {
                bold = true;
            }
            if (s.contains("ital") || s.equals("oblique") || s.equals("it") || s.equals("bi") || s.equals("bdit")) {
                italic = true;
            }
            n = n.substring(0, m.start());
        }
        n = n.replaceAll("[,-].*$", "");
        String key = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String family = STANDARD.get(key);
        if (family == null) {
            String trimmed = n.replaceAll("(PSMT|MT|PS)$", "");
            family = trimmed.isEmpty() ? n : spaced(trimmed);
        }
        return new BaseFontName(family.isEmpty() ? "Helvetica" : family, bold, italic);
    }

    private static String spaced(String s) {
        if (s.contains(" ")) {
            return s;
        }
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
