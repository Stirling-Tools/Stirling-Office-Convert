package stirling.software.officeconvert.topdf.xlsx;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class ScreenDigits {

    private static final int MAX_PPEM = 100;

    private static final Map<String, int[]> HINTED = new HashMap<>();

    static {
        hinted("Aptos", 1094, "");
        hinted("Aptos Narrow", 1038, "15-1 72+1");
        hinted("Aptos Light", 1094, "");
        hinted("Arial", 1139, "80+1 89+1");
        hinted("Helvetica", 1139, "80+1 89+1");
        hinted("Arial Narrow", 934, "");
        hinted("Calibri", 1038, "15-1 72+1");
        hinted("Calibri Light", 1038, "72+1");
        hinted("Cambria", 1134, "93+1");
        hinted("Candara", 1130, "3+1 4+1 6+1 8+2 9+1 11+1 13+1 19+1 23-1 25-1 26+1 27+1 28+1 31+1 38-1 39-1 40-1 "
                + "41-1 43+1 44+1 45+1 46+1 48+1 52-1 58-1 59-1 60+1 62+1 64+1 66+1 70-1 72-1 74-1 "
                + "76-1 77+1 78+1 79+1 80+1 82+1 88-1 90-1 92-1 93-1 94+1 95+1 96+1");
        hinted("Century Gothic", 1135, "46+1");
        hinted("Consolas", 1126, "10+1 30+1");
        hinted("Constantia", 1119, "3+1 4+2 5+1 6+1 7+1 8+2 9+1 10+2 11+1 12+1 13+1 15+1 16+1 17+1 18+1 19+1 21+1 "
                + "26+1 33-1 36-1 37+1 39+1 41+1 43+1 46+1 48+1 50+1 52+1 54-1 56-1 60-1 66+1 70+1 "
                + "72+1 85+1 86+1 90+1 96+1");
        hinted("Corbel", 1074, "5+1 6+1 7+1 8+2 9+1 10+1 11+1 12+1 13+1 14+1 16+1 18+1 20+1 30-1 31+1 32+1 33+1 "
                + "35+1 37+1 39+1 41-1 43-1 51+1 52+1 54+1 56+1 58+1 60+1 62-1 64-1 72+1 73+1 75+1 "
                + "79+1 84+1 91-1 92+1 96+1");
        hinted("Courier New", 1229, "");
        hinted("Franklin Gothic Book", 1201, "52+1");
        hinted("Garamond", 960, "");
        hinted("Georgia", 1257, "11+1 12+1 13+1");
        hinted("Gill Sans MT", 1024, "");
        hinted("Book Antiqua", 1024, "");
        hinted("Palatino Linotype", 1024, "");
        hinted("Tahoma", 1118, "87+1 98+1");
        hinted("Times New Roman", 1024, "13-1 15-1 19-1 23-1 25-1 29-1");
        hinted("Trebuchet MS", 1074, "");
        hinted("Verdana", 1302, "9+1 10+1 81+1");
        hinted("Microsoft Sans Serif", 1139, "19-1 80+1 89+1");
        hinted("Lucida Sans", 1295, "34+1");
        hinted("Century", 1139, "80+1 89+1");
        hinted("Bookman Old Style", 1270, "");
        hinted("Lucida Console", 1234, "39+1");
        hinted("Comic Sans MS", 1250, "9+1");
    }

    private ScreenDigits() {}

    private static void hinted(String family, int digit, String deltas) {
        int[] pixels = new int[MAX_PPEM + 1];
        for (int ppem = 1; ppem <= MAX_PPEM; ppem++) {
            pixels[ppem] = (int) Math.round(digit * ppem / 2048.0);
        }
        for (String delta : deltas.isEmpty() ? new String[0] : deltas.split(" ")) {
            int sign = Math.max(delta.indexOf('+'), delta.indexOf('-'));
            pixels[Integer.parseInt(delta, 0, sign, 10)] += Integer.parseInt(delta.substring(sign));
        }
        HINTED.put(family.toLowerCase(Locale.ROOT), pixels);
    }

    static int[] of(String family) {
        return family == null ? null : HINTED.get(family.toLowerCase(Locale.ROOT).trim());
    }
}
