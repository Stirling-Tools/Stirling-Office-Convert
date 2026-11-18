package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkDirTest {

    @TempDir
    Path tmp;

    private Path folder(String name, Instant modified) throws IOException {
        return folder(WorkDir.parent(tmp), name, modified);
    }

    private static Path folder(Path in, String name, Instant modified) throws IOException {
        Path dir = Files.createDirectory(in.resolve(name));
        Files.writeString(dir.resolve("in.pdf"), "upload");
        Files.setLastModifiedTime(dir, FileTime.from(modified));
        return dir;
    }

    @Test
    void sweepsFoldersOfAStoppedServerAndKeepsALiveOne() throws IOException {
        Path dead = folder(WorkDir.PREFIX + "dead", Instant.now());
        Files.writeString(dead.resolve(".lock"), "");
        Path old = folder(WorkDir.PREFIX + "old", Instant.now().minus(WorkDir.UNLOCKED_AGE).minusSeconds(60));
        Path recent = folder(WorkDir.PREFIX + "recent", Instant.now());
        Path other = folder("someone-else", Instant.EPOCH);

        try (WorkDir live = WorkDir.open(tmp)) {
            Files.writeString(live.newJob().resolve("in.pdf"), "upload");

            assertFalse(Files.exists(dead));
            assertFalse(Files.exists(old));
            assertTrue(Files.exists(recent));
            assertTrue(Files.exists(other));

            assertEquals(0, WorkDir.sweep(WorkDir.parent(tmp), Instant.now()));
            assertTrue(Files.isDirectory(live.root()));
        }
    }

    @Test
    void neverTouchesMatchingFoldersOutsideItsOwnParent() throws IOException {
        Path stray = folder(tmp, WorkDir.PREFIX + "stray", Instant.EPOCH);
        Files.writeString(stray.resolve(".lock"), "");

        try (WorkDir live = WorkDir.open(tmp)) {
            assertTrue(live.root().startsWith(WorkDir.parent(tmp)));
            assertTrue(Files.exists(stray));
        }
    }

    @Test
    void aSweepInTheOwnersJvmKeepsTheOwnersLock() throws Exception {
        try (WorkDir live = WorkDir.open(tmp)) {
            Path parent = WorkDir.parent(tmp);
            assertEquals(0, WorkDir.sweep(parent, Instant.now()));

            Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), SweepMain.class.getName(), parent.toString())
                    .redirectErrorStream(true).start();
            String out = new String(other.getInputStream().readAllBytes()).strip();
            assertEquals(0, other.waitFor());

            assertTrue(out.endsWith("0"), out);
            assertTrue(Files.isDirectory(live.root()));
        }
    }

    @Test
    void closingDeletesTheRoot() throws IOException {
        Path root;
        try (WorkDir work = WorkDir.open(tmp)) {
            root = work.root();
            Files.writeString(work.newJob().resolve("out.docx"), "result");
        }
        assertFalse(Files.exists(root));
    }

    @Test
    void neverFollowsALinkOrTouchesAFile() throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.writeString(target.resolve("keep.txt"), "keep");
        Path parent = WorkDir.parent(tmp);
        Path link = parent.resolve(WorkDir.PREFIX + "link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        Path file = Files.writeString(parent.resolve(WorkDir.PREFIX + "file"), "keep");
        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));

        WorkDir.sweep(parent, Instant.now().plus(WorkDir.UNLOCKED_AGE).plusSeconds(60));

        assertTrue(Files.exists(target.resolve("keep.txt")));
        assertTrue(Files.exists(file));
    }

    @Test
    void deletingCarriesOnPastAFileItCannotDelete() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        Path dir = Files.createDirectory(tmp.resolve("job"));
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.writeString(locked.resolve("x"), "x");
        Files.writeString(dir.resolve("b.txt"), "b");
        Files.writeString(Files.createDirectory(dir.resolve("c")).resolve("d.txt"), "d");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            assertFalse(WorkDir.delete(dir));

            assertFalse(Files.exists(dir.resolve("b.txt")));
            assertFalse(Files.exists(dir.resolve("c")));
            assertTrue(Files.exists(locked.resolve("x")));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
