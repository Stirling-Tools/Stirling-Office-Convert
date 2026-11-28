package stirling.software.officeconvert.topdf.psd;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Descriptor {

    static final int MAX_DEPTH = 32;

    static final int MAX_ITEMS = 20_000;

    record Unit(String unit, double value) {}

    record Enum(String type, String value) {}

    final String classId;

    final Map<String, Object> items = new LinkedHashMap<>();

    private Descriptor(String classId) {
        this.classId = classId;
    }

    static Descriptor read(byte[] data, int offset) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            b.position(offset);
            int[] budget = {MAX_ITEMS};
            return descriptor(b, 0, budget);
        } catch (BufferUnderflowException | IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static Descriptor descriptor(ByteBuffer b, int depth, int[] budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("too deep");
        }
        unicode(b);
        Descriptor d = new Descriptor(key(b));
        long count = b.getInt() & 0xFFFFFFFFL;
        for (long i = 0; i < count; i++) {
            if (--budget[0] < 0) {
                throw new IllegalArgumentException("too many items");
            }
            String key = key(b);
            d.items.put(key, value(b, type(b), depth, budget));
        }
        return d;
    }

    private static String type(ByteBuffer b) {
        byte[] t = new byte[4];
        b.get(t);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static Object value(ByteBuffer b, String type, int depth, int[] budget) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("too deep");
        }
        return switch (type) {
            case "Objc", "GlbO" -> descriptor(b, depth + 1, budget);
            case "VlLs" -> {
                long n = b.getInt() & 0xFFFFFFFFL;
                List<Object> list = new ArrayList<>();
                for (long i = 0; i < n; i++) {
                    if (--budget[0] < 0) {
                        throw new IllegalArgumentException("too many items");
                    }
                    list.add(value(b, type(b), depth + 1, budget));
                }
                yield list;
            }
            case "doub" -> b.getDouble();
            case "UntF" -> new Unit(type(b), b.getDouble());
            case "UnFl" -> {
                String unit = type(b);
                long n = b.getInt() & 0xFFFFFFFFL;
                if (n * 8 > b.remaining()) {
                    throw new IllegalArgumentException("short");
                }
                double[] v = new double[(int) n];
                for (int i = 0; i < n; i++) {
                    v[i] = b.getDouble();
                }
                yield new Unit(unit, n > 0 ? v[0] : 0);
            }
            case "TEXT" -> unicode(b);
            case "enum" -> new Enum(key(b), key(b));
            case "long" -> b.getInt();
            case "comp" -> b.getLong();
            case "bool" -> b.get() != 0;
            case "type", "GlbC" -> {
                unicode(b);
                yield key(b);
            }
            case "alis", "tdta", "Pth " -> {
                long n = b.getInt() & 0xFFFFFFFFL;
                if (n > b.remaining()) {
                    throw new IllegalArgumentException("short");
                }
                b.position(b.position() + (int) n);
                yield null;
            }
            case "obj " -> reference(b);
            default -> throw new IllegalArgumentException("unknown type " + type);
        };
    }

    private static Object reference(ByteBuffer b) {
        long n = b.getInt() & 0xFFFFFFFFL;
        for (long i = 0; i < n; i++) {
            String t = type(b);
            switch (t) {
                case "prop" -> {
                    unicode(b);
                    key(b);
                    key(b);
                }
                case "Clss" -> {
                    unicode(b);
                    key(b);
                }
                case "Enmr" -> {
                    unicode(b);
                    key(b);
                    key(b);
                    key(b);
                }
                case "rele", "indx" -> {
                    unicode(b);
                    key(b);
                    b.getInt();
                }
                case "Idnt" -> b.getInt();
                case "name" -> {
                    unicode(b);
                    key(b);
                    unicode(b);
                }
                default -> throw new IllegalArgumentException("reference " + t);
            }
        }
        return null;
    }

    private static String key(ByteBuffer b) {
        long n = b.getInt() & 0xFFFFFFFFL;
        int len = n == 0 ? 4 : (int) Math.min(n, 1 << 16);
        if (len > b.remaining()) {
            throw new IllegalArgumentException("short");
        }
        byte[] k = new byte[len];
        b.get(k);
        return new String(k, StandardCharsets.ISO_8859_1);
    }

    private static String unicode(ByteBuffer b) {
        long n = b.getInt() & 0xFFFFFFFFL;
        if (n * 2 > b.remaining()) {
            throw new IllegalArgumentException("short");
        }
        StringBuilder s = new StringBuilder();
        for (long i = 0; i < n; i++) {
            char c = b.getChar();
            if (c != 0) {
                s.append(c);
            }
        }
        return s.toString();
    }

    Object get(String key) {
        return items.get(key);
    }

    Descriptor object(String key) {
        return items.get(key) instanceof Descriptor d ? d : null;
    }

    List<?> list(String key) {
        return items.get(key) instanceof List<?> l ? l : List.of();
    }

    double number(String key, double fallback) {
        Object v = items.get(key);
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Unit u) {
            return u.value();
        }
        if (v instanceof Integer i) {
            return i;
        }
        return fallback;
    }

    boolean flag(String key, boolean fallback) {
        return items.get(key) instanceof Boolean f ? f : fallback;
    }

    String enumValue(String key) {
        return items.get(key) instanceof Enum e ? e.value() : null;
    }

    float[] color(String key) {
        Descriptor c = object(key);
        return c == null ? null : color(c);
    }

    static float[] color(Descriptor c) {
        switch (c.classId) {
            case "RGBC" -> {
                double r = c.number("Rd  ", Double.NaN);
                double g = c.number("Grn ", Double.NaN);
                double b = c.number("Bl  ", c.number("blueFloat", Double.NaN));
                if (Double.isNaN(r)) {
                    r = c.number("redFloat", 0) * 255;
                    g = c.number("greenFloat", 0) * 255;
                    b = c.number("blueFloat", 0) * 255;
                }
                return new float[] {(float) (r / 255), (float) (g / 255), (float) (b / 255)};
            }
            case "CMYC" -> {
                return new float[] {(float) (c.number("Cyn ", 0) / 100), (float) (c.number("Mgnt", 0) / 100),
                    (float) (c.number("Ylw ", 0) / 100), (float) (c.number("Blck", 0) / 100), -1};
            }
            case "Grsc" -> {
                return new float[] {(float) (1 - c.number("Gry ", 0) / 100)};
            }
            case "HSBC" -> {
                return Colors.toRgb(1, new int[] {(int) (c.number("H   ", 0) / 360 * 65535),
                    (int) (c.number("Strt", 0) / 100 * 65535), (int) (c.number("Brgh", 0) / 100 * 65535), 0});
            }
            case "LbCl" -> {
                float l = (float) c.number("Lmnc", 0);
                float a = (float) c.number("A   ", 0);
                float b = (float) c.number("B   ", 0);
                float[] rgb = Colors.lab(l, a, b);
                return new float[] {rgb[0], rgb[1], rgb[2], Colors.clamp(l / 100), Colors.clamp((a + 128) / 255),
                    Colors.clamp((b + 128) / 255)};
            }
            default -> {
                return null;
            }
        }
    }
}
