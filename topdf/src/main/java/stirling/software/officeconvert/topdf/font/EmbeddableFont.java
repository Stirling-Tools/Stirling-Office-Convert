package stirling.software.officeconvert.topdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.List;
import java.util.Objects;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

public final class EmbeddableFont implements Closeable {

    private final FontProgram.Opened opened;

    private final FontEntry entry;

    private final String description;

    private static final Lru<FontEntry, SoftReference<CFFFont>> PARSED = new Lru<>(4);

    private CFFFont outlines;

    private EmbeddableFont(FontProgram.Opened opened, FontEntry entry) {
        this.opened = opened;
        this.entry = entry;
        this.description = entry.describe();
    }

    public static EmbeddableFont open(FontFace face) throws IOException {
        Objects.requireNonNull(face, "face");
        FontEntry entry = face.program().entry();
        return new EmbeddableFont(FontProgram.open(entry), entry);
    }

    public TrueTypeFont font() {
        return opened.font();
    }

    public String description() {
        return description;
    }

    public boolean trueTypeOutlines() {
        try {
            return !entry.postScript() && font().getTableMap().get("glyf") != null
                    && font().getIndexToLocation() != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public GeneralPath outline(int gid) throws IOException {
        if (gid <= 0 || gid >= font().getNumberOfGlyphs()) {
            return null;
        }
        if (!entry.postScript()) {
            GlyphTable glyf = font().getGlyph();
            GlyphData g = glyf == null ? null : glyf.getGlyph(gid);
            return g == null ? null : g.getPath();
        }
        CFFFont cff = cff();
        if (gid >= cff.getNumCharStrings()) {
            return null;
        }
        int selector = cff instanceof CFFCIDFont cid ? cid.getCharset().getCIDForGID(gid) : gid;
        GeneralPath p;
        synchronized (cff) {
            p = new GeneralPath(cff.getType2CharString(selector).getPath());
        }
        List<Number> matrix = cff.getFontMatrix();
        if (matrix != null && matrix.size() == 6) {
            double k = font().getUnitsPerEm() * matrix.get(0).doubleValue();
            if (k > 0 && Math.abs(k - 1) > 1e-6) {
                p.transform(AffineTransform.getScaleInstance(k, k));
            }
        }
        return p;
    }

    private CFFFont cff() throws IOException {
        if (outlines == null) {
            SoftReference<CFFFont> known = PARSED.get(entry);
            outlines = known == null ? null : known.get();
        }
        if (outlines == null) {
            byte[] data = FontScanner.table(entry, "CFF ", FontProgram.MAX_WHOLE_BYTES);
            List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
            if (fonts.isEmpty()) {
                throw new IOException("the font has no CFF outlines");
            }
            outlines = fonts.get(0);
            PARSED.put(entry, new SoftReference<>(outlines));
        }
        return outlines;
    }

    @Override
    public void close() throws IOException {
        opened.close();
    }
}
