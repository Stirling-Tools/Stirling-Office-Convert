package stirling.software.officeconvert.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WorkDir implements AutoCloseable {

    static final String PARENT = "stirling-office-convert";

    static final String PREFIX = "office-convert-app";

    static final Duration UNLOCKED_AGE = Duration.ofHours(1);

    private static final String LOCK = ".lock";

    private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();

    private final Path root;
    private final FileChannel channel;

    private WorkDir(Path root, FileChannel channel) {
        this.root = root;
        this.channel = channel;
    }

    static WorkDir open(Path tmp) throws IOException {
        Path parent = parent(tmp);
        sweep(parent, Instant.now());
        Path root = key(Files.createTempDirectory(parent, PREFIX));
        Path pending = root.resolve(LOCK + ".new");
        FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            channel.lock();
            Files.move(pending, root.resolve(LOCK), StandardCopyOption.ATOMIC_MOVE);
            OWNED.add(root);
            return new WorkDir(root, channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            delete(root);
            throw e;
        }
    }

    static WorkDir open() throws IOException {
        return open(Path.of(System.getProperty("java.io.tmpdir")));
    }

    static Path parent(Path tmp) throws IOException {
        Path parent = tmp.resolve(PARENT + "-" + System.getProperty("user.name", "user").replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(parent);
            }
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || !mine(parent, tmp)) {
                return key(Files.createTempDirectory(tmp, PARENT));
            }
        }
        return key(parent);
    }

    private static boolean mine(Path dir, Path tmp) {
        UserPrincipal me = owner(tmp);
        try {
            return me != null && me.equals(Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static Path key(Path p) {
        return p.toAbsolutePath().normalize();
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
            channel.close();
        } catch (IOException e) {
            System.out.println("Could not release " + root.resolve(LOCK) + ": " + e.getMessage());
        }
        delete(root);
        OWNED.remove(root);
    }

    static int sweep(Path parent, Instant now) {
        UserPrincipal me = owner(parent);
        int removed = 0;
        try (DirectoryStream<Path> found = Files.newDirectoryStream(parent, PREFIX + "*")) {
            for (Path dir : found) {
                if (!OWNED.contains(key(dir)) && abandoned(dir, me, now) && delete(dir)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            System.out.println("Could not look for old uploads in " + parent + ": " + e.getMessage());
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
