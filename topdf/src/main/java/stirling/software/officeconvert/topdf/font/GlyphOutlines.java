package stirling.software.officeconvert.topdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/** Glyph outlines of an installed face by code point, from TrueType or CFF outlines alike. */
public final class GlyphOutlines implements Closeable {

    private final FontProgram.Opened opened;

    private final CFFFont cff;

    private GlyphOutlines(FontProgram.Opened opened, CFFFont cff) {
        this.opened = opened;
        this.cff = cff;
    }

    public static GlyphOutlines open(FontFace face) throws IOException {
        Objects.requireNonNull(face, "face");
        FontEntry entry = face.program().entry();
        FontProgram.Opened opened = FontProgram.open(entry);
        try {
            CFFFont cff = null;
            if (opened.font().getTableMap().get("glyf") == null) {
                // CFF faces open metrics-only, so the outlines are read from the file itself
                byte[] table = FontScanner.table(entry, "CFF ", FontProgram.MAX_WHOLE_BYTES);
                List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(table));
                if (fonts.isEmpty()) {
                    throw new IOException("the CFF has no font");
                }
                cff = fonts.get(0);
            }
            return new GlyphOutlines(opened, cff);
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** The outline of {@code codePoint} scaled to {@code unitsPerEm}, or null when the face lacks it. */
    public GeneralPath path(int codePoint, int unitsPerEm) throws IOException {
        TrueTypeFont ttf = opened.font();
        CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
        int gid = cmap == null ? 0 : cmap.getGlyphId(codePoint);
        if (gid <= 0 || gid >= ttf.getNumberOfGlyphs()) {
            return null;
        }
        GeneralPath p;
        double k;
        if (cff == null) {
            GlyphData g = ttf.getGlyph().getGlyph(gid);
            if (g == null) {
                return null;
            }
            p = g.getPath();
            k = unitsPerEm / (double) ttf.getUnitsPerEm();
        } else {
            int selector = cff instanceof CFFCIDFont ? cff.getCharset().getCIDForGID(gid) : gid;
            p = cff instanceof CFFCIDFont cid ? cid.getType2CharString(selector).getPath()
                    : ((CFFType1Font) cff).getType2CharString(selector).getPath();
            List<Number> m = cff.getFontMatrix();
            double scale = m == null || m.isEmpty() ? 0.001 : m.get(0).doubleValue();
            k = unitsPerEm * scale;
        }
        p.transform(AffineTransform.getScaleInstance(k, k));
        return p;
    }

    @Override
    public void close() throws IOException {
        opened.close();
    }
}
