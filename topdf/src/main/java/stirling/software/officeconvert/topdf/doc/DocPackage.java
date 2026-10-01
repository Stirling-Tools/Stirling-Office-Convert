package stirling.software.officeconvert.topdf.doc;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.List;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.DirectoryNode;

import stirling.software.officeconvert.memory.Admission;

/** A legacy Word 97-2003 document (.doc, .dot) rewritten as the WordprocessingML package the DOCX renderer draws.
 * Fields show their cached results (none is evaluated), macros and embedded objects are never opened (an object
 * shows its stored preview picture), and nothing the document links to is followed. */
public final class DocPackage {

    /** What the rewrite left out: warnings for the result, and whether content is missing. */
    public record Outcome(List<String> warnings, boolean lost) {
        public Outcome {
            warnings = List.copyOf(warnings);
        }
    }

    public static final String PASSWORD = "The document is password protected; remove the password and try again";

    private DocPackage() {}

    /** Whether an OLE2 file holds a Word document stream. */
    public static boolean isDocument(DirectoryNode root) {
        return root.hasEntryCaseInsensitive("WordDocument");
    }

    /** The heap reading a document of this many bytes may need, for the shared memory gate. */
    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 24;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Outcome write(DirectoryNode root, OutputStream out) throws IOException {
        Opened opened = Opened.open(root);
        try {
            return new DocWriter(opened.doc(), out, opened.defused()).write();
        } catch (RuntimeException | StackOverflowError e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            if (e instanceof StackOverflowError) {
                throw new IOException("The document nests too deeply to convert", e);
            }
            throw new IOException("The Word 97-2003 document could not be read: " + reason(e), e);
        }
    }

    record Opened(HWPFDocument doc, boolean defused) {

        static Opened open(DirectoryNode root) throws IOException {
            WordFile file;
            try {
                file = WordFile.read(root);
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                throw new IOException("The Word 97-2003 document could not be read: " + reason(e), e);
            }
            try {
                return new Opened(new HWPFDocument(file.root()), file.defused());
            } catch (EncryptedDocumentException e) {
                throw new IOException(PASSWORD, e);
            } catch (OldFileFormatException e) {
                throw new IOException("The file is a Word 6.0/95 or older document, which is not supported; save it"
                        + " as .docx", e);
            } catch (IOException | RuntimeException | StackOverflowError e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Conversion interrupted");
                }
                if (file.encrypted()) {
                    throw new IOException(PASSWORD, e);
                }
                throw new IOException("The Word 97-2003 document could not be read: " + reason(e), e);
            }
        }
    }

    static String reason(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
