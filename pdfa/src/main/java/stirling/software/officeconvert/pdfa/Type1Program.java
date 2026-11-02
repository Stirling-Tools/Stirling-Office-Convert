package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.type1.Type1Font;

final class Type1Program extends FontProgram {

    private final Type1Font font;

    private final List<String> names = new ArrayList<>();

    private final Map<String, Integer> gids = new HashMap<>();

    Type1Program(byte[] data, int length1, int length2) throws IOException {
        font = parse(data, length1, length2);
        names.add(".notdef");
        for (String n : font.getCharStringsDict().keySet()) {
            if (!".notdef".equals(n)) {
                names.add(n);
            }
        }
        for (int i = 0; i < names.size(); i++) {
            gids.put(names.get(i), i);
        }
    }

    private static Type1Font parse(byte[] data, int length1, int length2) throws IOException {
        if (data.length > 6 && (data[0] & 0xFF) == 0x80 && data[1] == 1) {
            try {
                return Type1Font.createWithPFB(data);
            } catch (IOException | RuntimeException e) {
                byte[][] segments = pfbSegments(data);
                return segments(segments[0], segments[1]);
            }
        }
        byte[][] segments = split(data, length1, length2);
        return segments(segments[0], segments[1]);
    }

    static byte[][] split(byte[] data, int length1, int length2) throws IOException {
        if (data.length > 6 && (data[0] & 0xFF) == 0x80 && data[1] == 1) {
            return pfbSegments(data);
        }
        int split = length1 > 0 && length1 < data.length && isEexecEnd(data, length1) ? length1 : eexecEnd(data);
        if (split < 0) {
            throw new IOException("The Type 1 program has no eexec section");
        }
        int end = length2 > 0 && split + length2 <= data.length ? split + length2 : trailerStart(data, split);
        return new byte[][] {java.util.Arrays.copyOfRange(data, 0, split), java.util.Arrays.copyOfRange(data, split,
                end)};
    }

    private static Type1Font segments(byte[] clear, byte[] binary) throws IOException {
        try {
            return Type1Font.createWithSegments(clear, binary);
        } catch (IOException | RuntimeException e) {
            String text = new String(clear, StandardCharsets.ISO_8859_1);
            String fixed = text.replaceAll("/(?=[\\s\\[\\]{}()<>])", "/.notdef");
            if (fixed.equals(text)) {
                throw e;
            }
            return Type1Font.createWithSegments(fixed.getBytes(StandardCharsets.ISO_8859_1), binary);
        }
    }

    private static byte[][] pfbSegments(byte[] data) {
        java.io.ByteArrayOutputStream clear = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream binary = new java.io.ByteArrayOutputStream();
        int at = 0;
        while (at < data.length) {
            if (at + 6 > data.length || (data[at] & 0xFF) != 0x80 || data[at + 1] != 1 && data[at + 1] != 2) {
                (clear.size() == 0 ? clear : binary).write(data, at, data.length - at);
                break;
            }
            int type = data[at + 1];
            long length = (data[at + 2] & 0xFFL) | (data[at + 3] & 0xFFL) << 8 | (data[at + 4] & 0xFFL) << 16
                    | (data[at + 5] & 0xFFL) << 24;
            int n = (int) Math.min(length, data.length - at - 6);
            (type == 1 && binary.size() == 0 ? clear : binary).write(data, at + 6, n);
            at += 6 + n;
        }
        return new byte[][] {clear.toByteArray(), binary.toByteArray()};
    }

    private static boolean isEexecEnd(byte[] data, int at) {
        String before = new String(data, Math.max(0, at - 32), Math.min(32, at), StandardCharsets.ISO_8859_1);
        return before.contains("eexec");
    }

    private static int eexecEnd(byte[] data) {
        String s = new String(data, 0, Math.min(data.length, 65_536), StandardCharsets.ISO_8859_1);
        int at = s.indexOf("eexec");
        if (at < 0) {
            return -1;
        }
        at += 5;
        while (at < data.length && (data[at] == '\r' || data[at] == '\n' || data[at] == ' ' || data[at] == '\t')) {
            at++;
        }
        return at;
    }

    private static int trailerStart(byte[] data, int from) {
        int tail = Math.max(from, data.length - 2048);
        String s = new String(data, tail, data.length - tail, StandardCharsets.ISO_8859_1);
        int mark = s.lastIndexOf("cleartomark");
        if (mark < 0) {
            return data.length;
        }
        int at = tail + mark;
        while (at > from && (data[at - 1] == '0' || data[at - 1] == '\r' || data[at - 1] == '\n'
                || data[at - 1] == ' ' || data[at - 1] == '\t')) {
            at--;
        }
        return at;
    }

    @Override
    int count() {
        return names.size();
    }

    @Override
    String name() {
        return font.getFontName();
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

    @Override
    GeneralPath rawPath(int gid) throws IOException {
        return font.getPath(names.get(gid));
    }

    @Override
    double rawWidth(int gid) throws IOException {
        Double w = Type1Width.of(font.getCharStringsDict().get(names.get(gid)));
        return w != null ? w : font.getWidth(names.get(gid));
    }

    @Override
    CharStringExpansion.Result expansion(int gid) throws IOException {
        return CharStringExpansion.type1(font.getCharStringsDict().get(names.get(gid)), font.getSubrsArray());
    }

    @Override
    List<Number> matrix() {
        return font.getFontMatrix();
    }

    @Override
    String weight() {
        return font.getWeight();
    }

    @Override
    float italicAngle() {
        return font.getItalicAngle();
    }
}
