package stirling.software.officeconvert.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;

final class WorkDir implements AutoCloseable {

    static final String PREFIX = "office-convert-app";

    static final Duration UNLOCKED_AGE = Duration.ofHours(1);

    private static final String LOCK = ".lock";

    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;

    private WorkDir(Path root, FileChannel channel, FileLock lock) {
        this.root = root;
        this.channel = channel;
        this.lock = lock;
    }

    static WorkDir open(Path tmp) throws IOException {
        sweep(tmp, Instant.now());
        Path root = Files.createTempDirectory(tmp, PREFIX);
        FileChannel channel = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return new WorkDir(root, channel, channel.lock());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    static WorkDir open() throws IOException {
        return open(Path.of(System.getProperty("java.io.tmpdir")));
    }

    Path root() {
        return root;
    }

    Path newJob() throws IOException {
        return Files.createTempDirectory(root, "job");
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            System.out.println("Could not release " + root.resolve(LOCK) + ": " + e.getMessage());
        }
        delete(root);
    }

    static int sweep(Path tmp, Instant now) {
        UserPrincipal me = owner(tmp);
        int removed = 0;
        try (DirectoryStream<Path> found = Files.newDirectoryStream(tmp, PREFIX + "*")) {
            for (Path dir : found) {
                if (abandoned(dir, me, now) && delete(dir)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            System.out.println("Could not look for old uploads in " + tmp + ": " + e.getMessage());
        }
        if (removed > 0) {
            System.out.println("Deleted " + removed + " upload folder(s) left behind by a stopped server");
        }
        return removed;
    }

    private static UserPrincipal owner(Path tmp) {
        try {
            Path probe = Files.createTempFile(tmp, PREFIX + "-owner", ".tmp");
            try {
                return Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static boolean abandoned(Path dir, UserPrincipal me, Instant now) {
        try {
            BasicFileAttributes a = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isDirectory() || me == null || !me.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            Path lockFile = dir.resolve(LOCK);
            if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                return a.lastModifiedTime().toInstant().isBefore(now.minus(UNLOCKED_AGE));
            }
            try (FileChannel c = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock held = c.tryLock()) {
                return held != null;
            } catch (OverlappingFileLockException e) {
                return false;
            }
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    static boolean delete(Path dir) {
        boolean[] all = {true};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    remove(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    if (!(e instanceof NoSuchFileException)) {
                        all[0] = false;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) {
                    remove(d);
                    return FileVisitResult.CONTINUE;
                }

                private void remove(Path p) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        all[0] = false;
                    }
                }
            });
        } catch (IOException e) {
            all[0] = false;
        }
        if (!all[0]) {
            System.out.println("Could not delete everything in " + dir);
        }
        return all[0];
    }
}
