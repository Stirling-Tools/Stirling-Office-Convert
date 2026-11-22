package stirling.software.officeconvert.topdf.font;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Path;
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

    // Shared across conversions, so keyed by the host font file alone: a document's own font never goes in
    private record Key(Path file, long offset, int index) {}

    private static final Lru<Key, SoftReference<CFFFont>> PARSED = new Lru<>(4);

    private static final Lru<Key, SoftReference<LazyCidCff>> LAZY = new Lru<>(8);

    private static final Lru<Key, Boolean> NOT_LAZY = new Lru<>(64);

    private final Key key;

    private CFFFont outlines;

    private LazyCidCff lazy;

    private boolean notLazy;

    private EmbeddableFont(FontProgram.Opened opened, FontEntry entry) {
        this.opened = opened;
        this.entry = entry;
        this.description = entry.describe();
        this.key = entry.file() == null ? null : new Key(entry.file(), entry.offset(), entry.index());
    }

    static int shared() {
        return PARSED.size() + LAZY.size() + NOT_LAZY.size();
    }

    private static <V> V get(Lru<Key, V> cache, Key key) {
        return key == null ? null : cache.get(key);
    }

    private static <V> void put(Lru<Key, V> cache, Key key, V value) {
        if (key != null) {
            cache.put(key, value);
        }
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
        LazyCidCff cidOutlines = lazy();
        GeneralPath p;
        List<Number> matrix;
        if (cidOutlines != null) {
            if (gid >= cidOutlines.glyphCount()) {
                return null;
            }
            p = new GeneralPath(cidOutlines.path(gid));
            matrix = cidOutlines.fontMatrix();
        } else {
            CFFFont cff = cff();
            if (gid >= cff.getNumCharStrings()) {
                return null;
            }
            int selector = cff instanceof CFFCIDFont cid ? cid.getCharset().getCIDForGID(gid) : gid;
            synchronized (cff) {
                p = new GeneralPath(cff.getType2CharString(selector).getPath());
            }
            matrix = cff.getFontMatrix();
        }
        if (matrix != null && matrix.size() == 6) {
            double k = font().getUnitsPerEm() * matrix.get(0).doubleValue();
            if (k > 0 && Math.abs(k - 1) > 1e-6) {
                p.transform(AffineTransform.getScaleInstance(k, k));
            }
        }
        return p;
    }

    private LazyCidCff lazy() {
        if (lazy != null || notLazy || get(NOT_LAZY, key) != null) {
            return lazy;
        }
        SoftReference<LazyCidCff> known = get(LAZY, key);
        lazy = known == null ? null : known.get();
        if (lazy == null) {
            try {
                lazy = LazyCidCff.open(entry);
                put(LAZY, key, new SoftReference<>(lazy));
            } catch (IOException e) {
                notLazy = true;
                if (!Failures.interrupted(e) && !Failures.fileSystem(e)) {
                    put(NOT_LAZY, key, Boolean.TRUE);
                }
            }
        }
        return lazy;
    }

    private CFFFont cff() throws IOException {
        if (outlines == null) {
            SoftReference<CFFFont> known = get(PARSED, key);
            outlines = known == null ? null : known.get();
        }
        if (outlines == null) {
            byte[] data = FontScanner.table(entry, "CFF ", FontProgram.MAX_WHOLE_BYTES);
            List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(data));
            if (fonts.isEmpty()) {
                throw new IOException("the font has no CFF outlines");
            }
            outlines = fonts.get(0);
            put(PARSED, key, new SoftReference<>(outlines));
        }
        return outlines;
    }

    @Override
    public void close() throws IOException {
        opened.close();
    }
}
