package stirling.software.officeconvert.topdf.doc;

import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.poi.hwpf.usermodel.Bookmark;
import org.apache.poi.hwpf.usermodel.Bookmarks;

final class Marks {

    static final int MAX_BOOKMARKS = 20_000;

    private final NavigableMap<Integer, StringBuilder> at = new TreeMap<>();

    Marks(Source src) {
        Bookmarks all;
        int n;
        try {
            all = src.doc.getBookmarks();
            n = Math.min(MAX_BOOKMARKS, all.getBookmarksCount());
        } catch (RuntimeException e) {
            return;
        }
        for (int i = 0; i < n; i++) {
            Bookmark b;
            try {
                b = all.getBookmark(i);
            } catch (RuntimeException e) {
                continue;
            }
            String name = b == null ? null : b.getName();
            if (name == null || name.isBlank() || b.getStart() < 0 || b.getEnd() < b.getStart()) {
                continue;
            }
            at.computeIfAbsent(b.getStart(), k -> new StringBuilder()).append("<w:bookmarkStart w:id=\"").append(i)
                    .append("\" w:name=\"").append(Xml.esc(name)).append("\"/>");
            at.computeIfAbsent(b.getEnd(), k -> new StringBuilder()).append("<w:bookmarkEnd w:id=\"").append(i)
                    .append("\"/>");
        }
    }

    NavigableMap<Integer, StringBuilder> within(int start, int end) {
        return at.subMap(start, true, end, false);
    }
}
