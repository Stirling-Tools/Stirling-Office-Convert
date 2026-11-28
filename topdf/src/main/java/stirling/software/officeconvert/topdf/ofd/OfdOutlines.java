package stirling.software.officeconvert.topdf.ofd;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

final class OfdOutlines {

    static final int MAX_CACHED = 20_000;

    private CFFFont cff;

    private TrueTypeFont ttf;

    private double perEm;

    private byte[] pending;

    private final Map<Integer, Shape> cache = new HashMap<>();

    private OfdOutlines(CFFFont cff, TrueTypeFont ttf, double perEm) {
        this.cff = cff;
        this.ttf = ttf;
        this.perEm = perEm;
    }

    static OfdOutlines lazy(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        OfdOutlines o = new OfdOutlines(null, null, 0);
        o.pending = data;
        return o;
    }

    static OfdOutlines of(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        try {
            if (data[0] == 1 && data[1] == 0) {
                List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
                if (fonts.isEmpty()) {
                    return null;
                }
                CFFFont f = fonts.get(0);
                List<Number> m = f.getFontMatrix();
                double scale = m != null && !m.isEmpty() && m.get(0).doubleValue() > 0 ? m.get(0).doubleValue() : 0.001;
                return new OfdOutlines(f, null, scale);
            }
            TrueTypeFont t = new TTFParser(true).parse(new RandomAccessReadBuffer(data));
            int upm = t.getUnitsPerEm();
            return new OfdOutlines(null, t, 1.0 / (upm > 0 ? upm : 1000));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    Shape outline(int gid) {
        if (gid <= 0) {
            return null;
        }
        if (pending != null) {
            OfdOutlines read = of(pending);
            pending = null;
            if (read != null) {
                cff = read.cff;
                ttf = read.ttf;
                perEm = read.perEm;
            }
        }
        Shape known = cache.get(gid);
        if (known != null || cache.containsKey(gid)) {
            return known;
        }
        Shape made = null;
        try {
            GeneralPath path = null;
            if (cff != null && gid < cff.getNumCharStrings()) {
                int code = cff instanceof CFFCIDFont ? cff.getCharset().getCIDForGID(gid) : gid;
                path = cff.getType2CharString(code).getPath();
            } else if (ttf != null && gid < ttf.getNumberOfGlyphs()) {
                GlyphData g = ttf.getGlyph().getGlyph(gid);
                path = g == null ? null : g.getPath();
            }
            if (path != null) {
                made = new AffineTransform(perEm, 0, 0, -perEm, 0, 0).createTransformedShape(path);
            }
        } catch (IOException | RuntimeException e) {
            made = null;
        }
        if (cache.size() < MAX_CACHED) {
            cache.put(gid, made);
        }
        return made;
    }
}
