package stirling.software.officeconvert.extract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

record GlyphRef(String text, int style, GlyphPrint print) {

    static final int BOLD = 1;

    static final int ITALIC = 2;

    static final int SANS = 4;

    static final int MONO = 8;

    private static final String RESOURCE = "glyph-refs.txt";

    private static volatile List<GlyphRef> all;

    static List<GlyphRef> all() {
        List<GlyphRef> refs = all;
        if (refs == null) {
            synchronized (GlyphRef.class) {
                refs = all;
                if (refs == null) {
                    refs = load();
                    all = refs;
                }
            }
        }
        return refs;
    }

    private static List<GlyphRef> load() {
        List<GlyphRef> refs = new ArrayList<>();
        try (InputStream in = GlyphRef.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    refs.add(parse(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(refs);
    }

    static GlyphRef parse(String line) {
        String[] parts = line.split("\\|");
        StringBuilder text = new StringBuilder();
        for (String cp : parts[0].split(" ")) {
            text.appendCodePoint(Integer.parseInt(cp, 16));
        }
        String[] box = parts[2].split(" ");
        float[] cells = new float[GlyphPrint.GRID * GlyphPrint.GRID];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = Character.digit(parts[3].charAt(i), 16) / 15f;
        }
        return new GlyphRef(text.toString(), Integer.parseInt(parts[1]), new GlyphPrint(cells, num(box[0]),
                num(box[1]), num(box[2]), num(box[3]), num(box[4])));
    }

    private static float num(String s) {
        return Integer.parseInt(s) / 1000f;
    }
}
