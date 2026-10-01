package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import stirling.software.officeconvert.memory.Admission;

public final class PdfFiles {

    private PdfFiles() {}

    public static PDDocument open(Path pdf, String password) throws IOException {
        return load(pdf, password);
    }

    public static String property(String value) {
        return GlyphCollector.clean(value == null ? null : value.strip());
    }

    public static int pageCount(Path pdf, String password) {
        try (PDDocument doc = load(pdf, password)) {
            return doc.getNumberOfPages();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public static int[] pageRange(int total, int firstPage, int lastPage) throws IOException {
        if (total == 0) {
            throw new IOException("The PDF has no pages");
        }
        if (firstPage > total) {
            throw new IOException("Page " + firstPage + " is past the end of this " + total + "-page PDF");
        }
        int first = firstPage > 0 ? firstPage - 1 : 0;
        int last = lastPage > 0 ? Math.min(lastPage, total) - 1 : total - 1;
        if (last < first) {
            throw new IOException("The page range ends before it starts: " + firstPage + "-" + lastPage);
        }
        return new int[] {first, last};
    }

    public static Path outputFolder(Path target) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            throw new NoSuchFileException(String.valueOf(dir), null, "The output folder does not exist");
        }
        return dir;
    }

    public static void checkOpen(PDDocument doc) throws IOException {
        if (doc.getDocument().isClosed()) {
            throw new IOException("The PDF document is closed");
        }
    }

    /** Stops when the thread is interrupted, or when memory is nearly exhausted and this conversion must give way. */
    public static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
        Admission.checkpoint();
    }

    public static IOException interrupted(IOException e) {
        if (e instanceof ClosedChannelException && Thread.currentThread().isInterrupted()) {
            InterruptedIOException stop = new InterruptedIOException("Conversion interrupted");
            stop.initCause(e);
            return stop;
        }
        return e;
    }

    private static PDDocument load(Path pdf, String password) throws IOException {
        RandomAccessRead source = new InterruptibleRead(new RandomAccessReadBufferedFile(pdf.toFile()));
        try {
            return new KeyedPdfParser(source, password == null ? "" : password,
                    IOUtils.createTempFileOnlyStreamCache()).parse();
        } catch (NoClassDefFoundError e) {
            source.close();
            throw new IOException("This PDF is encrypted with a certificate, which needs BouncyCastle (bcpkix) on the classpath", e);
        } catch (InvalidPasswordException | InterruptedIOException e) {
            source.close();
            throw e;
        } catch (IOException | RuntimeException e) {
            source.close();
            throw new IOException("The PDF could not be opened: " + reason(pdf, e), e);
        }
    }

    private static String reason(Path pdf, Exception e) {
        try (InputStream in = Files.newInputStream(pdf)) {
            byte[] head = in.readNBytes(1024);
            if (head.length == 0) {
                return "the file is empty";
            }
            if (!new String(head, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                return "the file is not a PDF";
            }
        } catch (IOException ignored) {
        }
        return e.getMessage();
    }
}
