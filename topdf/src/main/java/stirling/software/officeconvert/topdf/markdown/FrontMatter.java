package stirling.software.officeconvert.topdf.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

record FrontMatter(String title, String body) {

    private static final int MAX_LINES = 2000;

    private static final Pattern YAML_KEY = Pattern.compile("[A-Za-z0-9_.-]+[ \\t]*:(?:[ \\t].*)?");

    private static final Pattern TOML_KEY = Pattern.compile("(?:[A-Za-z0-9_.\"'-]+[ \\t]*=.*|\\[.*\\]|[\\]}].*)");

    private static final Pattern YAML_ITEM = Pattern.compile("-(?:[ \\t].*)?");

    private static final Pattern YAML_TITLE = Pattern.compile("title[ \\t]*:[ \\t]*(.*)");

    private static final Pattern TOML_TITLE = Pattern.compile("title[ \\t]*=[ \\t]*(.*)");

    static FrontMatter of(String text) {
        String fence = text.startsWith("---") ? "---" : text.startsWith("+++") ? "+++" : null;
        if (fence == null) {
            return new FrontMatter(null, text);
        }
        int lineEnd = lineEnd(text, 0);
        if (!text.substring(fence.length(), lineEnd).isBlank()) {
            return new FrontMatter(null, text);
        }
        boolean yaml = fence.equals("---");
        String title = null;
        boolean keyed = false;
        int start = next(text, lineEnd);
        for (int at = start, lines = 0; at < text.length() && lines < MAX_LINES; at = next(text, lineEnd), lines++) {
            lineEnd = lineEnd(text, at);
            String line = text.substring(at, lineEnd);
            String trimmed = line.strip();
            if (trimmed.equals(fence) || yaml && trimmed.equals("...")) {
                return keyed ? new FrontMatter(title, text.substring(next(text, lineEnd))) : new FrontMatter(null, text);
            }
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                boolean indented = line.charAt(0) == ' ' || line.charAt(0) == '\t';
                boolean key = (yaml ? YAML_KEY : TOML_KEY).matcher(line).matches();
                if (!key && !(keyed && (indented || yaml && YAML_ITEM.matcher(line).matches()))) {
                    return new FrontMatter(null, text);
                }
                keyed |= key;
            }
            Matcher m = (yaml ? YAML_TITLE : TOML_TITLE).matcher(line);
            if (title == null && m.matches()) {
                title = unquote(m.group(1).strip());
            }
        }
        return new FrontMatter(null, text);
    }

    private static int lineEnd(String text, int from) {
        int n = text.indexOf('\n', from);
        int end = n < 0 ? text.length() : n;
        return end > from && text.charAt(end - 1) == '\r' ? end - 1 : end;
    }

    private static int next(String text, int lineEnd) {
        if (lineEnd < text.length() && text.charAt(lineEnd) == '\r') {
            lineEnd++;
        }
        return Math.min(text.length(), lineEnd + 1);
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isBlank() || v.equals("|") || v.equals(">") ? null : v;
    }
}
