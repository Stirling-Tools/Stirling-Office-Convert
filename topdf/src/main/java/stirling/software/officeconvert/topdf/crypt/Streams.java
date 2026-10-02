package stirling.software.officeconvert.topdf.crypt;

import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;

final class Streams {

    private static final int MAX_DEPTH = 16;

    private static final int MAX_ENTRIES = 10_000;

    private Streams() {}

    static void copyExcept(DirectoryNode from, DirectoryEntry to, String... skipped) throws IOException {
        copy(from, to, 0, new int[1], skipped);
    }

    private static void copy(DirectoryEntry from, DirectoryEntry to, int depth, int[] count, String[] skipped)
            throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Entry e : from) {
            if (++count[0] > MAX_ENTRIES) {
                return;
            }
            if (depth == 0 && skip(e.getName(), skipped)) {
                continue;
            }
            if (e instanceof DocumentEntry doc) {
                try (InputStream in = ((DirectoryNode) from).createDocumentInputStream(doc)) {
                    to.createDocument(e.getName(), in);
                }
            } else if (e instanceof DirectoryEntry dir) {
                copy(dir, to.createDirectory(e.getName()), depth + 1, count, skipped);
            }
        }
    }

    private static boolean skip(String name, String[] skipped) {
        for (String s : skipped) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
