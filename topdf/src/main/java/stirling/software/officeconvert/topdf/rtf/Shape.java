package stirling.software.officeconvert.topdf.rtf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Shape {

    static final int MAX_CHILDREN = 1000;

    static final int MAX_PROPS = 512;

    final boolean group;

    int left;
    int top;
    int right;
    int bottom;
    String bx;
    String by;
    boolean bxIgnore;
    boolean byIgnore;
    int wrap = -1;
    int wrapSide;
    boolean behind;
    int z;
    boolean inline;
    final Map<String, String> props = new HashMap<>();
    PictureXml.Image picture;
    String text;
    final List<Shape> children = new ArrayList<>();

    Shape(boolean group) {
        this.group = group;
    }

    boolean word(String w, int v) {
        switch (w) {
            case "shpleft" -> left = v;
            case "shptop" -> top = v;
            case "shpright" -> right = v;
            case "shpbottom" -> bottom = v;
            case "shpbxpage" -> bx = "page";
            case "shpbxmargin" -> bx = "margin";
            case "shpbxcolumn" -> bx = "column";
            case "shpbxignore" -> bxIgnore = true;
            case "shpbypage" -> by = "page";
            case "shpbymargin" -> by = "margin";
            case "shpbypara" -> by = "paragraph";
            case "shpbyignore" -> byIgnore = true;
            case "shpwr" -> wrap = v;
            case "shpwrk" -> wrapSide = v;
            case "shpfblwtxt" -> behind = v != 0;
            case "shpz" -> z = v;
            default -> {
                return false;
            }
        }
        return true;
    }

    void prop(String name, String value) {
        if (name != null && props.size() < MAX_PROPS) {
            props.put(name.strip(), value == null ? "" : value.strip());
        }
    }

    int integer(String name, int fallback) {
        String v = props.get(name);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Long.parseLong(v)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    boolean flag(String name, boolean fallback) {
        String v = props.get(name);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        return !"0".equals(v) && !"false".equalsIgnoreCase(v);
    }

    void add(Shape child) {
        if (children.size() < MAX_CHILDREN) {
            children.add(child);
        }
    }
}
