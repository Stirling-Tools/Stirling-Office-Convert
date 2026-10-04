package stirling.software.officeconvert.topdf.rtf;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

final class FontTable {

    static final int MAX = 1 << 15;

    static final class Font {
        final int id;
        String name = "";
        String alt;
        int charset = -1;
        int codePage;
        String family;
        int pitch;

        Font(int id) {
            this.id = id;
        }

        boolean symbol() {
            return charset == 2;
        }
    }

    private final Map<Integer, Font> fonts = new HashMap<>();

    Font open(int id) {
        if (fonts.size() >= MAX && !fonts.containsKey(id)) {
            return new Font(id);
        }
        return fonts.computeIfAbsent(id, Font::new);
    }

    Font get(int id) {
        return fonts.get(id);
    }

    String name(int id) {
        Font f = fonts.get(id);
        return f == null || f.name.isBlank() ? null : f.name;
    }

    Iterable<Font> all() {
        return fonts.values();
    }

    Charset charset(int id, Charset fallback) {
        Font f = fonts.get(id);
        if (f == null) {
            return fallback;
        }
        if (f.codePage > 0) {
            Charset c = CodePages.forCodePage(f.codePage);
            if (c != null) {
                return c;
            }
        }
        if (f.charset < 0 || f.charset == 1) {
            return fallback;
        }
        Charset c = CodePages.forCharset(f.charset);
        return c == null ? fallback : c;
    }

    boolean symbol(int id) {
        Font f = fonts.get(id);
        return f != null && f.symbol();
    }
}
