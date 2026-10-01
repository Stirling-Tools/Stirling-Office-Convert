package stirling.software.officeconvert.topdf.odf;

final class PrintRange {

    private PrintRange() {}

    static String area(String range) {
        if (range == null || range.isBlank()) {
            return null;
        }
        String[] ends = range.replaceAll("'[^']*'", "S").split(":");
        if (ends.length > 2) {
            return null;
        }
        String a = cell(ends[0]);
        String b = ends.length == 2 ? cell(ends[1]) : a;
        if (a == null || b == null) {
            return null;
        }
        return a + ":" + b;
    }

    static java.util.List<String> split(String ranges) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < ranges.length() && out.size() < 64; i++) {
            char c = ranges.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String cell(String ref) {
        String r = ref.trim();
        int dot = r.lastIndexOf('.');
        if (dot >= 0) {
            r = r.substring(dot + 1);
        }
        r = r.replace("$", "");
        int i = 0;
        while (i < r.length() && Character.isLetter(r.charAt(i))) {
            i++;
        }
        if (i == 0 || i > 3 || i == r.length()) {
            return null;
        }
        String col = r.substring(0, i).toUpperCase(java.util.Locale.ROOT);
        String row = r.substring(i);
        if (!row.chars().allMatch(Character::isDigit) || row.length() > 7) {
            return null;
        }
        return "$" + col + "$" + row;
    }
}
