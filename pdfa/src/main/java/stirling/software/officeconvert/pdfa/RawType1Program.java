package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RawType1Program extends FontProgram {

    private final Type1Lenient font;

    private final List<String> names = new ArrayList<>();

    private final Map<String, Integer> gids = new HashMap<>();

    RawType1Program(Type1Lenient font) {
        this.font = font;
        names.add(".notdef");
        for (String n : font.charStrings.keySet()) {
            if (!".notdef".equals(n)) {
                names.add(n);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            gids.put(names.get(i), i);
        }
    }

    @Override
    int count() {
        return names.size();
    }

    @Override
    String name() {
        return font.fontName;
    }

    @Override
    String name(int gid) {
        return gid >= 0 && gid < names.size() ? names.get(gid) : null;
    }

    @Override
    int gid(String name) {
        Integer g = name == null ? null : gids.get(name);
        return g == null ? 0 : g;
    }

    private org.apache.fontbox.cff.Type1CharString charString(String glyph, int depth) throws IOException {
        byte[] bytes = font.charStrings.get(glyph);
        if (bytes == null) {
            bytes = new byte[] {14};
        }
        List<Object> sequence = new org.apache.fontbox.cff.Type1CharStringParser(font.fontName).parse(bytes,
                font.subrs, glyph);
        return new org.apache.fontbox.cff.Type1CharString(n -> depth > 2 ? null : charString(n, depth + 1),
                font.fontName, glyph, sequence);
    }

    @Override
    GeneralPath rawPath(int gid) throws IOException {
        return charString(names.get(gid), 0).getPath();
    }

    @Override
    double rawWidth(int gid) throws IOException {
        Double w = Type1Width.of(font.charStrings.get(names.get(gid)));
        return w != null ? w : charString(names.get(gid), 0).getWidth();
    }

    @Override
    CharStringExpansion.Result expansion(int gid) throws IOException {
        byte[] cs = font.charStrings.get(names.get(gid));
        return CharStringExpansion.type1(cs == null ? new byte[] {14} : cs, font.subrs);
    }

    @Override
    List<Number> matrix() {
        return font.fontMatrix;
    }

    @Override
    String weight() {
        return null;
    }

    @Override
    float italicAngle() {
        return 0;
    }
}
