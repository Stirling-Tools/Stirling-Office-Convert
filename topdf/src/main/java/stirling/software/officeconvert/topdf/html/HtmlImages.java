package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.json.MediaBytes;
import stirling.software.officeconvert.model.Picture;

/** Pictures given inline: data: URIs and SVG drawn in the page. Nothing else is loaded: a picture on the web or on
 * disk is left out. */
final class HtmlImages {

    static final String NOT_FETCHED = "Pictures linked from the web or from disk were left out: only pictures inside"
            + " the HTML file (data: URIs) are drawn";

    private static final long MAX_DATA_CHARS = 96L << 20;

    // Painting SVG costs time and memory, so a page gets this many and no more
    private static final int MAX_SVGS = 3_000;
    private static final long MAX_SVG_PIXELS = 300_000_000L;
    private static final int MAX_SVG_CHARS = 2 << 20;
    private static final long SVG_WORK = 600_000_000L;

    private final DocSink sink;
    private final List<String> warnings;
    private final Map<String, Picture.MediaRef> seen = new HashMap<>();
    private final Map<String, Svg.Painted> painted = new HashMap<>();
    private long dataChars;
    private int added;
    private int svgs;
    private long svgPixels;
    private final long[] svgWork = {SVG_WORK};
    private Map<String, Element> documentIds;

    static final String NOT_IN_PACKAGE = "Left out pictures that could not be found beside the HTML file";

    private final Linked linked;

    HtmlImages(DocSink sink, List<String> warnings, Linked linked) {
        this.sink = sink;
        this.warnings = warnings;
        this.linked = linked;
    }

    Picture picture(Element img, Map<String, String> d, Look look, float maxWidth, float maxHeight) throws IOException {
        String src = img.attr("src").strip();
        if (src.isEmpty()) {
            src = firstCandidate(img.attr("srcset"));
        }
        if (src.regionMatches(true, 0, "data:image/svg", 0, 14)) {
            return svgImage(src, img, d, look, maxWidth, maxHeight);
        }
        if (!src.isEmpty() && !src.regionMatches(true, 0, "data:", 0, 5) && !linked.none()) {
            return linkedImage(src, img, d, look, maxWidth, maxHeight);
        }
        Picture.MediaRef ref = media(src);
        if (ref == null) {
            return null;
        }
        float[] box = box(d, look, ref.pixelWidth() * 0.75f, ref.pixelHeight() * 0.75f, maxWidth, maxHeight);
        Picture p = new Picture(ref, box[0], box[1]);
        p.description = img.attr("alt");
        return p;
    }

    /** An inline svg element painted at the size its CSS or attributes give it. */
    Picture inlineSvg(Element svg, Map<String, String> d, Look look, float maxWidth, float maxHeight) throws IOException {
        float[] natural = Svg.size(svg);
        if (percent(d.get("width")) || percent(d.get("height"))) {
            // a percentage is of a box this flow does not have; the drawing's own size is the better guess
            d = new HashMap<>(d);
            d.remove("width");
            d.remove("height");
        }
        float[] box = box(d, look, natural[0] * 0.75f, natural[1] * 0.75f, maxWidth, maxHeight);
        return painted(svg, ids(svg.ownerDocument()), box, look, null);
    }

    private Picture svgImage(String src, Element img, Map<String, String> d, Look look, float maxWidth, float maxHeight)
            throws IOException {
        Element svg = parseSvg(src);
        if (svg == null) {
            return null;
        }
        float[] natural = Svg.size(svg);
        float[] box = box(d, look, natural[0] * 0.75f, natural[1] * 0.75f, maxWidth, maxHeight);
        Map<String, Element> ids = new HashMap<>();
        collectIds(svg, ids);
        Picture p = painted(svg, ids, box, look, src.length() < (1 << 16) ? src : null);
        if (p != null) {
            p.description = img.attr("alt");
        }
        return p;
    }

    private Picture linkedImage(String src, Element img, Map<String, String> d, Look look, float maxWidth,
            float maxHeight) throws IOException {
        String reference = HtmlRefs.resolve(linked.base(), src);
        byte[] bytes = reference == null ? null : linked.read(reference);
        if (bytes == null) {
            warn(linked.exhausted() ? "Left out pictures past the limit of linked files" : NOT_IN_PACKAGE);
            return null;
        }
        if (svgFile(reference, bytes)) {
            if (bytes.length > MAX_SVG_CHARS) {
                return null;
            }
            Element svg = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser()).selectFirst("svg");
            if (svg == null) {
                return null;
            }
            float[] natural = Svg.size(svg);
            float[] box = box(d, look, natural[0] * 0.75f, natural[1] * 0.75f, maxWidth, maxHeight);
            Map<String, Element> ids = new HashMap<>();
            collectIds(svg, ids);
            Picture p = painted(svg, ids, box, look, "linked:" + reference);
            if (p != null) {
                p.description = img.attr("alt");
            }
            return p;
        }
        Picture.MediaRef ref = seen.get(reference);
        if (ref == null) {
            ref = decoded(bytes, "linked:" + reference);
            if (ref == null) {
                return null;
            }
            seen.put(reference, ref);
        }
        float[] box = box(d, look, ref.pixelWidth() * 0.75f, ref.pixelHeight() * 0.75f, maxWidth, maxHeight);
        Picture p = new Picture(ref, box[0], box[1]);
        p.description = img.attr("alt");
        return p;
    }

    private static boolean svgFile(String reference, byte[] bytes) {
        if (HtmlRefs.extension(reference).equals("svg")) {
            return true;
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.ISO_8859_1).stripLeading();
        return head.startsWith("<svg") || head.startsWith("<?xml") && head.contains("<svg");
    }

    static String firstCandidate(String srcset) {
        String s = srcset.strip();
        if (s.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != ',') {
            end++;
        }
        return s.substring(0, end);
    }

    private static Element parseSvg(String src) {
        int comma = src.indexOf(',');
        if (comma < 0 || src.length() > MAX_SVG_CHARS) {
            return null;
        }
        String head = src.substring(5, comma).toLowerCase(Locale.ROOT);
        String data = src.substring(comma + 1);
        String text;
        try {
            text = head.endsWith(";base64") ? new String(Base64.getMimeDecoder().decode(data), StandardCharsets.UTF_8)
                    : percentDecoded(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // The XML parser reads no DTD and resolves no external entity
        Document doc = Jsoup.parse(text, "", Parser.xmlParser());
        return doc.selectFirst("svg");
    }

    // data:image/svg+xml,%3Csvg... spells markup with %XX escapes
    private static String percentDecoded(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int hi = i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
            int lo = i + 2 < s.length() ? Character.digit(s.charAt(i + 2), 16) : -1;
            if (c == '%' && hi >= 0 && lo >= 0) {
                out.write(hi << 4 | lo);
                i += 2;
            } else {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private Picture painted(Element svg, Map<String, Element> ids, float[] box, Look look, String key) throws IOException {
        if (svgs >= MAX_SVGS || svgPixels > MAX_SVG_PIXELS) {
            warn("Only the first SVG pictures of a very large page were drawn");
            return null;
        }
        String cacheKey = key == null ? null : key + "|" + box[0] + "x" + box[1] + "|" + look.color;
        Svg.Painted p = cacheKey == null ? null : painted.get(cacheKey);
        if (p == null) {
            svgs++;
            p = Svg.paint(svg, ids, box[0], box[1], look.color, svgWork);
            if (p == null) {
                return null;
            }
            svgPixels += (long) p.pixelWidth() * p.pixelHeight();
            if (cacheKey != null) {
                painted.put(cacheKey, p);
            }
        }
        Object mediaKey = cacheKey != null ? cacheKey : "svg:" + (++added);
        Picture.MediaRef ref = sink.media(mediaKey);
        if (ref == null) {
            ref = sink.media(p.png(), "png", p.pixelWidth(), p.pixelHeight(), mediaKey);
        }
        return new Picture(ref, p.width(), p.height());
    }

    private Map<String, Element> ids(Document doc) {
        if (documentIds == null) {
            documentIds = new HashMap<>();
            if (doc != null) {
                collectIds(doc, documentIds);
            }
        }
        return documentIds;
    }

    private static void collectIds(Element root, Map<String, Element> into) {
        for (Element e : root.select("[id]")) {
            if (into.size() >= 50_000) {
                return;
            }
            into.putIfAbsent(e.id(), e);
        }
    }

    // The size a picture is shown at, in points, from its CSS and its natural size
    private static float[] box(Map<String, String> d, Look look, float natW, float natH, float maxWidth, float maxHeight) {
        float ratio = aspectRatio(d.get("aspect-ratio"));
        if (ratio > 0) {
            natH = natW / ratio;
        }
        Float w = size(d.get("width"), look, maxWidth);
        // A percentage height needs a container of known height, which flowing content never has
        Float h = percent(d.get("height")) ? null : size(d.get("height"), look, maxHeight);
        String fit = d.getOrDefault("object-fit", "fill").strip().toLowerCase(Locale.ROOT);
        if (w != null && h != null && (fit.equals("cover") || fit.equals("contain") || fit.equals("scale-down"))) {
            float s = Math.min(w / natW, h / natH);
            w = natW * s;
            h = natH * s;
        } else if (w == null && h == null) {
            w = natW;
            h = natH;
        } else if (w == null) {
            w = h * natW / natH;
        } else if (h == null) {
            h = w * natH / natW;
        }
        Float maxW = size(d.get("max-width"), look, maxWidth);
        Float maxH = percent(d.get("max-height")) ? null : size(d.get("max-height"), look, maxHeight);
        float room = Math.min(maxWidth, maxW == null ? Float.MAX_VALUE : maxW);
        float tall = Math.min(maxHeight, maxH == null ? Float.MAX_VALUE : maxH);
        float scale = Math.min(1f, Math.min(room / Math.max(0.1f, w), tall / Math.max(0.1f, h)));
        return new float[] {Math.max(0.5f, w * scale), Math.max(0.5f, h * scale)};
    }

    static float aspectRatio(String v) {
        if (v == null) {
            return 0;
        }
        String s = v.strip().toLowerCase(Locale.ROOT).replace("auto", "").strip();
        String[] parts = s.split("/");
        try {
            float w = Float.parseFloat(parts[0].strip());
            float h = parts.length > 1 ? Float.parseFloat(parts[1].strip()) : 1f;
            float r = w / h;
            return parts.length <= 2 && r > 0.001f && r < 1000f ? r : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean percent(String v) {
        return v != null && v.strip().endsWith("%");
    }

    private static Float size(String v, Look look, float room) {
        if (v == null || v.equalsIgnoreCase("auto")) {
            return null;
        }
        Float pt = Css.length(v, look.size, room);
        return pt == null || pt <= 0 ? null : pt;
    }

    private Picture.MediaRef media(String src) throws IOException {
        if (src.isEmpty()) {
            return null;
        }
        if (!src.regionMatches(true, 0, "data:", 0, 5)) {
            warn(NOT_FETCHED);
            return null;
        }
        Picture.MediaRef known = seen.get(src);
        if (known != null) {
            return known;
        }
        int comma = src.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String head = src.substring(5, comma).toLowerCase(Locale.ROOT);
        if (!head.endsWith(";base64")) {
            warn("Left out pictures that are not base64 PNG, JPEG, GIF, BMP or SVG data");
            return null;
        }
        dataChars += src.length() - comma;
        if (dataChars > MAX_DATA_CHARS) {
            warn("Left out pictures past the first 96 MB of picture data");
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(src.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            warn("Left out a picture whose data is not valid base64");
            return null;
        }
        Picture.MediaRef ref = decoded(bytes, "html:" + (++added));
        if (ref == null) {
            return null;
        }
        if (src.length() < (1 << 20)) {
            seen.put(src, ref);
        }
        return ref;
    }

    private Picture.MediaRef decoded(byte[] bytes, Object key) throws IOException {
        MediaBytes pic = WebP.is(bytes) ? WebP.redraw(bytes) : MediaBytes.of(bytes);
        if (pic == null) {
            warn("Left out a picture that could not be read (AVIF is not drawn), or was too large to draw");
            return null;
        }
        return sink.media(pic.bytes(), pic.ext(), pic.pixelWidth(), pic.pixelHeight(), key);
    }

    private void warn(String w) {
        if (!warnings.contains(w)) {
            warnings.add(w);
        }
    }
}
