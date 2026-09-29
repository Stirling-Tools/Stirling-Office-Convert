package stirling.software.officeconvert.topdf.docx;

sealed interface Inline {

    record Link(String url, String anchor) {}

    record Text(String text, RunProps rp, Link link) implements Inline {}

    record Tab(RunProps rp, Link link) implements Inline {}

    record Break(String type, RunProps rp) implements Inline {}

    record Field(String name, RunProps rp, String cached, Link link, String format) implements Inline {}

    record NoteRef(boolean endnote, int id, RunProps rp, String customMark) implements Inline {}

    record NoteMark(RunProps rp) implements Inline {}

    record Obj(Drawing drawing, RunProps rp, Link link) implements Inline {}

    record Bookmark(String name) implements Inline {}

    record PTab(String alignment, String relativeTo, char leader, RunProps rp) implements Inline {}
}
