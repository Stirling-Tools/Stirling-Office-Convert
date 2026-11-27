package stirling.software.officeconvert.topdf.uof;

import java.awt.Dimension;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class UofObjects {

    static final int MAX_PICTURE_BYTES = 32 << 20;

    static final long MAX_TOTAL_BYTES = 256L << 20;

    static final long EMU_PER_TWIP = 635;

    static final long MAX_WIDTH_EMU = 6L * 914400;

    record Shape(byte[] picture, String format, Element text, long width, long height) {}

    private final Map<String, Element> data = new HashMap<>();

    private final Map<String, Element> shapes = new HashMap<>();

    private final Map<String, byte[]> decoded = new HashMap<>();

    private final UofUnits units;

    private long total;

    UofObjects(Element root, UofUnits units) {
        this.units = units;
        Element set = Uof.kid(root, "对象集");
        for (Element e : Uof.kids(set)) {
            String id = Uof.attr(e, "标识符");
            if (id == null) {
                continue;
            }
            switch (Uof.name(e)) {
                case "其他对象" -> data.putIfAbsent(id, e);
                case "图形" -> shapes.putIfAbsent(id, e);
                default -> {}
            }
        }
    }

    Shape shape(String id) {
        Element g = id == null ? null : shapes.get(id);
        if (g == null) {
            return null;
        }
        String other = Uof.attr(g, "其他对象");
        if (other == null || !data.containsKey(other)) {
            other = data.containsKey(id) ? id : other;
        }
        byte[] picture = other == null ? null : picture(other);
        Element props = Uof.path(g, "预定义图形", "属性");
        long w = units.twips(Uof.text(Uof.kid(props, "宽度")));
        long h = units.twips(Uof.text(Uof.kid(props, "高度")));
        Element obj = other == null ? null : data.get(other);
        String format = obj == null ? null : Uof.attr(obj, "公共类型");
        return new Shape(picture, format, Uof.kid(g, "文本内容"), w, h);
    }

    byte[] picture(String id) {
        if (id == null || !data.containsKey(id)) {
            return null;
        }
        if (decoded.containsKey(id)) {
            return decoded.get(id);
        }
        Element e = data.get(id);
        byte[] out = null;
        Element d = Uof.kid(e, "数据");
        if (d != null) {
            String b64 = d.getTextContent();
            if (b64.length() / 4L * 3 <= MAX_PICTURE_BYTES && total + b64.length() / 4L * 3 <= MAX_TOTAL_BYTES) {
                try {
                    out = Base64.getMimeDecoder().decode(b64);
                    total += out.length;
                    if (out.length == 0) {
                        out = null;
                    }
                } catch (IllegalArgumentException x) {
                    out = null;
                }
            }
        }
        decoded.put(id, out);
        return out;
    }

    long[] size(Element anchor, Shape shape) {
        Element p = Uof.kid(anchor, "锚点属性");
        long w = units.twips(Uof.text(Uof.kid(p, "宽度")));
        long h = units.twips(Uof.text(Uof.kid(p, "高度")));
        if (!(UofUnits.known(w) && w > 0) && UofUnits.known(shape.width()) && shape.width() > 0) {
            w = shape.width();
            h = shape.height();
        }
        if (UofUnits.known(w) && UofUnits.known(h) && w > 0 && h > 0 && w < 200_000 && h < 200_000) {
            return new long[] {w * EMU_PER_TWIP, h * EMU_PER_TWIP};
        }
        long pw = 0;
        long ph = 0;
        try {
            Dimension d = PictureDecoder.pixelSize(shape.picture());
            pw = d.width;
            ph = d.height;
        } catch (IOException | RuntimeException x) {
            pw = 0;
        }
        if (pw <= 0 || ph <= 0) {
            return new long[] {914400, 914400};
        }
        double cx = pw * 9525.0;
        double cy = ph * 9525.0;
        if (cx > MAX_WIDTH_EMU) {
            cy = cy * MAX_WIDTH_EMU / cx;
            cx = MAX_WIDTH_EMU;
        }
        return new long[] {Math.round(cx), Math.max(1, Math.min(Math.round(cy), 20L * MAX_WIDTH_EMU))};
    }
}
