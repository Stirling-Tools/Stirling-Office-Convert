package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.List;

final class ColorTable {

    static final int MAX = 1 << 14;

    private final List<Integer> colors = new ArrayList<>();

    private int red;

    private int green;

    private int blue;

    private boolean any;

    void word(String word, int param) {
        int v = Math.max(0, Math.min(255, param));
        switch (word) {
            case "red" -> {
                red = v;
                any = true;
            }
            case "green" -> {
                green = v;
                any = true;
            }
            case "blue" -> {
                blue = v;
                any = true;
            }
            default -> {
            }
        }
    }

    void end() {
        if (colors.size() < MAX) {
            colors.add(any ? red << 16 | green << 8 | blue : -1);
        }
        red = 0;
        green = 0;
        blue = 0;
        any = false;
    }

    boolean explicit(int index) {
        return index >= 0 && index < colors.size() && colors.get(index) >= 0;
    }

    int rgb(int index, int fallback) {
        if (index < 0 || index >= colors.size()) {
            return fallback;
        }
        int c = colors.get(index);
        return c < 0 ? fallback : c;
    }

    String hex(int index) {
        return explicit(index) ? Shading.hex(colors.get(index)) : "auto";
    }
}
