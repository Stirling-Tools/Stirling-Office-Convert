package stirling.software.officeconvert.topdf.font;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphTable;
import org.apache.fontbox.ttf.HeaderTable;
import org.apache.fontbox.ttf.HorizontalHeaderTable;
import org.apache.fontbox.ttf.HorizontalMetricsTable;
import org.apache.fontbox.ttf.KerningSubtable;
import org.apache.fontbox.ttf.KerningTable;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

final class FontProgram {

    private static final int DIRECT = 0x250;

    private final FontEntry entry;

    private final FontMetrics metrics;

    private final int[] codePoints;

    private final int[] glyphs;

    private final int[] advances;

    private final int[] direct;

    private final boolean symbol;

    private final KerningSubtable kerning;

    private final boolean notdefInk;

    private final GposKerning gpos;

    private volatile int[] reverse;

    private volatile Object awt;

    private final Map<Integer, float[]> inks = new ConcurrentHashMap<>();

    private final Map<Long, Integer> hinted = new ConcurrentHashMap<>();

    private final Map<Integer, Font> pixelFonts = new ConcurrentHashMap<>();

    static final long MAX_WHOLE_BYTES = 64L << 20;

    private static final long GPOS_BYTES = 8L << 20;

    // Office kerns with kern tables only; these metric clones carry their original's kern table as GPOS pairs
    private static final Set<String> GPOS_CLONES = Set.of("Carlito", "Caladea");

    private FontProgram(FontEntry entry, TrueTypeFont ttf) throws IOException {
        this.entry = entry;
        HeaderTable head = ttf.getHeader();
        HorizontalHeaderTable hhea = ttf.getHorizontalHeader();
        OS2WindowsMetricsTable os2 = ttf.getOS2Windows();
        PostScriptTable post = ttf.getPostScript();
        int upm = head.getUnitsPerEm();
        if (upm < 16 || upm > 16384) {
            upm = 1000;
        }
        int numGlyphs = Math.max(1, ttf.getNumberOfGlyphs());
        HorizontalMetricsTable hmtx = ttf.getHorizontalMetrics();
        advances = new int[numGlyphs];
        for (int g = 0; g < numGlyphs; g++) {
            advances[g] = Math.max(0, hmtx.getAdvanceWidth(g));
        }
        CmapLookup cmap = ttf.getUnicodeCmapLookup(false);
        if (cmap == null) {
            throw new IOException("no character map");
        }
        symbol = cmap instanceof CmapSubtable s && s.getPlatformId() == 3 && s.getPlatformEncodingId() == 0;
        long[] pairs = new long[Math.min(numGlyphs * 2, 1 << 20)];
        int n = 0;
        for (int g = 1; g < numGlyphs; g++) {
            List<Integer> codes = cmap.getCharCodes(g);
            if (codes == null) {
                continue;
            }
            for (int cp : codes) {
                if (cp < 0 || cp > Character.MAX_CODE_POINT) {
                    continue;
                }
                if (n == pairs.length) {
                    pairs = Arrays.copyOf(pairs, pairs.length * 2);
                }
                pairs[n++] = (long) cp << 32 | g;
            }
        }
        Arrays.sort(pairs, 0, n);
        int[] cps = new int[n];
        int[] gids = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int cp = (int) (pairs[i] >>> 32);
            if (m > 0 && cps[m - 1] == cp) {
                continue;
            }
            cps[m] = cp;
            gids[m] = cmap.getGlyphId(cp);
            if (gids[m] > 0 && gids[m] < numGlyphs) {
                m++;
            }
        }
        codePoints = Arrays.copyOf(cps, m);
        glyphs = Arrays.copyOf(gids, m);
        direct = new int[DIRECT];
        for (int cp = 0; cp < DIRECT; cp++) {
            direct[cp] = search(cp);
        }
        kerning = kerning(ttf);
        notdefInk = notdefInk(ttf);
        gpos = kerning == null && GPOS_CLONES.contains(entry.family()) ? gpos(ttf) : null;
        int typoAsc = os2 == null ? hhea.getAscender() : os2.getTypoAscender();
        int typoDesc = os2 == null ? hhea.getDescender() : os2.getTypoDescender();
        int typoGap = os2 == null ? hhea.getLineGap() : os2.getTypoLineGap();
        int winAsc = os2 == null ? hhea.getAscender() : os2.getWinAscent();
        int winDesc = os2 == null ? -hhea.getDescender() : os2.getWinDescent();
        boolean useTypo = os2 != null && (os2.getFsSelection() & 0x80) != 0;
        int capHeight = os2 != null && os2.getVersion() >= 2 ? os2.getCapHeight() : 0;
        int xHeight = os2 != null && os2.getVersion() >= 2 ? os2.getHeight() : 0;
        if (capHeight <= 0) {
            capHeight = Math.round(0.7f * upm);
        }
        if (xHeight <= 0) {
            xHeight = Math.round(0.5f * upm);
        }
        int ulPos = post == null ? 0 : post.getUnderlinePosition();
        int ulThick = post == null ? 0 : post.getUnderlineThickness();
        if (ulThick <= 0) {
            ulThick = Math.max(1, upm / 20);
            ulPos = -upm / 10;
        }
        int stPos = os2 == null ? 0 : os2.getStrikeoutPosition();
        int stSize = os2 == null ? 0 : os2.getStrikeoutSize();
        if (stSize <= 0) {
            stSize = ulThick;
            stPos = Math.round(xHeight / 2f + stSize / 2f);
        }
        metrics = new FontMetrics(upm, hhea.getAscender(), hhea.getDescender(), hhea.getLineGap(), typoAsc, typoDesc,
                typoGap, useTypo, winAsc, winDesc, capHeight, xHeight, ulPos, ulThick, stPos, stSize,
                post == null ? 0 : post.getItalicAngle(), post != null && post.getIsFixedPitch() != 0, head.getXMin(),
                head.getYMin(), head.getXMax(), head.getYMax());
    }

    static FontProgram load(FontEntry entry) throws IOException {
        try (Opened opened = open(entry)) {
            return new FontProgram(entry, opened.font());
        }
    }

    record Opened(TrueTypeFont font, Closeable owner, String pooled, long bytes) implements Closeable {
        @Override
        public void close() throws IOException {
            if (pooled == null || !idle(this)) {
                owner.close();
            }
        }
    }

    // Parsed system fonts are lent to one conversion at a time and kept for the next, instead of parsed again
    static final int POOL_FONTS = 64;

    static final long POOL_BYTES = 64L << 20;

    static final long POOL_FILE_BYTES = 8L << 20;

    private record Idle(SoftReference<Opened> font, long bytes) {}

    private static final Map<String, Idle> IDLE = new LinkedHashMap<>(64, 0.75f, true);

    private static long idleBytes;

    private static boolean idle(Opened o) {
        List<Opened> evicted = new ArrayList<>();
        synchronized (IDLE) {
            Idle there = IDLE.get(o.pooled());
            if (there != null && there.font().get() != null) {
                return false;
            }
            if (there != null) {
                idleBytes -= there.bytes();
            }
            IDLE.put(o.pooled(), new Idle(new SoftReference<>(o), o.bytes()));
            idleBytes += o.bytes();
            Iterator<Idle> it = IDLE.values().iterator();
            while ((IDLE.size() > POOL_FONTS || idleBytes > POOL_BYTES) && it.hasNext()) {
                Idle old = it.next();
                it.remove();
                idleBytes -= old.bytes();
                Opened font = old.font().get();
                if (font != null && font != o) {
                    evicted.add(font);
                }
            }
            if (!IDLE.containsKey(o.pooled())) {
                return false;
            }
        }
        for (Opened old : evicted) {
            try {
                old.owner().close();
            } catch (IOException ignored) {
                // in-memory fonts hold nothing that needs closing
            }
        }
        return true;
    }

    private static Opened take(String key) {
        synchronized (IDLE) {
            Idle idle = IDLE.remove(key);
            if (idle == null) {
                return null;
            }
            idleBytes -= idle.bytes();
            return idle.font().get();
        }
    }

    private static String poolKey(FontEntry entry) {
        return entry.data() != null || entry.file() == null ? null : entry.file().toAbsolutePath() + "#" + entry.index();
    }

    private static byte[] poolable(FontEntry entry) {
        try {
            return Files.size(entry.file()) > POOL_FILE_BYTES ? null : Files.readAllBytes(entry.file());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Opened open(FontEntry entry) throws IOException {
        String key = poolKey(entry);
        Opened reused = key == null ? null : take(key);
        if (reused != null) {
            return reused;
        }
        byte[] bytes = key == null ? null : poolable(entry);
        byte[] data = bytes != null ? bytes : entry.data();
        TrueTypeFont ttf;
        Closeable owner;
        if (entry.index() < 0) {
            ttf = data != null ? new TTFParser().parse(new RandomAccessReadBuffer(data))
                    : new TTFParser().parse(new RandomAccessReadBufferedFile(entry.file().toFile()));
            owner = ttf;
        } else {
            TrueTypeCollection ttc = data != null ? new TrueTypeCollection(new ByteArrayInputStream(data))
                    : new TrueTypeCollection(entry.file().toFile());
            try {
                ttf = entry.postScriptName() == null ? null : ttc.getFontByName(entry.postScriptName());
                if (ttf == null) {
                    TrueTypeFont[] found = new TrueTypeFont[1];
                    int[] at = {0};
                    ttc.processAllFonts(f -> {
                        if (at[0]++ == entry.index()) {
                            found[0] = f;
                        }
                    });
                    ttf = found[0];
                }
                if (ttf == null) {
                    throw new IOException("face " + entry.index() + " is missing from the collection");
                }
            } catch (IOException | RuntimeException e) {
                ttc.close();
                throw e;
            }
            owner = ttc;
        }
        ttf.setEnableGsub(false);
        return bytes == null ? new Opened(ttf, owner, null, 0) : new Opened(ttf, owner, key, bytes.length);
    }

    FontEntry entry() {
        return entry;
    }

    // Some fonts (Liberation Serif) leave .notdef empty, so a missing character would not show at all
    boolean notdefInk() {
        return notdefInk;
    }

    private static boolean notdefInk(TrueTypeFont ttf) {
        try {
            GlyphTable glyf = ttf.getGlyph();
            GlyphData notdef = glyf == null ? null : glyf.getGlyph(0);
            return notdef == null || notdef.getDescription().getContourCount() != 0;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    FontMetrics metrics() {
        return metrics;
    }

    boolean symbol() {
        return symbol;
    }

    int glyph(int codePoint) {
        if (codePoint >= 0 && codePoint < DIRECT) {
            int g = direct[codePoint];
            if (g > 0 || !symbol) {
                return g;
            }
        }
        int g = search(codePoint);
        if (g == 0 && symbol && codePoint >= 0x20 && codePoint <= 0xFF) {
            g = search(0xF000 + codePoint);
        }
        return g;
    }

    int encode(int codePoint) {
        if (search(codePoint) > 0) {
            return codePoint;
        }
        return symbol && codePoint >= 0x20 && codePoint <= 0xFF ? 0xF000 + codePoint : codePoint;
    }

    int glyphCount() {
        return advances.length;
    }

    int codePointOf(int glyph) {
        int[] r = reverse;
        if (r == null) {
            r = new int[advances.length];
            for (int i = codePoints.length - 1; i >= 0; i--) {
                if (glyphs[i] < r.length) {
                    r[glyphs[i]] = codePoints[i];
                }
            }
            reverse = r;
        }
        return glyph > 0 && glyph < r.length ? r[glyph] : 0;
    }

    Font awtFont() {
        Object known = awt;
        if (known == null) {
            Object made;
            try {
                Font f = createAwtFont();
                made = f == null ? Boolean.FALSE : f.deriveFont((float) metrics.unitsPerEm());
            } catch (IOException | FontFormatException | RuntimeException | LinkageError | InternalError e) {
                made = Boolean.FALSE;
            }
            awt = made;
            known = made;
        }
        return known instanceof Font f ? f : null;
    }

    private Font createAwtFont() throws IOException, FontFormatException {
        if (symbol) {
            return null;
        }
        if (entry.file() != null && entry.index() < 0) {
            return Font.createFont(Font.TRUETYPE_FONT, entry.file().toFile());
        }
        if (entry.file() != null) {
            Font[] faces = Font.createFonts(entry.file().toFile());
            for (Font f : faces) {
                if (entry.postScriptName() != null && entry.postScriptName().equals(f.getPSName())) {
                    return f;
                }
            }
            return entry.index() < faces.length ? faces[entry.index()] : null;
        }
        if (FontLibrary.bundled(entry)) {
            try (InputStream in = new ByteArrayInputStream(entry.data())) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        }
        return null;
    }

    Shape glyphOutline(int glyph) {
        Font f = glyph > 0 && glyph < advances.length ? awtFont() : null;
        if (f == null) {
            return null;
        }
        try {
            return f.createGlyphVector(new FontRenderContext(null, false, true), new int[] {glyph}).getGlyphOutline(0);
        } catch (RuntimeException | InternalError e) {
            return null;
        }
    }

    float[] inkBounds(int glyph) {
        float[] box = glyphBox(glyph);
        return box == null ? null : new float[] {box[0], box[2]};
    }

    // The outline's box {x0, y0, x1, y1} in font units with y up, or null for an empty glyph
    float[] glyphBox(int glyph) {
        if (glyph <= 0 || glyph >= advances.length) {
            return null;
        }
        float[] known = inks.get(glyph);
        if (known != null) {
            return known.length == 0 ? null : known;
        }
        float[] made = new float[0];
        Font f = awtFont();
        if (f != null) {
            try {
                Rectangle2D r = f.createGlyphVector(new FontRenderContext(null, false, true), new int[] {glyph})
                        .getGlyphOutline(0).getBounds2D();
                if (r.getWidth() > 0) {
                    made = new float[] {(float) r.getMinX(), (float) -r.getMaxY(), (float) r.getMaxX(),
                            (float) -r.getMinY()};
                }
            } catch (RuntimeException | InternalError e) {
                made = new float[0];
            }
        }
        if (inks.size() < 65_536) {
            inks.put(glyph, made);
        }
        return made.length == 0 ? null : made;
    }

    int hintedAdvance(int glyph, int ppem) {
        if (glyph < 0 || glyph >= advances.length || ppem < 1 || ppem > 400) {
            return -1;
        }
        long key = (long) ppem << 32 | glyph;
        Integer known = hinted.get(key);
        if (known != null) {
            return known;
        }
        int made = -1;
        Font f = pixelFont(ppem);
        if (f != null) {
            try {
                float adv = f.createGlyphVector(new FontRenderContext(null, false, false), new int[] {glyph})
                        .getGlyphMetrics(0).getAdvanceX();
                made = Float.isFinite(adv) && adv >= 0 ? Math.round(adv) : -1;
            } catch (RuntimeException | InternalError e) {
                made = -1;
            }
        }
        if (hinted.size() < 262_144) {
            hinted.put(key, made);
        }
        return made;
    }

    private Font pixelFont(int ppem) {
        Font known = pixelFonts.get(ppem);
        Font base = known != null || pixelFonts.size() >= 64 ? null : awtFont();
        if (base != null) {
            known = base.deriveFont((float) ppem);
            pixelFonts.put(ppem, known);
        }
        return known;
    }

    byte[] whole() throws IOException {
        return FontScanner.whole(entry, MAX_WHOLE_BYTES);
    }

    int advanceOfGlyph(int glyph) {
        return glyph >= 0 && glyph < advances.length ? advances[glyph] : advances[0];
    }

    int kerning(int leftGlyph, int rightGlyph) {
        if (kerning == null && gpos != null) {
            return gpos.kerning(leftGlyph, rightGlyph);
        }
        if (kerning == null || leftGlyph <= 0 || rightGlyph <= 0) {
            return 0;
        }
        try {
            return kerning.getKerning(leftGlyph, rightGlyph);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static GposKerning gpos(TrueTypeFont ttf) {
        try {
            TTFTable t = ttf.getTableMap().get("GPOS");
            return t == null || t.getLength() > GPOS_BYTES ? null : GposKerning.of(ttf.getTableBytes(t));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static KerningSubtable kerning(TrueTypeFont ttf) {
        try {
            KerningTable kern = ttf.getKerning();
            return kern == null ? null : kern.getHorizontalKerningSubtable();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private int search(int codePoint) {
        int i = Arrays.binarySearch(codePoints, codePoint);
        return i >= 0 ? glyphs[i] : 0;
    }
}
