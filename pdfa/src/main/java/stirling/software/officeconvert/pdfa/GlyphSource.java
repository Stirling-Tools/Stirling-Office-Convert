package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.TrueTypeFont;

import stirling.software.officeconvert.topdf.font.EmbeddableFont;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class GlyphSource implements Closeable {

    private static final int FALLBACK_PROBES = 8;

    private final FontLibrary library;

    private final EmbeddableFont primary;

    private final boolean bold;

    private final boolean italic;

    private final boolean symbol;

    private final List<EmbeddableFont> opened = new ArrayList<>();

    private final Map<String, Optional<EmbeddableFont>> fallbacks = new HashMap<>();

    private GlyphSource(FontLibrary library, EmbeddableFont primary, boolean bold, boolean italic, boolean symbol) {
        this.library = library;
        this.primary = primary;
        this.bold = bold;
        this.italic = italic;
        this.symbol = symbol;
        opened.add(primary);
    }

    static GlyphSource open(FontLibrary library, BaseFontName name) throws IOException {
        return open(library, name, List.of(), null);
    }

    static GlyphSource open(FontLibrary library, BaseFontName name, Collection<Integer> needed, String eastAsianFamily)
            throws IOException {
        boolean symbol = "Symbol".equals(name.family()) || "ZapfDingbats".equals(name.family());
        boolean b = name.bold();
        boolean i = name.italic();
        FontFace named = library.find(name.family(), b, i);
        List<FontFace> candidates = new ArrayList<>();
        if (!symbol) {
            String compact = name.family().replace(" ", "");
            FontFace whole = compact.equals(name.family()) ? null : library.find(compact, b, i);
            boolean eastAsian = eastAsianFamily != null || named.eastAsian() || whole != null && whole.eastAsian();
            if (whole != null && whole.eastAsian()) {
                candidates.add(whole);
            }
            if (eastAsianFamily != null) {
                candidates.add(library.find(eastAsianFamily, b, i));
            }
            candidates.add(named);
            Set<Integer> wanted = new LinkedHashSet<>(needed);
            if (!wanted.isEmpty()) {
                int probes = 0;
                for (int cp : wanted) {
                    if (probes >= FALLBACK_PROBES) {
                        break;
                    }
                    if (!named.covers(cp)) {
                        probes++;
                        FontFace f = library.fallback(cp, b, i);
                        if (f != null) {
                            candidates.add(f);
                        }
                    }
                }
                candidates = ranked(candidates, wanted, eastAsian ? null : named);
            }
        } else {
            candidates.add(named);
        }
        for (FontFace face : candidates) {
            EmbeddableFont f = usable(face);
            if (f != null) {
                return new GlyphSource(library, f, b, i, symbol);
            }
        }
        EmbeddableFont f = usable(library.lastResort(b, i));
        if (f == null) {
            throw new IOException("No TrueType font is available to stand in for " + name.family());
        }
        return new GlyphSource(library, f, b, i, symbol);
    }

    static String eastAsianFamily(String ordering, String baseFont, int flags) {
        if (ordering == null) {
            return null;
        }
        String n = baseFont == null ? "" : baseFont.toLowerCase(Locale.ROOT);
        boolean mincho = n.contains("heiseimin") || n.contains("mincho") || n.contains("myeongjo")
                || n.contains("myungjo");
        boolean sans = !mincho && (n.contains("gothic") || n.contains("hei") || n.contains("goth")
                || n.contains("dotum") || n.contains("gulim") || n.contains("sans") || n.contains("kaku"));
        boolean serif = mincho || !sans && (n.contains("min") || n.contains("song") || n.contains("sun")
                || n.contains("kai") || n.contains("batang") || n.contains("fang") || n.contains("serif")
                || (flags & 2) != 0);
        return switch (ordering) {
            case "Japan1", "Japan2" -> serif ? "MS Mincho" : "MS Gothic";
            case "GB1" -> serif ? "SimSun" : "SimHei";
            case "CNS1" -> serif ? "PMingLiU" : "Microsoft JhengHei";
            case "Korea1", "KR" -> serif ? "Batang" : "Malgun Gothic";
            default -> null;
        };
    }

    private static List<FontFace> ranked(List<FontFace> candidates, Set<Integer> wanted, FontFace keepUnlessPoor) {
        List<FontFace> unique = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (FontFace f : candidates) {
            if (unique.stream().anyMatch(u -> sameProgram(u, f))) {
                continue;
            }
            int covered = 0;
            for (int cp : wanted) {
                if (f.covers(cp)) {
                    covered++;
                }
            }
            unique.add(f);
            scores.add(covered);
        }
        if (keepUnlessPoor != null) {
            int at = unique.indexOf(keepUnlessPoor);
            int own = scores.get(Math.max(0, at));
            if (own * 2 >= wanted.size()) {
                return List.of(keepUnlessPoor);
            }
        }
        List<FontFace> out = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < unique.size(); k++) {
            order.add(k);
        }
        order.sort((x, y) -> Integer.compare(scores.get(y), scores.get(x)));
        for (int k : order) {
            out.add(unique.get(k));
        }
        return out;
    }

    private static boolean sameProgram(FontFace a, FontFace b) {
        return a == b || a.postScriptName() != null && a.postScriptName().equals(b.postScriptName())
                && a.family().equals(b.family()) && a.subfamily().equals(b.subfamily());
    }

    private static EmbeddableFont usable(FontFace face) {
        if (face == null) {
            return null;
        }
        try {
            EmbeddableFont f = EmbeddableFont.open(face);
            if (f.font().getUnicodeCmapLookup(false) == null && !f.trueTypeOutlines()) {
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

    boolean copies() {
        return primary.trueTypeOutlines();
    }

    String description() {
        return primary.description();
    }

    int glyph(int code, String name, String unicode) throws IOException {
        TrueTypeFont ttf = primary.font();
        if (symbol && name != null && copies()) {
            int gid = ttf.nameToGID(name);
            if (gid > 0) {
                return gid;
            }
        }
        if (symbol && name != null) {
            CmapSubtable sym = ttf.getCmap() == null ? null : ttf.getCmap().getSubtable(3, 0);
            if (sym != null && code >= 0) {
                int gid = sym.getGlyphId(0xF000 + code);
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
        if (name != null && !".notdef".equals(name) && copies()) {
            int gid = ttf.nameToGID(name);
            if (gid > 0) {
                return gid;
            }
        }
        return 0;
    }

    GeneralPath outline(int gid, int unitsPerEm) {
        return scaled(primary, gid, unitsPerEm);
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
        EmbeddableFont f = fallbacks.computeIfAbsent(key(face), k -> {
            EmbeddableFont t = usable(face);
            if (t != null) {
                opened.add(t);
            }
            return Optional.ofNullable(t);
        }).orElse(null);
        if (f == null) {
            return null;
        }
        try {
            CmapLookup cmap = f.font().getUnicodeCmapLookup(false);
            return scaled(f, cmap == null ? 0 : cmap.getGlyphId(cp), unitsPerEm);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static GeneralPath scaled(EmbeddableFont f, int gid, int unitsPerEm) {
        try {
            GeneralPath p = f.outline(gid);
            if (p == null) {
                return null;
            }
            double k = unitsPerEm / (double) f.font().getUnitsPerEm();
            p.transform(AffineTransform.getScaleInstance(k, k));
            return p;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    int fontsOpen() {
        return opened.size();
    }

    private static String key(FontFace face) {
        String ps = face.postScriptName();
        return ps != null ? ps : face.family() + "|" + face.subfamily();
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
