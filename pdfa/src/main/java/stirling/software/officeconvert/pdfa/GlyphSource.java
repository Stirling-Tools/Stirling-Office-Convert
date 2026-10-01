package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TrueTypeFont;

import stirling.software.officeconvert.topdf.font.EmbeddableFont;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class GlyphSource implements Closeable {

    private final FontLibrary library;

    private final EmbeddableFont primary;

    private final boolean bold;

    private final boolean italic;

    private final boolean symbol;

    private final List<EmbeddableFont> opened = new ArrayList<>();

    private GlyphSource(FontLibrary library, EmbeddableFont primary, boolean bold, boolean italic, boolean symbol) {
        this.library = library;
        this.primary = primary;
        this.bold = bold;
        this.italic = italic;
        this.symbol = symbol;
        opened.add(primary);
    }

    static GlyphSource open(FontLibrary library, BaseFontName name) throws IOException {
        boolean symbol = "Symbol".equals(name.family()) || "ZapfDingbats".equals(name.family());
        FontFace face = library.find(name.family(), name.bold(), name.italic());
        EmbeddableFont f = truetype(face);
        if (f == null) {
            f = truetype(library.lastResort(name.bold(), name.italic()));
        }
        if (f == null) {
            throw new IOException("No TrueType font is available to stand in for " + name.family());
        }
        return new GlyphSource(library, f, name.bold(), name.italic(), symbol);
    }

    private static EmbeddableFont truetype(FontFace face) {
        if (face == null) {
            return null;
        }
        try {
            EmbeddableFont f = EmbeddableFont.open(face);
            if (f.font().getTableMap().get("glyf") == null) {
                f.close();
                return null;
            }
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    TrueTypeFont font() {
        return primary.font();
    }

    String description() {
        return primary.description();
    }

    int glyph(int code, String name, String unicode) throws IOException {
        TrueTypeFont ttf = primary.font();
        if (symbol && name != null) {
            int gid = ttf.nameToGID(name);
            if (gid > 0) {
                return gid;
            }
            CmapSubtable sym = ttf.getCmap() == null ? null : ttf.getCmap().getSubtable(3, 0);
            if (sym != null && code >= 0) {
                gid = sym.getGlyphId(0xF000 + code);
                if (gid > 0) {
                    return gid;
                }
            }
        }
        if (unicode != null && unicode.codePointCount(0, unicode.length()) == 1) {
            CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
            int gid = cmap == null ? 0 : cmap.getGlyphId(unicode.codePointAt(0));
            if (gid > 0) {
                return gid;
            }
        }
        if (name != null && !".notdef".equals(name)) {
            int gid = ttf.nameToGID(name);
            if (gid > 0) {
                return gid;
            }
        }
        return 0;
    }

    GeneralPath fallback(String unicode, int unitsPerEm) {
        if (unicode == null || unicode.codePointCount(0, unicode.length()) != 1) {
            return null;
        }
        int cp = unicode.codePointAt(0);
        FontFace face = library.fallback(cp, bold, italic);
        if (face == null) {
            return null;
        }
        EmbeddableFont f = truetype(face);
        if (f == null) {
            return null;
        }
        opened.add(f);
        try {
            TrueTypeFont ttf = f.font();
            CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
            int gid = cmap == null ? 0 : cmap.getGlyphId(cp);
            GlyphData g = gid > 0 ? ttf.getGlyph().getGlyph(gid) : null;
            if (g == null) {
                return null;
            }
            GeneralPath p = g.getPath();
            double k = unitsPerEm / (double) ttf.getUnitsPerEm();
            p.transform(AffineTransform.getScaleInstance(k, k));
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        for (EmbeddableFont f : opened) {
            try {
                f.close();
            } catch (IOException ignored) {
            }
        }
    }
}
