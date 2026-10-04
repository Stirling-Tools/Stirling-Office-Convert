package stirling.software.officeconvert.topdf.xlsx;

import java.util.Locale;

record StyledName(String family, boolean bold, boolean italic) {

    private static final String[][] SUFFIXES = {{" bold italic", "bi"}, {" bold oblique", "bi"}, {" italic", "i"},
            {" oblique", "i"}, {" bold", "b"}};

    static StyledName of(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String[] s : SUFFIXES) {
            if (lower.endsWith(s[0]) && lower.length() > s[0].length()) {
                String base = trimmed.substring(0, trimmed.length() - s[0].length()).trim();
                return base.isEmpty() ? null : new StyledName(base, s[1].contains("b"), s[1].contains("i"));
            }
        }
        return null;
    }
}
