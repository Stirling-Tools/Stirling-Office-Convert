package stirling.software.officeconvert.svg;

import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;

import stirling.software.officeconvert.html.FixedHtml;

final class SvgFonts {

    static final int MAX_FONT_BYTES = 4 << 20;

    static final long MAX_FONTS_BYTES = 64L << 20;

    static final int MAX_FONTS = 512;

    static final int MAX_CHECKED_GLYPHS = 65_536;

    private static final List<String> KEEP_TABLES =
            List.of("head", "hhea", "loca", "maxp", "cvt ", "prep", "glyf", "hmtx", "fpgm", "name", "OS/2", "post", "cmap");

    private final FixedHtml.FontSource source;
    private final Map<Object, Face> faces = new HashMap<>();
    private long bytes;
    private int checked;

    SvgFonts(FixedHtml.FontSource source) {
        this.source = source;
    }

    final class Face {
        final String family;
        final String fallback;
        final boolean bold;
        final boolean italic;
        final boolean embedded;
        final PDFont font;
        private final TrueTypeFont web;
        private final byte[] program;
        private final CmapLookup cmap;
        private final float scale;
        private final Map<Long, Boolean> glyphs = new HashMap<>();
        final Set<Integer> used = new TreeSet<>();

        Face(PDFont font, String family, TrueTypeFont web, byte[] program, CmapLookup cmap, float scale) {
            this.font = font;
            this.family = family;
            this.web = web;
            this.program = program;
            this.cmap = cmap;
            this.scale = scale;
            this.embedded = font.isEmbedded();
            this.fallback = FixedHtml.family(name(font)).replace("\"", "");
            String raw = name(font).toLowerCase(Locale.ROOT);
            PDFontDescriptor fd = font.getFontDescriptor();
            this.bold = raw.contains("bold") || raw.contains("black") || raw.contains("heavy");
            this.italic = raw.contains("italic") || raw.contains("oblique")
                    || fd != null && (fd.isItalic() || fd.getItalicAngle() < -1);
        }

        boolean web() {
            return web != null;
        }

        boolean text(int code, int codePoint) {
            if (!embedded) {
                return !(font instanceof PDType3Font);
            }
            if (web == null) {
                return false;
            }
            long key = ((long) code << 32) | (codePoint & 0xFFFFFFFFL);
            Boolean known = glyphs.get(key);
            if (known != null) {
                return known;
            }
            if (checked >= MAX_CHECKED_GLYPHS) {
                return false;
            }
            checked++;
            boolean ok = same(code, codePoint);
            glyphs.put(key, ok);
            return ok;
        }

        private boolean same(int code, int codePoint) {
            try {
                int gid = cmap.getGlyphId(codePoint);
                if (gid <= 0 || !(font instanceof PDVectorFont vector)) {
                    return false;
                }
                GeneralPath own = vector.getNormalizedPath(code);
                GlyphData data = web.getGlyph().getGlyph(gid);
                Rectangle2D a = own == null ? new Rectangle2D.Double() : own.getBounds2D();
                var fm = font.getFontMatrix();
                if (own != null && fm != null && Math.abs(fm.getScaleX() * 1000 - 1) > 1e-3) {
                    a = new java.awt.geom.AffineTransform(fm.getScaleX() * 1000, fm.getShearY() * 1000,
                            fm.getShearX() * 1000, fm.getScaleY() * 1000, 0, 0).createTransformedShape(a).getBounds2D();
                }
                Rectangle2D b = data == null ? new Rectangle2D.Double() : data.getPath().getBounds2D();
                b = new Rectangle2D.Double(b.getX() * scale, b.getY() * scale, b.getWidth() * scale, b.getHeight() * scale);
                boolean emptyA = a.getWidth() <= 0 && a.getHeight() <= 0;
                boolean emptyB = b.getWidth() <= 0 && b.getHeight() <= 0;
                if (emptyA || emptyB) {
                    return emptyA == emptyB;
                }
                double tol = Math.max(12, 0.04 * Math.max(Math.max(a.getWidth(), a.getHeight()),
                        Math.max(b.getWidth(), b.getHeight())));
                return Math.abs(a.getMinX() - b.getMinX()) <= tol && Math.abs(a.getMaxX() - b.getMaxX()) <= tol
                        && Math.abs(a.getMinY() - b.getMinY()) <= tol && Math.abs(a.getMaxY() - b.getMaxY()) <= tol;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        byte[] subset() {
            if (used.isEmpty()) {
                return null;
            }
            try {
                TTFSubsetter subsetter = new TTFSubsetter(web, KEEP_TABLES);
                subsetter.addAll(used);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                subsetter.writeToStream(out);
                byte[] b = out.toByteArray();
                return b.length > 0 && b.length < program.length ? b : program;
            } catch (IOException | RuntimeException e) {
                return program;
            }
        }
    }

    Face face(PDFont font) {
        Object key = font.getCOSObject();
        Face face = faces.get(key);
        if (face != null) {
            return face;
        }
        if (faces.size() >= MAX_FONTS) {
            return new Face(font, "f0", null, null, null, 1);
        }
        face = load(font);
        faces.put(key, face);
        return face;
    }

    private Face load(PDFont font) {
        String family = "f" + (faces.size() + 1);
        if (source == null || !font.isEmbedded() || font instanceof PDType3Font || bytes >= MAX_FONTS_BYTES) {
            return new Face(font, family, null, null, null, 1);
        }
        try {
            byte[] ttf = source.webFont(font);
            if (ttf == null || ttf.length == 0 || ttf.length > MAX_FONT_BYTES || bytes + ttf.length > MAX_FONTS_BYTES) {
                return new Face(font, family, null, null, null, 1);
            }
            TrueTypeFont parsed = new TTFParser(true).parse(new RandomAccessReadBuffer(ttf));
            CmapLookup cmap = parsed.getUnicodeCmapLookup(false);
            if (cmap == null) {
                parsed.close();
                return new Face(font, family, null, null, null, 1);
            }
            bytes += ttf.length;
            int upem = parsed.getUnitsPerEm();
            return new Face(font, family, parsed, ttf, cmap, upem > 0 ? 1000f / upem : 1);
        } catch (IOException | RuntimeException e) {
            return new Face(font, family, null, null, null, 1);
        }
    }

    static String css(List<Face> used) {
        StringBuilder sb = new StringBuilder();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        long total = 0;
        for (Face f : used) {
            if (!f.web() || seen.put(f.family, true) != null) {
                continue;
            }
            byte[] b = f.subset();
            if (b == null || total + b.length > MAX_FONTS_BYTES) {
                continue;
            }
            total += b.length;
            sb.append("@font-face{font-family:'").append(f.family).append("';src:url(data:font/ttf;base64,")
                    .append(Base64.getEncoder().encodeToString(b)).append(") format('truetype')}\n");
        }
        return sb.toString();
    }

    void clearUsed() {
        for (Face f : faces.values()) {
            f.used.clear();
        }
    }

    void close() {
        for (Face f : faces.values()) {
            if (f.web != null) {
                try {
                    f.web.close();
                } catch (IOException ignored) {
                    continue;
                }
            }
        }
        faces.clear();
    }

    static String name(PDFont font) {
        String name = font.getName() == null ? "" : font.getName();
        int plus = name.indexOf('+');
        return plus == 6 ? name.substring(7) : name;
    }
}
