package stirling.software.officeconvert.topdf.font;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FontSet {

    public static final long MAX_FONT_BYTES = 64L << 20;

    public static final int MAX_FONT_FILES = 10_000;

    public static final int MAX_FONT_DATA = 1_000;

    public static final float MIN_WIDTH_SCALE = 0.5f;

    public static final float MAX_WIDTH_SCALE = 2f;

    private static final FontSet SYSTEM = new Builder().build();

    private static final Lru<List<Object>, FontLibrary> LIBRARIES = new Lru<>(16);

    private final List<Path> directories;

    private final List<byte[]> data;

    private final List<String> digests;

    private final boolean systemFonts;

    private final Map<String, String> substitutions;

    private final Map<String, Float> widthScales;

    private volatile Loaded loaded;

    private record Loaded(FontLibrary library, List<String> problems) {}

    private FontSet(Builder b) {
        this.directories = List.copyOf(new LinkedHashSet<>(b.directories));
        this.data = List.copyOf(b.data);
        List<String> d = new ArrayList<>();
        for (byte[] bytes : data) {
            d.add(digest(bytes));
        }
        this.digests = List.copyOf(d);
        this.systemFonts = b.systemFonts;
        this.substitutions = Collections.unmodifiableMap(new LinkedHashMap<>(b.substitutions));
        this.widthScales = Collections.unmodifiableMap(new LinkedHashMap<>(b.widthScales));
    }

    public static FontSet system() {
        return SYSTEM;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.directories.addAll(directories);
        b.data.addAll(data);
        b.systemFonts = systemFonts;
        b.substitutions.putAll(substitutions);
        b.widthScales.putAll(widthScales);
        return b;
    }

    public FontSet withDirectories(List<Path> dirs) {
        Objects.requireNonNull(dirs, "dirs");
        if (dirs.isEmpty()) {
            return this;
        }
        return toBuilder().directories(dirs).build();
    }

    public List<Path> directories() {
        return directories;
    }

    public int fontDataCount() {
        return data.size();
    }

    public boolean systemFonts() {
        return systemFonts;
    }

    public Map<String, String> substitutions() {
        return substitutions;
    }

    public Map<String, Float> widthScales() {
        return widthScales;
    }

    public FontLibrary library() {
        return load().library();
    }

    public List<String> problems() {
        return load().problems();
    }

    private Loaded load() {
        Loaded l = loaded;
        if (l != null) {
            return l;
        }
        synchronized (this) {
            if (loaded == null) {
                loaded = scan();
            }
            return loaded;
        }
    }

    private Loaded scan() {
        if (directories.isEmpty() && data.isEmpty() && substitutions.isEmpty() && widthScales.isEmpty()
                && systemFonts) {
            return new Loaded(FontLibrary.system(), List.of());
        }
        List<String> problems = new ArrayList<>();
        List<FontFiles.Scanned> files = FontFiles.scan(directories, problems);
        List<Object> key = new ArrayList<>();
        for (FontFiles.Scanned s : files) {
            key.add(s.key());
        }
        key.add(digests);
        key.add(systemFonts);
        key.add(substitutions);
        key.add(widthScales);
        List<FontEntry> entries = new ArrayList<>();
        for (FontFiles.Scanned s : files) {
            entries.addAll(s.entries());
        }
        for (int i = 0; i < data.size(); i++) {
            entries.addAll(FontFiles.scan(data.get(i), "font data " + (i + 1), problems));
        }
        FontLibrary library = LIBRARIES.computeIfAbsent(List.copyOf(key), k -> {
            List<FontEntry> all = new ArrayList<>(entries);
            if (systemFonts) {
                all.addAll(FontLibrary.system().entries());
            }
            return FontLibrary.of(all, substitutions, widthScales);
        });
        return new Loaded(library, List.copyOf(problems));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FontSet f && f.directories.equals(directories) && f.digests.equals(digests)
                && f.systemFonts == systemFonts && f.substitutions.equals(substitutions)
                && f.widthScales.equals(widthScales);
    }

    @Override
    public int hashCode() {
        return Objects.hash(directories, digests, systemFonts, substitutions, widthScales);
    }

    @Override
    public String toString() {
        return "FontSet[directories=" + directories + ", fontData=" + data.size() + ", systemFonts=" + systemFonts
                + ", substitutions=" + substitutions + ", widthScales=" + widthScales + "]";
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {

        private final List<Path> directories = new ArrayList<>();

        private final List<byte[]> data = new ArrayList<>();

        private boolean systemFonts = true;

        private final Map<String, String> substitutions = new LinkedHashMap<>();

        private final Map<String, Float> widthScales = new LinkedHashMap<>();

        private Builder() {}

        public Builder directory(Path dir) {
            Objects.requireNonNull(dir, "dir");
            directories.add(dir.toAbsolutePath().normalize());
            return this;
        }

        public Builder directories(List<Path> dirs) {
            Objects.requireNonNull(dirs, "dirs");
            for (Path d : dirs) {
                directory(d);
            }
            return this;
        }

        public Builder font(byte[] fontData) {
            Objects.requireNonNull(fontData, "fontData");
            if (data.size() >= MAX_FONT_DATA) {
                throw new IllegalArgumentException("A font set takes at most " + MAX_FONT_DATA + " fonts as data");
            }
            if (fontData.length > MAX_FONT_BYTES) {
                throw new IllegalArgumentException("A font must be at most " + MAX_FONT_BYTES + " bytes, was "
                        + fontData.length);
            }
            data.add(Arrays.copyOf(fontData, fontData.length));
            return this;
        }

        public Builder systemFonts(boolean include) {
            systemFonts = include;
            return this;
        }

        public Builder substitute(String requestedFamily, String installedFamily) {
            String from = name(requestedFamily, "requestedFamily");
            String to = name(installedFamily, "installedFamily");
            substitutions.put(FontLibrary.normalize(from), to);
            return this;
        }

        public Builder widthScale(String family, float scale) {
            String f = name(family, "family");
            if (!(scale >= MIN_WIDTH_SCALE && scale <= MAX_WIDTH_SCALE)) {
                throw new IllegalArgumentException("A width scale must be from " + MIN_WIDTH_SCALE + " to "
                        + MAX_WIDTH_SCALE + ", was " + scale);
            }
            widthScales.put(FontLibrary.normalize(f), scale);
            return this;
        }

        public FontSet build() {
            return new FontSet(this);
        }

        private static String name(String family, String what) {
            Objects.requireNonNull(family, what);
            String s = family.strip();
            if (s.isEmpty() || s.length() > 256) {
                throw new IllegalArgumentException(what + " must be a font family name, was \"" + family + "\"");
            }
            return s;
        }
    }
}
