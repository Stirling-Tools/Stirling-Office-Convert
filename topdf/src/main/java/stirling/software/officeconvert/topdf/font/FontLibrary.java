package stirling.software.officeconvert.topdf.font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;

public final class FontLibrary {

    public static final int MAX_DOCUMENT_FONT_BYTES = 32 << 20;

    public static final int MAX_DOCUMENT_FONTS = 64;

    private static final int MAX_FILES = 50_000;

    private static final int MAX_DEPTH = 16;

    private static final long MAX_FONT_FILE = 256L << 20;

    private static final int FALLBACK_LOADS = 48;

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Object SYSTEM_LOCK = new Object();

    private static volatile FontLibrary system;

    private static final int MAX_FACES = 4096;

    private static final Lru<List<Path>, FontLibrary> DIRS = new Lru<>(16);

    private static final Lru<List<Path>, FontLibrary> WITH_SYSTEM = new Lru<>(16);

    private final List<FontEntry> entries;

    private final List<FontEntry> usable;

    private final Map<String, List<FontEntry>> legacy = new HashMap<>();

    private final Map<String, List<FontEntry>> typographic = new HashMap<>();

    private final Map<String, FontEntry> names = new HashMap<>();

    // Keys come from documents, so these are bounded: a host converting forever must not keep every name it saw
    private final Lru<String, Optional<FontFace>> faces = new Lru<>(MAX_FACES);

    private final Lru<Long, Optional<FontFace>> fallbacks = new Lru<>(MAX_FACES);

    private FontLibrary(List<FontEntry> entries) {
        List<FontEntry> all = new ArrayList<>(entries);
        for (FontEntry e : Bundled.ENTRIES) {
            if (!all.contains(e)) {
                all.add(e);
            }
        }
        this.entries = List.copyOf(all);
        List<FontEntry> ok = new ArrayList<>();
        for (FontEntry e : this.entries) {
            for (String name : e.legacyFamilies()) {
                legacy.computeIfAbsent(normalize(name), k -> new ArrayList<>()).add(e);
            }
            for (String name : e.typographicFamilies()) {
                typographic.computeIfAbsent(normalize(name), k -> new ArrayList<>()).add(e);
            }
            names.putIfAbsent(normalize(e.fullName()), e);
            if (e.postScriptName() != null) {
                names.putIfAbsent(normalize(e.postScriptName()), e);
            }
            if (e.usable()) {
                ok.add(e);
            }
        }
        ok.sort(Comparator.comparingInt(e -> (e.italic() ? 2 : 0) + (e.bold() ? 1 : 0)));
        this.usable = Collections.unmodifiableList(ok);
    }

    public static FontLibrary system() {
        FontLibrary lib = system;
        if (lib == null) {
            synchronized (SYSTEM_LOCK) {
                lib = system;
                if (lib == null) {
                    lib = new FontLibrary(scanDirs(systemFontDirs()));
                    system = lib;
                }
            }
        }
        return lib;
    }

    public static FontLibrary of(List<Path> dirs) {
        List<Path> key = key(dirs);
        return DIRS.computeIfAbsent(key, k -> new FontLibrary(scanDirs(k)));
    }

    public static FontLibrary withSystem(List<Path> extraDirs) {
        List<Path> key = key(extraDirs);
        if (key.isEmpty()) {
            return system();
        }
        FontLibrary base = system();
        return WITH_SYSTEM.computeIfAbsent(key, k -> {
            List<FontEntry> all = new ArrayList<>(scanDirs(k));
            all.addAll(base.entries);
            return new FontLibrary(all);
        });
    }

    public FontLibrary withFonts(List<byte[]> fontData) {
        Objects.requireNonNull(fontData, "fontData");
        List<FontEntry> all = new ArrayList<>();
        int faces = 0;
        for (byte[] data : fontData) {
            Objects.requireNonNull(data, "font data");
            if (data.length > MAX_DOCUMENT_FONT_BYTES || faces >= MAX_DOCUMENT_FONTS) {
                continue;
            }
            for (FontEntry e : FontScanner.scan(data.clone())) {
                if (faces++ < MAX_DOCUMENT_FONTS) {
                    all.add(e);
                }
            }
        }
        if (all.isEmpty()) {
            return this;
        }
        all.addAll(entries);
        return new FontLibrary(all);
    }

    public static List<Path> systemFontDirs() {
        List<Path> dirs = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String windows = firstSet(System.getenv("WINDIR"), System.getenv("SystemRoot"), "C:\\Windows");
            dirs.add(Path.of(windows, "Fonts"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                dirs.add(Path.of(local, "Microsoft", "Windows", "Fonts"));
                dirs.add(Path.of(local, "Microsoft", "FontCache", "4", "CloudFonts"));
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            dirs.add(Path.of("/System/Library/Fonts"));
            dirs.add(Path.of("/Library/Fonts"));
            if (home != null) {
                dirs.add(Path.of(home, "Library", "Fonts"));
            }
        } else {
            dirs.add(Path.of("/usr/share/fonts"));
            dirs.add(Path.of("/usr/local/share/fonts"));
            if (home != null) {
                dirs.add(Path.of(home, ".fonts"));
                String data = System.getenv("XDG_DATA_HOME");
                dirs.add(data != null && !data.isBlank() ? Path.of(data, "fonts") : Path.of(home, ".local", "share", "fonts"));
            }
        }
        return dirs;
    }

    public boolean isEmpty() {
        return usable.isEmpty();
    }

    int cachedFaces() {
        return faces.size() + fallbacks.size();
    }

    public int size() {
        return usable.size();
    }

    public SortedSet<String> families() {
        SortedSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (FontEntry e : usable) {
            out.add(e.family());
        }
        return Collections.unmodifiableSortedSet(out);
    }

    public FontFace find(String family, boolean bold, boolean italic) {
        Objects.requireNonNull(family, "family");
        String key = "f\u0000" + normalize(family) + '\u0000' + (bold ? 1 : 0) + (italic ? 1 : 0);
        Optional<FontFace> known = faces.get(key);
        if (known == null) {
            known = Optional.of(resolve(family, bold, italic));
            Optional<FontFace> raced = faces.putIfAbsent(key, known);
            known = raced == null ? known : raced;
        }
        return known.get();
    }

    public FontFace exact(String family, boolean bold, boolean italic) {
        Objects.requireNonNull(family, "family");
        String key = "e\u0000" + normalize(family) + '\u0000' + (bold ? 1 : 0) + (italic ? 1 : 0);
        Optional<FontFace> known = faces.get(key);
        if (known == null) {
            known = Optional.ofNullable(lookup(family, bold, italic));
            Optional<FontFace> raced = faces.putIfAbsent(key, known);
            known = raced == null ? known : raced;
        }
        return known.orElse(null);
    }

    public FontFace fallback(int codePoint, boolean bold, boolean italic) {
        long key = (long) codePoint << 2 | (bold ? 2 : 0) | (italic ? 1 : 0);
        Optional<FontFace> known = fallbacks.get(key);
        if (known != null) {
            return known.orElse(null);
        }
        FontFace found = null;
        for (String family : Substitutes.script(codePoint)) {
            FontFace f = exact(family, bold, italic);
            if (f != null && f.covers(codePoint)) {
                found = f;
                break;
            }
        }
        if (found == null) {
            int loads = 0;
            for (FontEntry e : usable) {
                if (!e.mayCover(codePoint)) {
                    continue;
                }
                FontFace f = face(e, e.family(), bold, italic, null);
                if (f != null && f.covers(codePoint)) {
                    found = f;
                    break;
                }
                if (++loads >= FALLBACK_LOADS) {
                    break;
                }
            }
        }
        fallbacks.putIfAbsent(key, Optional.ofNullable(found));
        return found;
    }

    /**
     * A face for a character the face {@code like} lacks. For Arabic and Hebrew it is drawn as wide as the family
     * {@code like} stands for draws those scripts on Windows (Arial, Times New Roman, Calibri and so on).
     */
    public FontFace fallback(int codePoint, FontFace like) {
        Objects.requireNonNull(like, "like");
        FontFace f = fallback(codePoint, like.boldStyle(), like.italicStyle());
        if (f == null) {
            return null;
        }
        String sample = ScriptWidths.sample(codePoint);
        boolean scalable = sample != null && !f.emulated() && !f.symbolStandIn()
                && ScriptWidths.average(like.requestedFamily(), sample) > 0;
        float stroke = Weights.thinStroke(like.requestedFamily(), like.boldStyle(), f);
        if (!scalable && stroke == 0) {
            return f;
        }
        String key = "s\u0000" + (scalable ? sample.length() : 0) + '\u0000' + normalize(like.requestedFamily())
                + '\u0000' + f.program().entry().describe() + (f.syntheticBold() ? 2 : 0)
                + (f.syntheticItalic() ? 1 : 0) + '\u0000' + stroke;
        Optional<FontFace> known = faces.get(key);
        if (known == null) {
            float k = scalable
                    ? ScriptWidths.scale(like.requestedFamily(), f.program(), sample, like.boldStyle()) : 0;
            FontFace scaled = k <= 0 ? f
                    : new FontFace(f.program(), f.requestedFamily(), f.syntheticBold(), f.syntheticItalic(), f.note(),
                            null, null, k);
            known = Optional.of(stroke > 0 ? scaled.withEmbolden(stroke) : scaled);
            Optional<FontFace> raced = faces.putIfAbsent(key, known);
            known = raced == null ? known : raced;
        }
        return known.get();
    }

    public List<FontRun> runs(String text, FontFace primary) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(primary, "primary");
        List<FontRun> out = new ArrayList<>();
        FontFace current = null;
        int start = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            FontFace face;
            if (primary.covers(cp) || FontFace.ignorable(cp) || cp == FontFace.SOFT_HYPHEN) {
                face = primary;
            } else if (current != null && mark(cp) && current.covers(cp)) {
                face = current;
            } else {
                FontFace f = fallback(cp, primary);
                face = f == null ? primary : f;
            }
            if (current == null) {
                current = face;
            } else if (!face.equals(current)) {
                out.add(new FontRun(current, start, i, text.substring(start, i)));
                current = face;
                start = i;
            }
            i += Character.charCount(cp);
        }
        if (current != null) {
            out.add(new FontRun(current, start, text.length(), text.substring(start)));
        }
        return out;
    }

    public static String normalize(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        return plainSpaces(n) ? n : SPACES.matcher(n).replaceAll(" ");
    }

    private static boolean plainSpaces(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x09 || c == 0x0A || c == 0x0B || c == 0x0C || c == 0x0D
                    || c == ' ' && i + 1 < s.length() && s.charAt(i + 1) == ' ') {
                return false;
            }
        }
        return true;
    }

    private FontFace resolve(String family, boolean bold, boolean italic) {
        FontFace face = exact(family, bold, italic);
        if (face != null) {
            return Weights.emboldenedIfThin(face, family, bold);
        }
        FontNames.Alias alias = FontNames.alias(family);
        String base = alias == null ? family : alias.family();
        boolean b = bold || alias != null && alias.bold() || Weights.of(family) >= 600;
        boolean i = italic || alias != null && alias.italic();
        if (alias != null) {
            FontFace f = exact(base, b, i);
            if (f != null) {
                return Weights.emboldened(new FontFace(f.program(), family, f.syntheticBold(), f.syntheticItalic(),
                        null, SymbolFonts.remap(base, f.program()), null, 1), family, bold);
            }
        }
        String why = unavailable(family);
        Set<String> tried = new LinkedHashSet<>();
        List<String> chain = new ArrayList<>(Substitutes.table(family));
        chain.addAll(Substitutes.table(base));
        if (Substitutes.eastAsian(family)) {
            chain.addAll(Substitutes.generic(family));
        }
        chain.addAll(Substitutes.generic(base));
        for (String candidate : chain) {
            if (!tried.add(normalize(candidate))) {
                continue;
            }
            FontFace f = exact(candidate, b, i);
            if (f != null) {
                int named = alias != null && alias.bold() ? 700 : Weights.of(base);
                boolean clone = Substitutes.metricClone(family, f)
                        || Weights.of(family) == named && Substitutes.metricClone(base, f);
                String note = clone ? null : why + "; using " + f.family();
                return Weights.emboldened(standIn(f, family, base, b, i, note), family, bold);
            }
        }
        FontFace last = lastResort(b, i);
        return Weights.emboldened(standIn(last, family, base, b, i, why + "; using " + last.family()), family, bold);
    }

    // A stand-in draws symbol fonts with Unicode look-alikes and keeps a missing Office font's widths and lines
    private static FontFace standIn(FontFace f, String family, String base, boolean bold, boolean italic, String note) {
        SymbolFonts.Remap symbols = SymbolFonts.remap(base, f.program());
        String metrics = OfficeFonts.style(family, bold, italic) != null ? family : base;
        OfficeFonts.Style original = symbols == null ? OfficeFonts.style(metrics, bold, italic) : null;
        OfficeFonts.Style widths = original != null && !OfficeFonts.compatible(metrics, f.family()) ? original : null;
        float stretch = 1;
        if (widths != null) {
            float own = OfficeFonts.prose(f.program());
            stretch = own > 0 ? Math.max(0.7f, Math.min(1.3f, widths.average() / own)) : 1;
        }
        FontFace face = new FontFace(f.program(), family, f.syntheticBold(), f.syntheticItalic(), note, symbols,
                widths, stretch, original);
        float[] scripts = symbols == null ? ScriptWidths.scales(family, base, f.program(), bold) : null;
        return scripts == null ? face : face.withScripts(scripts);
    }

    public FontFace lastResort(boolean bold, boolean italic) {
        for (FontEntry e : Bundled.ENTRIES) {
            FontFace f = face(e, e.family(), bold, italic, null);
            if (f != null) {
                return f;
            }
        }
        throw new IllegalStateException("The font bundled with PDFBox (" + Bundled.RESOURCE + ") cannot be read");
    }

    private FontFace lookup(String family, boolean bold, boolean italic) {
        String n = normalize(family);
        for (List<FontEntry> group : groups(n)) {
            for (FontEntry e : ranked(group, bold, italic)) {
                FontFace f = face(e, family, bold, italic, null);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private List<List<FontEntry>> groups(String n) {
        List<List<FontEntry>> out = new ArrayList<>();
        List<FontEntry> l = legacy.get(n);
        if (l != null) {
            out.add(l);
        }
        List<FontEntry> t = typographic.get(n);
        if (t != null) {
            out.add(t);
        }
        FontEntry named = names.get(n);
        if (named != null) {
            out.add(List.of(named));
        }
        return out;
    }

    private String unavailable(String family) {
        for (List<FontEntry> group : groups(normalize(family))) {
            for (FontEntry e : group) {
                if (e.unusable() != null) {
                    return family + " is installed but " + e.unusable();
                }
            }
            if (!group.isEmpty()) {
                return family + " is installed but could not be read";
            }
        }
        return family + " is not installed";
    }

    private static List<FontEntry> ranked(List<FontEntry> group, boolean bold, boolean italic) {
        List<FontEntry> ok = new ArrayList<>();
        for (FontEntry e : group) {
            if (e.usable()) {
                ok.add(e);
            }
        }
        int target = bold ? 700 : 400;
        ok.sort(Comparator.comparingInt(e -> (e.italic() != italic ? 10_000 : 0)
                + (bold && e.weight() < 600 ? 1_000 : 0) + (!bold && e.weight() >= 600 ? 1_000 : 0)
                + Math.abs(e.weight() - target)));
        return ok;
    }

    private static FontFace face(FontEntry e, String requested, boolean bold, boolean italic, String note) {
        try {
            FontProgram p = e.program();
            return new FontFace(p, requested, bold && !e.bold(), italic && !e.italic(), note);
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean mark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static List<Path> key(List<Path> dirs) {
        Objects.requireNonNull(dirs, "dirs");
        List<Path> out = new ArrayList<>();
        for (Path d : dirs) {
            out.add(Objects.requireNonNull(d, "font folder").toAbsolutePath().normalize());
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    private static List<FontEntry> scanDirs(List<Path> dirs) {
        Map<Path, Boolean> files = new LinkedHashMap<>();
        for (Path dir : dirs) {
            collect(dir, files);
        }
        List<Path> list = new ArrayList<>(files.keySet());
        List<List<FontEntry>> scanned = list.parallelStream().map(FontScanner::scan).toList();
        List<FontEntry> out = new ArrayList<>();
        for (List<FontEntry> s : scanned) {
            out.addAll(s);
        }
        return out;
    }

    private static void collect(Path dir, Map<Path, Boolean> files) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() + found.size() >= MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile() && attrs.size() > 12 && attrs.size() <= MAX_FONT_FILE && font(file)) {
                        found.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            return;
        } finally {
            Collections.sort(found);
            for (Path f : found) {
                files.putIfAbsent(f, Boolean.TRUE);
            }
        }
    }

    private static boolean font(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".ttc") || name.endsWith(".otf") || name.endsWith(".otc");
    }

    static boolean bundled(FontEntry entry) {
        return Bundled.ENTRIES.contains(entry);
    }

    private static final class Bundled {

        static final String RESOURCE = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

        static final List<FontEntry> ENTRIES = load();

        private static List<FontEntry> load() {
            try (InputStream in = PDDocument.class.getResourceAsStream(RESOURCE)) {
                return in == null ? List.of() : List.copyOf(FontScanner.scan(in.readNBytes(MAX_DOCUMENT_FONT_BYTES)));
            } catch (IOException e) {
                return List.of();
            }
        }
    }

    private static String firstSet(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return values[values.length - 1];
    }
}
