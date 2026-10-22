package stirling.software.officeconvert.topdf.html;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Picture;

final class Backgrounds {

    private static final long MAX_CANVAS_PIXELS = 6_000_000L;

    private static final int MAX_TILES = 20_000;

    private Backgrounds() {}

    static String url(Map<String, String> d) {
        String url = d.get("background-url");
        return url == null || url.equalsIgnoreCase("none") ? null : url;
    }

    static boolean drawable(Map<String, String> d, HtmlImages images) {
        String url = url(d);
        return url != null && images.source(url) != null;
    }

    static List<Inline> paint(Map<String, String> d, Look look, HtmlImages images, float x, float y, float width,
            float height, boolean fromParagraph) throws IOException {
        List<Inline> out = new ArrayList<>();
        HtmlImages.Source src = images.source(url(d));
        if (src == null || width < 1 || height < 1) {
            return out;
        }
        float[] tile = size(d.get("background-size"), look, src, width, height);
        if (tile[0] < 0.5f || tile[1] < 0.5f) {
            return out;
        }
        float[] at = position(d.get("background-position"), look, width, height, tile);
        boolean[] repeat = repeat(d.get("background-repeat"));
        boolean coversX = at[0] <= 0.01f && at[0] + tile[0] >= width - 0.01f;
        boolean coversY = at[1] <= 0.01f && at[1] + tile[1] >= height - 0.01f;
        Picture pic;
        if ((!repeat[0] || coversX) && (!repeat[1] || coversY)) {
            pic = single(src, look, images, at, tile, width, height);
            if (pic == null) {
                return out;
            }
            pic.x = x + Math.max(0, at[0]);
            pic.y = y + Math.max(0, at[1]);
        } else {
            pic = tiled(src, look, images, at, tile, width, height, repeat);
            if (pic == null) {
                return out;
            }
            pic.x = x;
            pic.y = y;
        }
        pic.wrap = Picture.Wrap.NONE;
        pic.behind = true;
        pic.fromParagraph = fromParagraph;
        out.add(new Inline.Image(pic));
        return out;
    }

    private static Picture single(HtmlImages.Source src, Look look, HtmlImages images, float[] at, float[] tile,
            float width, float height) throws IOException {
        float x0 = Math.max(0, at[0]);
        float y0 = Math.max(0, at[1]);
        float x1 = Math.min(width, at[0] + tile[0]);
        float y1 = Math.min(height, at[1] + tile[1]);
        if (x1 - x0 < 0.5f || y1 - y0 < 0.5f) {
            return null;
        }
        Picture p = images.picture(src, tile[0], tile[1], look);
        if (p == null) {
            return null;
        }
        p.cropLeft = (x0 - at[0]) / tile[0];
        p.cropTop = (y0 - at[1]) / tile[1];
        p.cropRight = (at[0] + tile[0] - x1) / tile[0];
        p.cropBottom = (at[1] + tile[1] - y1) / tile[1];
        p.width = x1 - x0;
        p.height = y1 - y0;
        return p;
    }

    private static Picture tiled(HtmlImages.Source src, Look look, HtmlImages images, float[] at, float[] tile,
            float width, float height, boolean[] repeat) throws IOException {
        String key = "bgtile:" + src.key() + "|" + tile[0] + "x" + tile[1] + "|" + at[0] + "," + at[1] + "|" + width
                + "x" + height + "|" + repeat[0] + repeat[1] + "|" + look.color;
        Picture.MediaRef known = images.tile(key);
        if (known != null) {
            return new Picture(known, width, height);
        }
        BufferedImage pixels = images.pixels(src, tile[0], tile[1], look);
        if (pixels == null) {
            return null;
        }
        double scale = Math.max(1, Math.min(2, pixels.getWidth() / (double) tile[0]));
        double area = width * height * scale * scale;
        if (area > MAX_CANVAS_PIXELS) {
            scale = Math.sqrt(MAX_CANVAS_PIXELS / (width * (double) height));
        }
        int cw = Math.max(1, (int) Math.round(width * scale));
        int ch = Math.max(1, (int) Math.round(height * scale));
        float startX = repeat[0] ? at[0] - (float) Math.ceil(at[0] / tile[0]) * tile[0] : at[0];
        float startY = repeat[1] ? at[1] - (float) Math.ceil(at[1] / tile[1]) * tile[1] : at[1];
        int across = repeat[0] ? (int) Math.ceil((width - startX) / tile[0]) : 1;
        int down = repeat[1] ? (int) Math.ceil((height - startY) / tile[1]) : 1;
        if ((long) across * down > MAX_TILES || !images.chargeTile((long) cw * ch)) {
            return null;
        }
        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int j = 0; j < down; j++) {
                for (int i = 0; i < across; i++) {
                    int px0 = (int) Math.round((startX + i * tile[0]) * scale);
                    int py0 = (int) Math.round((startY + j * tile[1]) * scale);
                    int px1 = (int) Math.round((startX + (i + 1) * tile[0]) * scale);
                    int py1 = (int) Math.round((startY + (j + 1) * tile[1]) * scale);
                    g.drawImage(pixels, px0, py0, px1 - px0, py1 - py0, null);
                }
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas, "png", png)) {
            return null;
        }
        return new Picture(images.png(png.toByteArray(), cw, ch, key), width, height);
    }

    static float[] size(String v, Look look, HtmlImages.Source src, float width, float height) {
        float nw = src.width() > 0 ? src.width() : width;
        float nh = src.height() > 0 ? src.height() : height;
        String s = v == null ? "auto" : v.strip().toLowerCase(Locale.ROOT);
        if (s.equals("cover") || s.equals("contain")) {
            float k = s.equals("cover") ? Math.max(width / nw, height / nh) : Math.min(width / nw, height / nh);
            return new float[] {nw * k, nh * k};
        }
        List<String> t = Css.tokens(s);
        Float w = t.isEmpty() ? null : length(t.get(0), look, width);
        Float h = t.size() < 2 ? null : length(t.get(1), look, height);
        if (w == null && h == null) {
            return new float[] {nw, nh};
        }
        if (h == null) {
            return new float[] {w, t.size() < 2 || t.get(1).equals("auto") ? w * nh / nw : nh};
        }
        if (w == null) {
            return new float[] {h * nw / nh, h};
        }
        return new float[] {w, h};
    }

    private static Float length(String v, Look look, float base) {
        if (v.equals("auto")) {
            return null;
        }
        Float pt = Css.length(v, look.size, base);
        return pt == null || pt <= 0 ? null : pt;
    }

    static float[] position(String v, Look look, float width, float height, float[] tile) {
        float roomX = width - tile[0];
        float roomY = height - tile[1];
        List<String> t = v == null ? List.of() : Css.tokens(v.strip().toLowerCase(Locale.ROOT));
        if (t.isEmpty()) {
            return new float[] {0, 0};
        }
        if (t.size() >= 3) {
            Float x = null;
            Float y = null;
            for (int i = 0; i < t.size() && i < 4; i++) {
                String k = t.get(i);
                Float off = i + 1 < t.size() && !PLACE.contains(t.get(i + 1)) ? null : 0f;
                if (off == null) {
                    off = Css.length(t.get(++i), look.size, k.equals("left") || k.equals("right") ? roomX : roomY);
                    off = off == null ? 0f : off;
                }
                switch (k) {
                    case "left" -> x = off;
                    case "right" -> x = roomX - off;
                    case "top" -> y = off;
                    case "bottom" -> y = roomY - off;
                    default -> {
                    }
                }
            }
            return new float[] {x == null ? roomX / 2 : x, y == null ? roomY / 2 : y};
        }
        String a = t.get(0);
        String b = t.size() > 1 ? t.get(1) : "center";
        if (a.equals("top") || a.equals("bottom") || b.equals("left") || b.equals("right")) {
            String swap = a;
            a = b;
            b = swap;
        }
        return new float[] {along(a, "left", "right", look, roomX), along(b, "top", "bottom", look, roomY)};
    }

    private static final Set<String> PLACE = Set.of("left", "right", "top", "bottom", "center");

    private static float along(String k, String start, String end, Look look, float room) {
        if (k.equals(start)) {
            return 0;
        }
        if (k.equals(end)) {
            return room;
        }
        if (k.equals("center")) {
            return room / 2;
        }
        Float pt = Css.length(k, look.size, room);
        return pt == null ? 0 : pt;
    }

    static boolean[] repeat(String v) {
        if (v == null) {
            return new boolean[] {true, true};
        }
        List<String> t = Css.tokens(v.strip().toLowerCase(Locale.ROOT));
        if (t.isEmpty()) {
            return new boolean[] {true, true};
        }
        return switch (t.get(0)) {
            case "no-repeat" -> t.size() > 1 ? new boolean[] {false, !t.get(1).equals("no-repeat")}
                    : new boolean[] {false, false};
            case "repeat-x" -> new boolean[] {true, false};
            case "repeat-y" -> new boolean[] {false, true};
            default -> t.size() > 1 ? new boolean[] {true, !t.get(1).equals("no-repeat")} : new boolean[] {true, true};
        };
    }
}
