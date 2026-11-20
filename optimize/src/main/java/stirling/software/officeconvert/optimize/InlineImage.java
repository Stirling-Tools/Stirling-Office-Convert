package stirling.software.officeconvert.optimize;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;

final class InlineImage {

    private static final byte[] HEX_END = {'>'};

    private static final byte[] A85_END = {'~', '>'};

    private InlineImage() {}

    static boolean skip(ContentLexer lex, Set<COSName> names) throws ContentLexer.Malformed {
        Map<String, Object> dict = new HashMap<>();
        String key = null;
        List<Object> container = null;
        int depth = 0;
        while (true) {
            if (!lex.next()) {
                return false;
            }
            ContentLexer.Kind kind = lex.kind();
            if (kind == ContentLexer.Kind.OPERATOR) {
                if (!lex.operator("ID") || depth != 0 || key != null) {
                    return false;
                }
                break;
            }
            Object value = switch (kind) {
                case NAME -> {
                    String name = new String(lex.nameBytes(), StandardCharsets.ISO_8859_1);
                    if (names != null) {
                        names.add(COSName.getPDFName(name));
                    }
                    yield name;
                }
                case NUMBER -> Double.parseDouble(lex.text());
                case KEYWORD -> lex.text();
                default -> kind;
            };
            if (kind == ContentLexer.Kind.OPEN_ARRAY || kind == ContentLexer.Kind.OPEN_DICT) {
                if (depth++ == 0) {
                    if (key == null) {
                        return false;
                    }
                    container = new ArrayList<>();
                }
                continue;
            }
            if (kind == ContentLexer.Kind.CLOSE_ARRAY || kind == ContentLexer.Kind.CLOSE_DICT) {
                if (depth == 0) {
                    return false;
                }
                if (--depth == 0) {
                    dict.put(key, container);
                    key = null;
                }
                continue;
            }
            if (depth > 0) {
                container.add(value);
            } else if (key == null) {
                if (!(value instanceof String name)) {
                    return false;
                }
                key = name;
            } else {
                dict.put(key, value);
                key = null;
            }
        }
        int dataStart = lex.end() + 1;
        int dataEnd = dataEnd(lex, dict, dataStart);
        return dataEnd >= 0 && lex.skipInlineData(dataEnd);
    }

    private static int dataEnd(ContentLexer lex, Map<String, Object> dict, int dataStart) {
        Object declared = value(dict, "L", "Length");
        if (declared != null) {
            long length = whole(declared);
            return length < 0 || length > lex.input().length - dataStart ? -1 : (int) (dataStart + length);
        }
        Object filter = value(dict, "F", "Filter");
        String first = filter instanceof String name ? name
                : filter instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof String name ? name
                : null;
        if (filter instanceof List<?> list && list.isEmpty()) {
            filter = null;
        }
        if (filter != null) {
            byte[] end = "AHx".equals(first) || "ASCIIHexDecode".equals(first) ? HEX_END
                    : "A85".equals(first) || "ASCII85Decode".equals(first) ? A85_END : null;
            if (end == null) {
                return -1;
            }
            int at = lex.indexOf(end, dataStart);
            return at < 0 ? -1 : at + end.length;
        }
        boolean mask = "true".equals(value(dict, "IM", "ImageMask"));
        int components = mask ? 1 : components(value(dict, "CS", "ColorSpace"));
        long bits = mask ? 1 : whole(value(dict, "BPC", "BitsPerComponent"));
        long width = whole(value(dict, "W", "Width"));
        long height = whole(value(dict, "H", "Height"));
        if (components < 1 || bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16 || width < 1
                || height < 1) {
            return -1;
        }
        try {
            long rowBytes = (Math.multiplyExact(Math.multiplyExact(width, (long) components), bits) + 7) / 8;
            long length = Math.multiplyExact(rowBytes, height);
            return length > lex.input().length - dataStart ? -1 : (int) (dataStart + length);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    private static int components(Object space) {
        if (space instanceof List<?> list && !list.isEmpty()
                && ("I".equals(list.getFirst()) || "Indexed".equals(list.getFirst()))) {
            return 1;
        }
        if (!(space instanceof String name)) {
            return -1;
        }
        return switch (name) {
            case "G", "DeviceGray", "CalGray" -> 1;
            case "RGB", "DeviceRGB" -> 3;
            case "CMYK", "DeviceCMYK" -> 4;
            default -> -1;
        };
    }

    private static Object value(Map<String, Object> dict, String abbreviation, String full) {
        Object v = dict.get(abbreviation);
        return v != null ? v : dict.get(full);
    }

    private static long whole(Object value) {
        if (!(value instanceof Double d) || d < 0 || d > Integer.MAX_VALUE || d != Math.floor(d)) {
            return -1;
        }
        return d.longValue();
    }
}
