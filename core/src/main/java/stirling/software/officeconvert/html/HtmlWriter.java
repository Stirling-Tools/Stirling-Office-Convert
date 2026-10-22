package stirling.software.officeconvert.html;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.ListLabels;
import stirling.software.officeconvert.sink.MediaStore;
import stirling.software.officeconvert.sink.SpillBuffer;

/** One self-contained HTML5 file: pictures are data URIs, there are no scripts and nothing is fetched. */
public final class HtmlWriter implements DocSink, Closeable {

    private static final String BULLET = "\u2022";

    private final OutputStream out;
    private final MediaStore media;
    private final HtmlBlocks blocks;
    private final SpillBuffer body = new SpillBuffer();
    private final Map<Integer, List<Paragraph>> notes = new TreeMap<>();
    private HeaderFooterSet running;
    private final List<Section> sections = new ArrayList<>();

    public HtmlWriter(OutputStream target, Pictures pictures) {
        this.out = new KeepOpen(target);
        this.media = new MediaStore(pictures);
        this.blocks = new HtmlBlocks(media);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key)
            throws IOException {
        return media.add(bytes, ext, pixelWidth, pixelHeight, key);
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return media.get(key);
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet running) {
        this.running = running;
        blocks.styles(styles);
    }

    @Override
    public void block(Block block) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        if (body.isEmpty()) {
            sb.append("<div class=\"s1\">\n");
        }
        if (block instanceof Paragraph p) {
            blocks.paragraph(sb, p);
            if (p.endsSection != null) {
                sections.add(p.endsSection);
                sb.append("</div>\n<div class=\"s").append(sections.size() + 1).append("\">\n");
            }
        } else {
            blocks.table(sb, (Table) block);
        }
        body.append(sb);
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        blocks.styles(styles);
        StringBuilder tail = new StringBuilder(body.isEmpty() ? "" : "</div>\n");
        sections.add(last == null ? defaultPage() : last);
        if (!notes.isEmpty()) {
            tail.append("<section class=\"footnotes\">\n");
            for (Map.Entry<Integer, List<Paragraph>> n : notes.entrySet()) {
                tail.append("<div id=\"fn-").append(n.getKey()).append("\">\n");
                for (Paragraph p : n.getValue()) {
                    blocks.paragraph(tail, p);
                }
                tail.append("</div>\n");
            }
            tail.append("</section>\n");
        }
        HeaderFooterSet hf = running != null ? running : runningSet;
        if (hf != null) {
            running(tail, "footer", hf.footer());
        }
        body.append(tail);
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
        w.write(head(sections, styles, title, author));
        StringBuilder top = new StringBuilder();
        if (hf != null) {
            running(top, "header", hf.header());
        }
        w.write(top.toString().replaceAll("\0[^\0]*\0", ""));
        ListLabels labels = new ListLabels(numbering == null ? new Numbering() : numbering);
        body.copyTo(w, key -> label(labels, key));
        w.write("</main>\n</body>\n</html>\n");
        w.flush();
    }

    private void running(StringBuilder sb, String tag, List<Paragraph> paragraphs) throws IOException {
        StringBuilder inner = new StringBuilder();
        for (Paragraph p : paragraphs == null ? List.<Paragraph>of() : paragraphs) {
            if (hasText(p)) {
                blocks.paragraph(inner, p);
            }
        }
        if (!inner.isEmpty()) {
            sb.append('<').append(tag).append(" class=\"running\">\n").append(inner).append("</").append(tag).append(">\n");
        }
    }

    // Page numbers mean nothing in one flowing page, so a header or footer line holding only those is left out
    private static boolean hasText(Paragraph p) {
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t && t.text().chars().anyMatch(Character::isLetterOrDigit)) {
                return true;
            }
        }
        return false;
    }

    private static Section defaultPage() {
        Section s = new Section();
        s.pageWidth = 595.3f;
        s.pageHeight = 841.9f;
        s.marginTop = 72;
        s.marginBottom = 72;
        s.marginLeft = 72;
        s.marginRight = 72;
        return s;
    }

    private static String geometry(Section s) {
        return HtmlText.pt(s.pageWidth) + ' ' + HtmlText.pt(s.pageHeight) + ";margin:" + HtmlText.pt(s.marginTop) + ' '
                + HtmlText.pt(s.marginRight) + ' ' + HtmlText.pt(s.marginBottom) + ' ' + HtmlText.pt(s.marginLeft);
    }

    // Sections of one page shape share an @page; a document with several shapes names one page per shape
    private static void pages(StringBuilder sb, List<Section> sections) {
        List<String> shapes = new ArrayList<>();
        for (Section s : sections) {
            if (!shapes.contains(geometry(s))) {
                shapes.add(geometry(s));
            }
        }
        sb.append("@page{size:").append(shapes.get(0)).append("}\n");
        if (shapes.size() == 1) {
            return;
        }
        for (int i = 0; i < shapes.size(); i++) {
            sb.append("@page p").append(i + 1).append("{size:").append(shapes.get(i)).append("}\n");
        }
        for (int i = 0; i < sections.size(); i++) {
            sb.append(".s").append(i + 1).append("{page:p").append(shapes.indexOf(geometry(sections.get(i))) + 1)
                    .append("}\n");
        }
    }

    private static String head(List<Section> sections, StyleSheet styles, String title, String author) {
        Section page = sections.get(0);
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"generator\" content=\"Stirling Office Convert\">\n<title>");
        HtmlText.escape(sb, title == null || title.isBlank() ? "Document" : title);
        sb.append("</title>\n");
        if (author != null && !author.isBlank()) {
            sb.append("<meta name=\"author\" content=\"");
            HtmlText.escape(sb, author);
            sb.append("\">\n");
        }
        float width = page.pageWidth;
        float left = page.marginLeft;
        float right = page.marginRight;
        float top = page.marginTop;
        float bottom = page.marginBottom;
        sb.append("<style>\n");
        pages(sb, sections);
        sb.append("html{background:#f3f3f3}\n")
                .append("body{margin:0;").append(HtmlText.css(styles.normal)).append(";line-height:1.15}\n")
                .append("main{box-sizing:border-box;max-width:").append(HtmlText.pt(width)).append(";margin:0 auto;padding:")
                .append(HtmlText.pt(top)).append(' ').append(HtmlText.pt(right)).append(' ').append(HtmlText.pt(bottom))
                .append(' ').append(HtmlText.pt(left)).append(";background:#fff}\n")
                .append("p,h1,h2,h3,h4,h5,h6{margin:0;white-space:pre-wrap;tab-size:4;overflow-wrap:break-word}\n")
                .append("h1,h2,h3,h4,h5,h6{font-size:inherit;font-weight:inherit}\n")
                .append("table{border-collapse:collapse;table-layout:fixed;max-width:100%}\n")
                .append("td,th{font-weight:inherit;text-align:inherit;vertical-align:top}\n")
                .append("img{max-width:100%;height:auto}\n")
                .append(".marker{display:inline-block;min-width:1.5em;text-indent:0}\n")
                .append(".float-left{float:left;margin:2pt 9pt 6pt 0}.float-right{float:right;margin:2pt 0 6pt 9pt}")
                .append("\n")
                .append(".box{margin:6pt 0;padding:4pt}\n")
                .append(".running{color:#555}\n")
                .append(".footnotes{margin-top:18pt;border-top:0.5pt solid #999;padding-top:6pt;font-size:0.85em}\n")
                .append(".page-break{break-after:page}\n")
                .append("@media print{html{background:none}main{max-width:none;padding:0}}\n");
        for (StyleSheet.Style s : styles.all()) {
            if (!"Normal".equals(s.id())) {
                sb.append('.').append(HtmlBlocks.styleClass(s.id())).append('{').append(HtmlText.css(s.run())).append("}\n");
            }
        }
        sb.append("</style>\n</head>\n<body>\n<main>\n");
        return sb.toString();
    }

    private static String label(ListLabels labels, String key) {
        if (!key.startsWith("L")) {
            return "";
        }
        int colon = key.indexOf(':');
        try {
            String label = labels.next(Integer.parseInt(key.substring(1, colon)), Integer.parseInt(key.substring(colon + 1)));
            boolean symbol = !label.isEmpty() && label.chars().allMatch(c -> c >= 0xE000 && c <= 0xF8FF);
            StringBuilder sb = new StringBuilder();
            HtmlText.escape(sb, symbol ? BULLET : label);
            return sb.append(' ').toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public void close() throws IOException {
        try (body; media) {
            out.close();
        }
    }

    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
