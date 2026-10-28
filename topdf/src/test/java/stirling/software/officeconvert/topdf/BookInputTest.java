package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

class BookInputTest {

    @Test
    void exactBoundariesAreAllowedButAnExtraByteAndSkipAreRejected() throws Exception {
        try (InputStream in = BookInput.bounded(new ByteArrayInputStream(new byte[32]), 32, "Book")) {
            assertEquals(32, in.readAllBytes().length);
        }
        try (InputStream in = BookInput.bounded(new ByteArrayInputStream(new byte[33]), 32, "Book")) {
            assertThrows(IOException.class, in::readAllBytes);
        }
        try (InputStream in = BookInput.bounded(new ByteArrayInputStream(new byte[33]), 32, "Book")) {
            assertThrows(IOException.class, () -> in.skip(33));
        }
    }

    @Test
    void interruptionStopsEvenASmallFictionBookAndKeepsTheFlag() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> Fb2Book.read("<FictionBook/>".getBytes()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingChaptersAndTooManyChaptersCannotReturnPartialBooks() {
        HtmlAssets missing = name -> null;
        assertThrows(IOException.class, () -> EpubBook.assemble(missing, List.of("missing.xhtml"), null, null, null));
        assertThrows(IOException.class, () -> EpubBook.assemble(missing,
                java.util.Collections.nCopies(EpubBook.MAX_CHAPTERS + 1, "a.xhtml"), null, null, null));
    }

    @Test
    void metadataCannotPushTheAssembledBookPastTheTextLimit() {
        String title = "x".repeat((int) EpubBook.MAX_BOOK_CHARS + 1);
        assertThrows(IOException.class, () -> EpubBook.assemble(name -> null, List.of(), title, null, null));
    }

    @Test
    void selectorExpansionIsBoundedAndCancellable() throws Exception {
        String css = "a,".repeat(100_000) + "a{color:red}";
        assertThrows(IOException.class, () -> EpubBook.scoped(css, 0, 0));
        assertThrows(IOException.class,
                () -> EpubBook.scoped("@media print{".repeat(9) + "p{color:red}" + "}".repeat(9), 0, 0));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> EpubBook.scoped("p{color:red}", 0, 0));
        } finally {
            Thread.interrupted();
        }
    }
}
