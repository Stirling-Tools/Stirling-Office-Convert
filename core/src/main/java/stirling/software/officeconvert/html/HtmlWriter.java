package stirling.software.officeconvert.html;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import stirling.software.officeconvert.sink.Links;
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
    private final ArrayDeque<Paragraph.ListRef> lists = new ArrayDeque<>();
    private Numbering numbering = new Numbering();
    private String pendingLabel;
    private String pendingKey;
    private Paragraph gapLine;
    private final PdfWebFonts fonts;

    public HtmlWriter(OutputStream target, Pictures pictures) {
        this(target, pictures, null);
    }

    public HtmlWriter(OutputStream target, Pictures pictures, PdfWebFonts fonts) {
        this.out = new KeepOpen(target);
        this.media = new MediaStore(pictures);
        this.blocks = new HtmlBlocks(media);
        this.fonts = fonts;
        blocks.fonts(fonts);
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
        if (gapLine != null) {
            Paragraph held = gapLine;
            gapLine = null;
            if (block instanceof Paragraph p && continues(held, p)) {
                join(held, p);
            } else {
                write(held);
            }
        }
        if (block instanceof Paragraph p && p.endsSection == null && HtmlBlocks.justified(p)
                && !HtmlBlocks.columnBreak(p)) {
            gapLine = p;
            return;
        }
        write(block);
    }

    private static boolean pageStart(Block block) {
        String mark = null;
        if (block instanceof Paragraph p) {
            mark = p.bookmark;
        } else if (block instanceof Table t && !t.rows.isEmpty() && !t.rows.get(0).cells.isEmpty()
                && !t.rows.get(0).cells.get(0).flatParagraphs().isEmpty()) {
            mark = t.rows.get(0).cells.get(0).flatParagraphs().getFirst().bookmark;
        }
        return mark != null && mark.startsWith(Links.PAGE_PREFIX);
    }

    private static boolean continues(Paragraph held, Paragraph p) {
        return p.align == Paragraph.Align.JUSTIFY && p.list == null && held.list == null && !p.pageBreakBefore
                && p.spaceBefore < 3 && held.style.equals(p.style) && p.bookmark == null;
    }

    private static void join(Paragraph held, Paragraph p) {
        List<Inline> lead = new ArrayList<>(held.inlines);
        Inline.Text last = null;
        for (Inline in : lead) {
            if (in instanceof Inline.Text t) {
                last = t;
            }
        }
        if (last != null && !last.text().isEmpty() && !Character.isWhitespace(last.text().charAt(last.text().length() - 1))
                && !last.text().endsWith("-")) {
            lead.add(new Inline.Text(" ", last.style(), null, -1));
        }
        p.inlines.addAll(0, lead);
        p.spaceBefore = held.spaceBefore;
        p.bookmark = held.bookmark;
        p.pageBreakBefore = held.pageBreakBefore;
        p.sourceTop = held.sourceTop;
        p.indentLeft = held.indentLeft;
        p.indentFirst = held.indentFirst;
    }

    private void write(Block block) throws IOException {
        if (block instanceof Table t) {
            Paragraph prose = Prose.of(t);
            if (prose != null) {
                block = prose;
            }
        }
        StringBuilder sb = new StringBuilder(512);
        if (body.isEmpty()) {
            sb.append("<div class=\"s1\">\n");
        } else if (pageStart(block)) {
            closeLists(sb);
            sb.append(HtmlBlocks.MARK).append('P').append(sections.size()).append(HtmlBlocks.MARK);
        }
        if (block instanceof Paragraph p) {
            list(sb, p.list);
            blocks.paragraph(sb, p, sections.size());
            if (p.endsSection != null) {
                closeLists(sb);
                sections.add(p.endsSection);
                sb.append("</div>\n<div class=\"s").append(sections.size() + 1).append("\">\n");
            }
        } else {
            closeLists(sb);
            blocks.table(sb, (Table) block);
        }
        body.append(sb);
    }

    private void list(StringBuilder sb, Paragraph.ListRef ref) {
        if (ref == null) {
            closeLists(sb);
            return;
        }
        while (!lists.isEmpty() && (lists.peek().level() > ref.level()
                || lists.peek().level() == ref.level() && lists.peek().numId() != ref.numId())) {
            closeList(sb);
        }
        if (!lists.isEmpty() && lists.peek().level() == ref.level()) {
            sb.append("</li>\n");
        } else {
            sb.append(HtmlBlocks.MARK).append('O').append(ref.numId()).append(':').append(ref.level()).append(HtmlBlocks.MARK)
                    .append('\n');
            lists.push(ref);
        }
        sb.append(HtmlBlocks.MARK).append('I').append(ref.numId()).append(':').append(ref.level()).append(HtmlBlocks.MARK);
    }

    private void closeLists(StringBuilder sb) {
        while (!lists.isEmpty()) {
            closeList(sb);
        }
    }

    private void closeList(StringBuilder sb) {
        Paragraph.ListRef ref = lists.pop();
        sb.append("</li>").append(HtmlBlocks.MARK).append('C').append(ref.numId()).append(':').append(ref.level())
                .append(HtmlBlocks.MARK).append('\n');
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        blocks.styles(styles);
        if (gapLine != null) {
            write(gapLine);
            gapLine = null;
        }
        StringBuilder closing = new StringBuilder();
        closeLists(closing);
        body.append(closing);
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
        StringBuilder top = new StringBuilder();
        if (hf != null) {
            running(top, "header", hf.header());
        }
        StringBuilder faces = new StringBuilder();
        if (fonts != null) {
            fonts.faces(faces);
        }
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
        w.write(head(sections, styles, title, author, faces));
        w.write(top.toString().replaceAll("\0[^\0]*\0", ""));
        this.numbering = numbering == null ? new Numbering() : numbering;
        ListLabels labels = new ListLabels(this.numbering);
        body.copyTo(w, key -> resolve(labels, key));
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
        StringBuilder text = new StringBuilder();
        boolean numbered = false;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                text.append(t.text());
            } else if (in instanceof Inline.PageNumber) {
                numbered = true;
                text.append(' ');
            }
        }
        if (text.chars().noneMatch(Character::isLetterOrDigit)) {
            return false;
        }
        if (!numbered) {
            return true;
        }
        List<String> words = Arrays.stream(text.toString().split("[\\s\\p{Punct}]+")).filter(w -> !w.isEmpty()).toList();
        return words.size() > 2 || words.stream().anyMatch(w -> w.length() > 6);
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
        for (int i = 0; i < sections.size(); i++) {
            List<float[]> columns = sections.get(i).columns;
            if (columns.size() > 1) {
                float[] first = columns.get(0);
                sb.append(".s").append(i + 1).append("{column-count:").append(columns.size()).append(";column-gap:")
                        .append(HtmlText.pt(first.length > 1 ? first[1] : 12)).append("}\n");
            }
        }
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

    private static String head(List<Section> sections, StyleSheet styles, String title, String author,
            CharSequence faces) {
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
        sb.append("<style>\n").append(faces);
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
                .append("main{position:relative;z-index:0}\n")
                .append(".drawing{position:absolute;z-index:-1;overflow:visible;pointer-events:none}\n")
                .append("ul.list,ol.list{list-style:none;margin:0;padding:0}\n")
                .append(".float-left,.float-right,.figure{position:relative}\n")
                .append(".picture-text{position:absolute;left:0;top:0;overflow:hidden;color:transparent;line-height:1.15;")
                .append("white-space:pre-wrap}\n")
                .append("@media print{html{background:none}main{max-width:none;padding:0}.running{display:none}}\n");
        for (StyleSheet.Style s : styles.all()) {
            if (!"Normal".equals(s.id())) {
                sb.append('.').append(HtmlBlocks.styleClass(s.id())).append('{').append(HtmlText.css(s.run())).append("}\n");
            }
        }
        sb.append("</style>\n</head>\n<body>\n<main>\n");
        return sb.toString();
    }

    private String resolve(ListLabels labels, String key) {
        try {
            return switch (key.isEmpty() ? ' ' : key.charAt(0)) {
                case 'L' -> label(labels, key);
                case 'I' -> item(labels, key);
                case 'O' -> ordered(key) ? "<ol class=\"list\">" : "<ul class=\"list\">";
                case 'C' -> ordered(key) ? "</ol>" : "</ul>";
                case 'X' -> left(key);
                case 'P' -> pageSplit(key);
                case 'Y' -> belowMargin(key);
                default -> "";
            };
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int[] ref(String key) {
        int colon = key.indexOf(':');
        return new int[] {Integer.parseInt(key.substring(1, colon)), Integer.parseInt(key.substring(colon + 1))};
    }

    private boolean ordered(String key) {
        int[] r = ref(key);
        if (r[0] < 1 || r[0] > numbering.instances.size() || r[1] < 0 || r[1] > 8) {
            return false;
        }
        Numbering.Level def = numbering.instances.get(r[0] - 1).definition.levels[r[1]];
        return def != null && !"bullet".equals(def.format()) && !"none".equals(def.format());
    }

    private String item(ListLabels labels, String key) {
        int[] r = ref(key);
        pendingLabel = labels.next(r[0], r[1]);
        pendingKey = key.substring(1);
        return ordered(key) ? "<li value=\"" + labels.value(r[0], r[1]) + "\">" : "<li>";
    }

    private String belowMargin(String key) {
        int colon = key.indexOf(':');
        Section s = sections.get(Math.min(sections.size() - 1, Integer.parseInt(key.substring(1, colon))));
        return HtmlText.pt(Float.parseFloat(key.substring(colon + 1)) - s.marginTop);
    }

    private String pageSplit(String key) {
        int at = Integer.parseInt(key.substring(1));
        if (at >= sections.size() || sections.get(at).columns.size() < 2) {
            return "";
        }
        return "</div>\n<div class=\"s" + (at + 1) + "\">\n";
    }

    private String left(String key) {
        String[] parts = key.substring(1).split(":");
        Section s = sections.get(Math.min(sections.size() - 1, Integer.parseInt(parts[0])));
        float x = Float.parseFloat(parts[1]);
        float column = s.marginLeft;
        float at = s.marginLeft;
        for (float[] c : s.columns) {
            if (at <= x + 1) {
                column = at;
            }
            at += c[0] + (c.length > 1 ? c[1] : 0);
        }
        return HtmlText.pt(x - column - Float.parseFloat(parts[2]));
    }

    private String label(ListLabels labels, String key) {
        int[] r = ref(key);
        boolean pending = pendingLabel != null && key.substring(1).equals(pendingKey);
        String label = pending ? pendingLabel : labels.next(r[0], r[1]);
        if (pending) {
            pendingLabel = null;
        }
        boolean symbol = !label.isEmpty() && label.chars().allMatch(c -> c >= 0xE000 && c <= 0xF8FF);
        StringBuilder sb = new StringBuilder();
        HtmlText.escape(sb, symbol ? BULLET : label);
        return sb.append(' ').toString();
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
