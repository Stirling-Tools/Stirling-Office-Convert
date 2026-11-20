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
        if (number(dict, "L", "Length") instanceof Double length && length >= 0) {
            return dataStart + length.intValue();
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
        Double bits = mask ? Double.valueOf(1) : number(dict, "BPC", "BitsPerComponent");
        Double width = number(dict, "W", "Width");
        Double height = number(dict, "H", "Height");
        if (components < 1 || bits == null || width == null || height == null || bits < 1 || width < 1
                || height < 1) {
            return -1;
        }
        long rowBytes = ((long) width.intValue() * components * bits.intValue() + 7) / 8;
        long length = rowBytes * height.intValue();
        return length > Integer.MAX_VALUE - dataStart ? -1 : (int) (dataStart + length);
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

    private static Double number(Map<String, Object> dict, String abbreviation, String full) {
        return value(dict, abbreviation, full) instanceof Double d ? d : null;
    }
}
