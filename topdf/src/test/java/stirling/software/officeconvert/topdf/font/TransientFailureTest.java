package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.PostScriptFonts;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class TransientFailureTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1);

    private LongSupplier clock;

    @BeforeEach
    void fakeClock() {
        clock = Failures.clock;
        Failures.clock = now::get;
    }

    @AfterEach
    void realClock() {
        Failures.clock = clock;
    }

    private void later() {
        now.addAndGet(Failures.RETRY_NANOS);
    }

    @Test
    void aFontFileThatCouldNotBeReadIsTriedAgainLater() throws IOException {
        Path file = Files.write(dir.resolve("Flaky.ttf"), TestFonts.renamed("Flaky Face"));
        List<FontEntry> entries = FontScanner.scan(file);
        FontLibrary lib = FontLibrary.of(entries, Map.of(), Map.of());
        Path away = Files.move(file, dir.resolve("away.bin"));

        assertNull(lib.exact("Flaky Face", false, false));
        FontEntry e = entries.get(0);
        assertFalse(e.failedForGood());
        assertFalse(e.usable());

        Files.move(away, file);
        assertNull(lib.exact("Flaky Face", false, false), "still inside the retry window");
        later();
        assertTrue(e.usable());
        assertNotNull(lib.exact("Flaky Face", false, false));
        assertNotNull(e.program());
    }

    @Test
    void anInterruptedLoadIsNotRemembered() throws IOException {
        Path file = Files.write(dir.resolve("Cut.ttf"), TestFonts.renamed("Cut Face"));
        FontEntry e = FontScanner.scan(file).get(0);
        Path away = Files.move(file, dir.resolve("away.bin"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, e::program);
        } finally {
            Thread.interrupted();
        }
        Files.move(away, file);
        assertTrue(e.usable());
        assertNotNull(e.program());
    }

    @Test
    void documentFontBytesFailTheSameWayForGood() {
        byte[] data = TestFonts.renamed("Broken Face");
        FontEntry e = FontScanner.scan(data).get(0);
        Arrays.fill(data, (byte) 0);
        assertThrows(IOException.class, e::program);
        assertTrue(e.failedForGood());
        later();
        assertFalse(e.usable());
    }

    @Test
    void aStopFromTheMemoryGateIsPassedOnUnwrapped() {
        assertTrue(Failures.interrupted(new InterruptedIOException("stopped")));
        assertTrue(Failures.interrupted(new IOException(new ClosedByInterruptException())));
        assertFalse(Failures.interrupted(new IOException("bad table")));
    }

    @Test
    void aFileThatCouldNotBeOpenedIsListedAsUnread() throws IOException {
        Path good = Files.write(dir.resolve("Good.ttf"), TestFonts.renamed("Good Face"));
        Path missing = dir.resolve("Missing.ttf");
        Path junk = Files.write(dir.resolve("Junk.ttf"), new byte[64]);
        FontLibrary.Scan scan = FontLibrary.scanFiles(List.of(good, missing, junk));
        assertEquals(List.of(missing), scan.unread());
        assertEquals(1, scan.entries().size());
    }

    @Test
    void aLockedFontFileIsScannedAgainLater() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path fonts = Files.createDirectories(dir.resolve("locked"));
        Path file = Files.write(fonts.resolve("Locked.ttf"), TestFonts.renamed("Locked Face"));
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE); FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            assertNull(FontLibrary.of(List.of(fonts)).exact("Locked Face", false, false));
        }
        assertNull(FontLibrary.of(List.of(fonts)).exact("Locked Face", false, false), "inside the retry window");
        later();
        assertNotNull(FontLibrary.of(List.of(fonts)).exact("Locked Face", false, false));
    }

    @Test
    void anUnreadableEmojiFileIsTriedAgainAndAPlainFileIsKnownForGood() throws IOException {
        Path file = dir.resolve("Emoji.ttf");
        assertNull(ColorEmoji.open(file));
        Long retry = ColorEmoji.retryAt(file);
        assertNotNull(retry);
        assertTrue(retry != Failures.NEVER);

        Files.write(file, TestFonts.renamed("Not Emoji"));
        assertNull(ColorEmoji.open(file));
        assertEquals(retry, ColorEmoji.retryAt(file), "inside the retry window");
        later();
        assertNull(ColorEmoji.open(file));
        assertEquals(Failures.NEVER, ColorEmoji.retryAt(file));
    }

    @Test
    void anInterruptedEmojiReadLeavesNothingBehind() throws IOException {
        Path file = Files.write(dir.resolve("Cut.ttf"), TestFonts.renamed("Cut Emoji"));
        Thread.currentThread().interrupt();
        try {
            assertNull(ColorEmoji.open(file));
        } finally {
            Thread.interrupted();
        }
        assertNull(ColorEmoji.retryAt(file));
    }

    @Test
    void aDocumentFontStaysOutOfTheSharedOutlineCaches() throws IOException {
        byte[] otf = PostScriptFonts.font("Own Ps", 400, false, 'A', 'B');
        FontFace own = FontLibrary.of(List.of(), Map.of(), Map.of()).withFonts(List.of(otf))
                .exact("Own Ps", false, false);
        assertNotNull(own);
        int before = EmbeddableFont.shared();
        try (EmbeddableFont font = EmbeddableFont.open(own)) {
            assertNotNull(font.outline(own.glyph('A')));
        }
        assertEquals(before, EmbeddableFont.shared());

        Path fonts = Files.createDirectories(dir.resolve("ps"));
        Files.write(fonts.resolve("Host.otf"), PostScriptFonts.font("Host Ps", 400, false, 'A', 'B'));
        FontFace host = FontLibrary.of(List.of(fonts)).exact("Host Ps", false, false);
        try (EmbeddableFont font = EmbeddableFont.open(host)) {
            assertNotNull(font.outline(host.glyph('A')));
        }
        assertTrue(EmbeddableFont.shared() > before);
    }
}
