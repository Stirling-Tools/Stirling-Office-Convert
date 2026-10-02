package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.crypt.Passwords;

/** A Word 6.0/95 document rewritten as the Word 97 file the .doc reader takes: Unicode text, Word 97 formatted disk
 * pages, style sheet, font table and section properties, the footnote, endnote, field and header tables carried
 * over, and the main text's drawing objects and text boxes are passed on as anchored DrawingML by character position.
 * Annotations, bookmarks and macros are left out. The old stream is kept as the Data stream, where pictures are
 * found by their offsets. */
public final class Word6Upgrade {

    public record Upgraded(POIFSFileSystem fs, List<String> warnings, Map<Integer, String> anchors) {}

    private static final int TEXT_FC = 1024;

    private static final int FIB_BYTES = 900;

    private static final int PAIRS_97 = 93;

    private static final int[] COPIED = {2, 3, 16, 17, 18, 46, 47, 48};

    private Word6Upgrade() {}

    /** Whether the WordDocument stream is a Word 6.0 or Word 95 one (nFib 101 to 105, Windows or Macintosh). */
    public static boolean isWord6(byte[] head) {
        if (head.length < 4) {
            return false;
        }
        int nFib = (head[2] & 0xFF) | (head[3] & 0xFF) << 8;
        return nFib >= 0x65 && nFib <= 0x69;
    }

    public static Upgraded upgrade(DirectoryNode root) throws IOException {
        try {
            return rewrite(root);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Conversion interrupted");
            }
            throw new IOException("The Word 6.0/95 document could not be read: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (StackOverflowError e) {
            throw new IOException("The Word 6.0/95 document nests too deeply to read", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("The Word 6.0/95 document needs too much memory to read", e);
        }
    }

    private static Upgraded rewrite(DirectoryNode root) throws IOException {
        byte[] main;
        try (InputStream in = root.createDocumentInputStream(root.getEntryCaseInsensitive("WordDocument"))) {
            main = in.readAllBytes();
        }
        Fib6 fib = new Fib6(main);
        if (fib.encrypted()) {
            throw new Passwords.Refused(Passwords.PROTECTED, null);
        }
        Charset charset = CodePages.of(fib, Tables6.charsets(fib));
        Text6 text = new Text6(fib, charset);
        List<String> warnings = new ArrayList<>();
        Drawings6.Result drawings = Drawings6.read(fib, text);
        if (drawings.lost() || fib.ccp[7] > 0 || fib.present(39)) {
            warnings.add("Some drawing objects of the Word 6.0/95 document were left out");
        }
        if (text.refused) {
            warnings.add("The Word 6.0/95 document's piece table could not be read; its text may be out of order");
        }
        Runs.Read chpRead = Runs.read(fib, text, false);
        Runs.Read papRead = Runs.read(fib, text, true);
        if (chpRead.truncated() || papRead.truncated()) {
            warnings.add("Some formatting of the Word 6.0/95 document was left out: it holds too many formatting runs");
        }
        List<Runs.Run> chp = chpRead.runs();
        List<Runs.Run> pap = papRead.runs();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[TEXT_FC]);
        byte[] utf16 = new String(text.chars).getBytes(StandardCharsets.UTF_16LE);
        body.writeBytes(utf16);
        pad(body, 512);
        int firstPage = body.size() / 512;
        List<Integer> chpFcs = new ArrayList<>();
        List<Integer> chpPns = new ArrayList<>();
        for (byte[] page : Runs.pages(chp, false, TEXT_FC, firstPage, chpFcs, chpPns)) {
            body.writeBytes(page);
        }
        List<Integer> papFcs = new ArrayList<>();
        List<Integer> papPns = new ArrayList<>();
        for (byte[] page : Runs.pages(pap, true, TEXT_FC, body.size() / 512, papFcs, papPns)) {
            body.writeBytes(page);
        }
        Sections sections = Sections.read(fib, body);
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        int[][] pairs = new int[PAIRS_97][2];
        put(table, pairs, 1, Tables6.styleSheet(fib, charset));
        put(table, pairs, 15, Tables6.fontTable(fib, charset));
        put(table, pairs, 33, clx(text.length()));
        put(table, pairs, 12, bte(chpFcs, chpPns));
        put(table, pairs, 13, bte(papFcs, papPns));
        put(table, pairs, 6, sections.plcf());
        put(table, pairs, 11, sections.headers(fib));
        for (int i : COPIED) {
            if (fib.present(i)) {
                put(table, pairs, i, java.util.Arrays.copyOfRange(main, fib.fc(i), fib.fc(i) + fib.lcb(i)));
            }
        }
        put(table, pairs, 31, dop(fib));
        byte[] mainOut = body.toByteArray();
        writeFib(mainOut, fib, pairs, TEXT_FC + utf16.length);
        POIFSFileSystem fs = new POIFSFileSystem();
        fs.createDocument(new ByteArrayInputStream(mainOut), "WordDocument");
        fs.createDocument(new ByteArrayInputStream(table.toByteArray()), "1Table");
        fs.createDocument(new ByteArrayInputStream(main), "Data");
        for (Entry e : root) {
            if (e instanceof DocumentEntry d && (e.getName().endsWith("SummaryInformation"))) {
                try (InputStream in = root.createDocumentInputStream(d)) {
                    fs.createDocument(in, e.getName());
                }
            }
        }
        return new Upgraded(fs, warnings, drawings.anchors());
    }

    private static void put(ByteArrayOutputStream table, int[][] pairs, int index, byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        if ((table.size() & 1) != 0) {
            table.write(0);
        }
        pairs[index][0] = table.size();
        pairs[index][1] = data.length;
        table.writeBytes(data);
    }

    private static byte[] clx(int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        Tables6.le32(out, 4 * 2 + 8);
        Tables6.le32(out, 0);
        Tables6.le32(out, length);
        Tables6.le16(out, 0);
        Tables6.le32(out, TEXT_FC);
        Tables6.le16(out, 0);
        return out.toByteArray();
    }

    private static byte[] bte(List<Integer> fcs, List<Integer> pns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (pns.isEmpty()) {
            return null;
        }
        for (int fc : fcs) {
            Tables6.le32(out, fc);
        }
        for (int pn : pns) {
            Tables6.le32(out, pn);
        }
        return out.toByteArray();
    }

    private static byte[] dop(Fib6 fib) {
        byte[] out = new byte[544];
        if (fib.present(31)) {
            System.arraycopy(fib.main, fib.fc(31), out, 0, Math.min(fib.lcb(31), 84));
        }
        return out;
    }

    private static void pad(ByteArrayOutputStream out, int to) {
        while (out.size() % to != 0) {
            out.write(0);
        }
    }

    private static void writeFib(byte[] m, Fib6 fib, int[][] pairs, int textEnd) {
        put16(m, 0, 0xA5EC);
        put16(m, 2, 0x00C1);
        put16(m, 6, fib.lid);
        put16(m, 10, 0x0200 | 0x1000 | 0x0004);
        put16(m, 12, 0x00BF);
        put32(m, 24, TEXT_FC);
        put32(m, 28, textEnd);
        put16(m, 32, 14);
        put16(m, 34 + 26, fib.lid);
        put16(m, 62, 22);
        put32(m, 64, m.length);
        int[] lw = {76, 80, 84, 88, 92, 96, 100, 104};
        for (int i = 0; i < 8; i++) {
            put32(m, lw[i], fib.ccp[i]);
        }
        put16(m, 152, PAIRS_97);
        for (int i = 0; i < PAIRS_97; i++) {
            put32(m, 154 + 8 * i, pairs[i][0]);
            put32(m, 158 + 8 * i, pairs[i][1]);
        }
        put16(m, FIB_BYTES - 2, 0);
    }

    static void put16(byte[] m, int at, int v) {
        m[at] = (byte) v;
        m[at + 1] = (byte) (v >> 8);
    }

    static void put32(byte[] m, int at, int v) {
        m[at] = (byte) v;
        m[at + 1] = (byte) (v >> 8);
        m[at + 2] = (byte) (v >> 16);
        m[at + 3] = (byte) (v >> 24);
    }
}
