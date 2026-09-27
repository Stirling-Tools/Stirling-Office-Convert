package stirling.software.officeconvert.table;

import java.util.regex.Pattern;

final class ListMarkers {

    private static final String BULLETS = "•●◦▪■‣⁃∙○◆►▶✓✔" + "·*-–\u2014";

    private static final Pattern NUMBERING =
            Pattern.compile("\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)]");

    private ListMarkers() {}

    static boolean isBullet(String text) {
        if (text.isEmpty() || text.length() > 2) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (BULLETS.indexOf(c) < 0 && (c < 0xF000 || c > 0xF0FF)) {
                return false;
            }
        }
        return true;
    }

    static boolean isMarker(String text) {
        return isBullet(text) || NUMBERING.matcher(text).matches();
    }

    static boolean isLeader(String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c != '.' && c != '·' && c != '…' && c != '_') {
                return false;
            }
        }
        return true;
    }
}
