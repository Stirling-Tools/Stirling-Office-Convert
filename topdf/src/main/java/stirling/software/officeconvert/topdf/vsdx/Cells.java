package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class Cells {

    private static final Set<String> LINE = Set.of("LineWeight", "LineColor", "LinePattern", "Rounding", "BeginArrow",
            "EndArrow", "LineCap", "BeginArrowSize", "EndArrowSize", "LineColorTrans", "CompoundType");

    private static final Set<String> FILL = Set.of("FillForegnd", "FillBkgnd", "FillPattern", "FillForegndTrans",
            "FillBkgndTrans", "FillGradientEnabled", "FillGradientDir", "FillGradientAngle", "ShdwForegnd",
            "ShdwPattern", "ShapeShdwType", "ShapeShdwOffsetX", "ShapeShdwOffsetY", "ShdwForegndTrans");

    private static final String[] VGA = {"000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
        "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "E6E6E6", "CDCDCD", "B3B3B3", "9A9A9A",
        "808080", "666666", "4D4D4D", "333333", "1A1A1A"};

    private static final int MAX_DEPTH = 32;

    enum Kind {
        LINE, FILL, TEXT
    }

    final Map<String, Sheet> styles;

    private final String defaultLine;

    private final String defaultFill;

    private final String defaultText;

    private final Map<Integer, String> colors = new HashMap<>();

    Cells(Map<String, Sheet> styles, String defaultLine, String defaultFill, String defaultText,
            Map<Integer, String> palette) {
        this.styles = styles;
        this.defaultLine = defaultLine;
        this.defaultFill = defaultFill;
        this.defaultText = defaultText;
        for (int i = 0; i < VGA.length; i++) {
            colors.put(i, VGA[i]);
        }
        colors.putAll(palette);
    }

    static Kind kind(String cell) {
        return LINE.contains(cell) ? Kind.LINE : FILL.contains(cell) ? Kind.FILL : Kind.TEXT;
    }

    String get(Sheet s, String name) {
        int depth = 0;
        for (Sheet x = s; x != null && depth++ < MAX_DEPTH; x = x.base) {
            String v = x.cells.get(name);
            if (v != null) {
                return v;
            }
        }
        Kind k = kind(name);
        Sheet st = styles.get(style(s, k));
        for (depth = 0; st != null && depth++ < MAX_DEPTH; st = styles.get(parent(st, k))) {
            String v = st.cells.get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    double number(Sheet s, String name, double fallback) {
        return parse(get(s, name), fallback);
    }

    static double parse(String v, double fallback) {
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    String rowCell(Sheet s, String section, String row, String cell) {
        int depth = 0;
        for (Sheet x = s; x != null && depth++ < MAX_DEPTH; x = x.base) {
            Sheet.Section sec = x.section(section);
            if (sec != null) {
                Sheet.Row r = sec.rows.get(row);
                if (r != null && r.cells().containsKey(cell)) {
                    return r.cells().get(cell);
                }
            }
        }
        Sheet st = styles.get(style(s, Kind.TEXT));
        for (depth = 0; st != null && depth++ < MAX_DEPTH; st = styles.get(parent(st, Kind.TEXT))) {
            Sheet.Section sec = st.section(section);
            if (sec != null) {
                Sheet.Row r = sec.rows.get("0");
                if (r != null && r.cells().containsKey(cell)) {
                    return r.cells().get(cell);
                }
            }
        }
        return null;
    }

    private String style(Sheet s, Kind k) {
        int depth = 0;
        for (Sheet x = s; x != null && depth++ < MAX_DEPTH; x = x.base) {
            String id = parent(x, k);
            if (id != null) {
                return id;
            }
        }
        return k == Kind.LINE ? defaultLine : k == Kind.FILL ? defaultFill : defaultText;
    }

    private static String parent(Sheet s, Kind k) {
        return k == Kind.LINE ? s.lineStyle : k == Kind.FILL ? s.fillStyle : s.textStyle;
    }

    record Geometry(String key, Map<String, String> cells, List<Sheet.Row> rows) {}

    List<Geometry> geometry(Sheet s) {
        Map<Integer, String> keys = new TreeMap<>();
        int depth = 0;
        for (Sheet x = s; x != null && depth++ < MAX_DEPTH; x = x.base) {
            for (String key : x.sections.keySet()) {
                if (key.startsWith("Geometry")) {
                    keys.putIfAbsent((int) parse(key.substring(8), keys.size()), key);
                }
            }
        }
        List<Geometry> out = new ArrayList<>();
        for (String key : keys.values()) {
            List<Sheet> chain = chain(s);
            if (chain.isEmpty() || chain.get(0).section(key) != null && chain.get(0).section(key).deleted) {
                continue;
            }
            Map<String, String> secCells = new HashMap<>();
            TreeMap<Integer, String> rowKeys = new TreeMap<>();
            for (Sheet x : chain) {
                Sheet.Section sec = x.section(key);
                if (sec == null) {
                    continue;
                }
                if (sec.deleted) {
                    break;
                }
                sec.cells.forEach(secCells::putIfAbsent);
                for (String rk : sec.rows.keySet()) {
                    rowKeys.putIfAbsent((int) parse(rk, rowKeys.size() + 1000), rk);
                }
            }
            List<Sheet.Row> rows = new ArrayList<>();
            for (String rk : rowKeys.values()) {
                String type = null;
                boolean deleted = false;
                Map<String, String> cells = new HashMap<>();
                for (Sheet x : chain) {
                    Sheet.Section sec = x.section(key);
                    Sheet.Row r = sec == null ? null : sec.rows.get(rk);
                    if (r == null) {
                        continue;
                    }
                    if (r.deleted()) {
                        deleted = true;
                        break;
                    }
                    if (type == null) {
                        type = r.type();
                    }
                    r.cells().forEach(cells::putIfAbsent);
                }
                if (!deleted && type != null) {
                    rows.add(new Sheet.Row(type, false, cells));
                }
            }
            out.add(new Geometry(key, secCells, rows));
        }
        return out;
    }

    private static List<Sheet> chain(Sheet s) {
        List<Sheet> out = new ArrayList<>();
        int depth = 0;
        for (Sheet x = s; x != null && depth++ < MAX_DEPTH; x = x.base) {
            out.add(x);
        }
        return out;
    }

    String color(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.startsWith("#") && t.length() == 7) {
            return t.substring(1).toUpperCase(Locale.ROOT);
        }
        String low = t.toLowerCase(Locale.ROOT);
        if (low.startsWith("rgb(") && low.endsWith(")")) {
            String[] p = low.substring(4, low.length() - 1).split(",");
            if (p.length == 3) {
                StringBuilder b = new StringBuilder();
                for (String c : p) {
                    int n = (int) Math.max(0, Math.min(255, parse(c, 0)));
                    b.append(String.format(Locale.ROOT, "%02X", n));
                }
                return b.toString();
            }
            return null;
        }
        double d = parse(t, -1);
        if (d >= 0 && d == Math.floor(d)) {
            return colors.get((int) d);
        }
        return null;
    }
}
