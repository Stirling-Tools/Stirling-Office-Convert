package stirling.software.officeconvert.topdf.font;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class FontFiles {

    private static final int MAX_DEPTH = 8;

    private static final Lru<Key, Scanned> SCANNED = new Lru<>(20_000);

    record Key(Path file, long size, long modified) {}

    record Scanned(Key key, List<FontEntry> entries, String problem) {}

    private FontFiles() {}

    static List<Scanned> scan(List<Path> dirs, List<String> problems) {
        List<Key> keys = new ArrayList<>();
        for (Path dir : dirs) {
            walk(dir, keys, problems);
        }
        List<Scanned> out = new ArrayList<>();
        for (Key k : new LinkedHashSet<>(keys)) {
            Scanned s = SCANNED.computeIfAbsent(k, FontFiles::scan);
            if (s.problem() != null) {
                problems.add(s.problem());
            }
            out.add(s);
        }
        return out;
    }

    static List<FontEntry> scan(byte[] data, String name, List<String> problems) {
        List<FontEntry> entries = FontScanner.scan(data);
        String problem = problem(name, entries);
        if (problem != null) {
            problems.add(problem);
        }
        return entries;
    }

    private static Scanned scan(Key k) {
        List<FontEntry> entries = FontScanner.scan(k.file());
        return new Scanned(k, entries, problem(k.file().toString(), entries));
    }

    private static String problem(String name, List<FontEntry> entries) {
        if (entries.isEmpty()) {
            return name + " is not a font that can be read; it is skipped";
        }
        List<String> unusable = new ArrayList<>();
        for (FontEntry e : entries) {
            if (e.unusable() != null) {
                unusable.add(e.family() + " " + e.subfamily() + " is not used: " + e.unusable());
            }
        }
        return unusable.isEmpty() ? null : name + ": " + String.join("; ", unusable);
    }

    private static void walk(Path dir, List<Key> keys, List<String> problems) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            problems.add(dir + " is not a folder; it is skipped");
            return;
        }
        List<Key> found = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!font(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (!attrs.isRegularFile()) {
                                problems.add(file + " is not a plain file; it is skipped");
                            } else if (attrs.size() > FontSet.MAX_FONT_BYTES) {
                                problems.add(file + " is larger than " + (FontSet.MAX_FONT_BYTES >> 20)
                                        + " MB; it is skipped");
                            } else if (keys.size() + found.size() >= FontSet.MAX_FONT_FILES) {
                                problems.add("more than " + FontSet.MAX_FONT_FILES + " font files; the rest of " + dir
                                        + " is skipped");
                                return FileVisitResult.TERMINATE;
                            } else {
                                found.add(new Key(file.toAbsolutePath().normalize(), attrs.size(),
                                        attrs.lastModifiedTime().toMillis()));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            problems.add(file + " cannot be read; it is skipped");
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            problems.add(dir + " cannot be read: " + e.getMessage());
        }
        Collections.sort(found, (a, b) -> a.file().compareTo(b.file()));
        keys.addAll(found);
    }

    private static boolean font(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".ttc") || name.endsWith(".otf") || name.endsWith(".otc");
    }
}
