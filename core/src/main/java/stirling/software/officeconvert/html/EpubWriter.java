package stirling.software.officeconvert.html;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.ListLabels;
import stirling.software.officeconvert.sink.MediaStore;
import stirling.software.officeconvert.sink.SpillBuffer;
import stirling.software.officeconvert.sink.ZipParts;

public final class EpubWriter implements DocSink, Closeable {

    private static final int CHAPTER_CHARS = 150_000;

    private static final String BULLET = "\u2022";

    private static final Pattern DATA_PICTURE = Pattern.compile(
            "src=\"data:(image/[a-z0-9.+-]+);base64,([A-Za-z0-9+/=]+)\"");

    private static final Pattern VOID = Pattern.compile("<(br|img|hr|col|wbr|input|source)(\\s[^<>]*?)?\\s*/?>");

    private static final Pattern HEADING = Pattern.compile("^\\s*<h([1-6])((?:\\s[^>]*)?)>");

    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]+)\"");

    private static final Pattern LOCAL_LINK = Pattern.compile("href=\"#([^\"\\x00]+)\"");

    private static final Pattern MARKUP = Pattern.compile("<[^>]*>|\\x00[^\\x00]*\\x00");

    private static final Pattern NOT_XML = Pattern.compile("[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");

    private record Chapter(String file, String title) {}

    private record Entry(int level, String title, String file, String id, boolean titled) {}

    private record Media(String file, String type) {}

    private final ZipOutputStream zip;
    private final MediaStore media;
    private final HtmlBlocks blocks;
    private final SpillBuffer body = new SpillBuffer();
    private final Map<Integer, List<Paragraph>> notes = new TreeMap<>();
    private final Map<String, Media> pictures = new HashMap<>();
    private final List<Media> pictureFiles = new ArrayList<>();
    private final List<Chapter> chapters = new ArrayList<>();
    private final List<Entry> toc = new ArrayList<>();
    private final Map<String, String> idFile = new HashMap<>();
    private int chapterChars;
    private int headings;
    private StyleSheet styles;
    private boolean started;

    public EpubWriter(OutputStream target, Pictures pictures) {
        this.zip = new ZipOutputStream(new KeepOpen(target), StandardCharsets.UTF_8);
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
        this.styles = styles;
        blocks.styles(styles);
    }

    private void start() throws IOException {
        if (!started) {
            started = true;
            ZipParts.stored(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        }
    }

    @Override
    public void block(Block block) throws IOException {
        start();
        StringBuilder sb = new StringBuilder(512);
        if (block instanceof Paragraph p) {
            blocks.paragraph(sb, p);
        } else {
            blocks.table(sb, (Table) block);
        }
        String html = xhtml(sb.toString());
        Matcher h = HEADING.matcher(html);
        int level = h.find() ? Integer.parseInt(h.group(1)) : 0;
        if (chapters.isEmpty()) {
            chapters.add(new Chapter(chapterFile(1), null));
        } else if (level == 1 && chapterChars > 0 || chapterChars > CHAPTER_CHARS) {
            body.placeholder("C");
            chapters.add(new Chapter(chapterFile(chapters.size() + 1), null));
            chapterChars = 0;
        }
        String file = chapters.get(chapters.size() - 1).file();
        if (level >= 1 && level <= 3) {
            String title = text(html);
            if (!title.isBlank()) {
                Matcher id = ID.matcher(html.substring(0, h.end()));
                String anchor;
                if (id.find()) {
                    anchor = id.group(1);
                } else {
                    anchor = "h" + (++headings);
                    html = html.substring(0, h.end() - 1) + " id=\"" + anchor + "\"" + html.substring(h.end() - 1);
                }
                toc.add(new Entry(level, title, file, anchor, h.group(2).contains("class=\"s-Title\"")));
                if (chapterChars == 0 && chapters.get(chapters.size() - 1).title() == null) {
                    chapters.set(chapters.size() - 1, new Chapter(file, title));
                }
            }
        }
        Matcher ids = ID.matcher(html);
        while (ids.find()) {
            idFile.putIfAbsent(ids.group(1), file);
        }
        html = LOCAL_LINK.matcher(html).replaceAll(m -> Matcher.quoteReplacement(
                "href=\"\0A" + m.group(1) + "\0\""));
        body.append(html);
        chapterChars += html.length();
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
    }

    @Override
    public void finish(Section last, HeaderFooterSet runningSet, Numbering numbering, StyleSheet finalStyles,
            String title, String author) throws IOException {
        start();
        if (finalStyles != null) {
            styles = finalStyles;
            blocks.styles(finalStyles);
        }
        String bookTitle = title != null && !title.isBlank() ? title.strip()
                : toc.isEmpty() ? "Document" : unescaped(toc.get(0).title());
        if (chapters.isEmpty()) {
            chapters.add(new Chapter(chapterFile(1), null));
        }
        String notesFile = notes.isEmpty() ? null : "text/notes.xhtml";
        if (notesFile != null) {
            for (Integer id : notes.keySet()) {
                idFile.put("fn-" + id, notesFile);
            }
        }
        ListLabels labels = new ListLabels(numbering == null ? new Numbering() : numbering);
        Writer w = new BufferedWriter(new OutputStreamWriter(new KeepOpen(zip), StandardCharsets.UTF_8), 1 << 16);
        int[] current = {0};
        open(w, chapters.get(0), bookTitle);
        try {
            body.copyTo(w, key -> {
                try {
                    return resolve(key, w, current, bookTitle, labels);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        close(w);
        if (notesFile != null) {
            writeNotes(w, notesFile, labels);
        }
        String css = css();
        entry("OEBPS/styles/book.css", css.getBytes(StandardCharsets.UTF_8));
        entry("OEBPS/nav.xhtml", nav(bookTitle, notesFile).getBytes(StandardCharsets.UTF_8));
        String uid = "urn:uuid:" + UUID.nameUUIDFromBytes((bookTitle + "\n" + author + "\n" + toc).getBytes(
                StandardCharsets.UTF_8));
        entry("OEBPS/toc.ncx", ncx(bookTitle, uid, notesFile).getBytes(StandardCharsets.UTF_8));
        entry("OEBPS/content.opf", opf(bookTitle, author, uid, notesFile).getBytes(StandardCharsets.UTF_8));
        entry("META-INF/container.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles>\n</container>\n").getBytes(StandardCharsets.UTF_8));
        zip.finish();
    }

    private String resolve(String key, Writer w, int[] current, String bookTitle, ListLabels labels)
            throws IOException {
        if (key.equals("C")) {
            close(w);
            current[0]++;
            open(w, chapters.get(current[0]), bookTitle);
            return "";
        }
        if (key.startsWith("A")) {
            String id = key.substring(1);
            String target = idFile.get(id);
            String here = chapters.get(current[0]).file();
            String prefix = target == null || target.equals(here) ? "" : target.substring(target.indexOf('/') + 1);
            StringBuilder sb = new StringBuilder(prefix).append('#');
            HtmlText.escape(sb, id);
            return sb.toString();
        }
        return label(labels, key);
    }

    private void open(Writer w, Chapter chapter, String bookTitle) throws IOException {
        zip.putNextEntry(new ZipEntry("OEBPS/" + chapter.file()));
        w.write(xhtmlHead(chapter.title() != null ? chapter.title() : escaped(bookTitle), "../styles/book.css"));
    }

    private void close(Writer w) throws IOException {
        w.write("</body>\n</html>\n");
        w.flush();
        zip.closeEntry();
    }

    private void writeNotes(Writer w, String file, ListLabels labels) throws IOException {
        zip.putNextEntry(new ZipEntry("OEBPS/" + file));
        StringBuilder sb = new StringBuilder(xhtmlHead("Notes", "../styles/book.css"));
        sb.append("<section class=\"footnotes\" epub:type=\"footnotes\">\n");
        for (Map.Entry<Integer, List<Paragraph>> n : notes.entrySet()) {
            sb.append("<aside id=\"fn-").append(n.getKey()).append("\" epub:type=\"footnote\">\n");
            StringBuilder inner = new StringBuilder();
            for (Paragraph p : n.getValue()) {
                blocks.paragraph(inner, p);
            }
            sb.append(LOCAL_LINK.matcher(xhtml(inner.toString())).replaceAll(m -> Matcher.quoteReplacement(
                    "href=\"\0A" + m.group(1) + "\0\""))).append("</aside>\n");
        }
        sb.append("</section>\n</body>\n</html>\n");
        String text = sb.toString();
        Matcher m = Pattern.compile("\\x00([^\\x00]*)\\x00").matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String key = m.group(1);
            String value;
            if (key.startsWith("A")) {
                String target = idFile.get(key.substring(1));
                String rel = target == null || target.equals(file) ? "" : target.substring(target.indexOf('/') + 1);
                value = rel + "#" + key.substring(1);
            } else {
                value = label(labels, key);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        w.write(out.toString());
        w.flush();
        zip.closeEntry();
    }

    private void entry(String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private String xhtml(String html) throws IOException {
        String s = NOT_XML.matcher(html).replaceAll("");
        Matcher m = DATA_PICTURE.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String file = picture(m.group(1), m.group(2));
            m.appendReplacement(out, Matcher.quoteReplacement(file == null ? "src=\"\"" : "src=\"../" + file + "\""));
        }
        m.appendTail(out);
        return VOID.matcher(out).replaceAll(v -> Matcher.quoteReplacement(
                "<" + v.group(1) + (v.group(2) == null ? "" : v.group(2)) + "/>"));
    }

    private String picture(String type, String base64) throws IOException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String hash = sha1(bytes);
        Media known = pictures.get(hash);
        if (known != null) {
            return known.file();
        }
        String ext = switch (type.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/svg+xml" -> "svg";
            default -> null;
        };
        if (ext == null) {
            return null;
        }
        Media m = new Media("images/p" + (pictureFiles.size() + 1) + "." + ext,
                ext.equals("jpg") ? "image/jpeg" : ext.equals("svg") ? "image/svg+xml" : "image/" + ext);
        ZipParts.picture(zip, "OEBPS/" + m.file(), bytes);
        pictures.put(hash, m);
        pictureFiles.add(m);
        return m.file();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String chapterFile(int n) {
        return "text/ch" + n + ".xhtml";
    }

    private static String text(String html) {
        String t = unescaped(MARKUP.matcher(html).replaceAll("")).replaceAll("\\s+", " ").strip();
        if (t.codePointCount(0, t.length()) > 200) {
            t = t.substring(0, t.offsetByCodePoints(0, 200)).strip();
        }
        return escaped(t);
    }

    private static String xhtmlHead(String title, String css) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n")
                .append("<head>\n<meta charset=\"utf-8\"/>\n<title>");
        sb.append(title);
        sb.append("</title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"").append(css).append("\"/>\n")
                .append("</head>\n<body>\n");
        return sb.toString();
    }

    private String css() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("body{").append(HtmlText.css(styles.normal)).append(";line-height:1.2}\n")
                .append("p,h1,h2,h3,h4,h5,h6{margin:0;white-space:pre-wrap;tab-size:4;overflow-wrap:break-word}\n")
                .append("h1,h2,h3,h4,h5,h6{font-size:inherit;font-weight:inherit}\n")
                .append("table{border-collapse:collapse;max-width:100%}\n")
                .append("td,th{font-weight:inherit;text-align:inherit;vertical-align:top}\n")
                .append("img{max-width:100%;height:auto}\n")
                .append(".marker{display:inline-block;min-width:1.5em;text-indent:0}\n")
                .append(".float-left{float:left;margin:2pt 9pt 6pt 0}.float-right{float:right;margin:2pt 0 6pt 9pt}\n")
                .append(".box{margin:6pt 0;padding:4pt}\n")
                .append(".footnotes{font-size:0.85em}\n")
                .append(".page-break{page-break-after:always}\n");
        for (StyleSheet.Style s : styles.all()) {
            if (!"Normal".equals(s.id())) {
                sb.append('.').append(HtmlBlocks.styleClass(s.id())).append('{').append(HtmlText.css(s.run())).append("}\n");
            }
        }
        return sb.toString();
    }

    private String nav(String bookTitle, String notesFile) {
        StringBuilder sb = new StringBuilder(xhtmlHead(escaped(bookTitle), "styles/book.css"));
        sb.append("<nav epub:type=\"toc\" id=\"toc\">\n<h1>Contents</h1>\n");
        List<Entry> entries = entries(notesFile);
        int depth = 0;
        int base = entries.stream().mapToInt(Entry::level).min().orElse(1);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int level = Math.min(e.level() - base + 1, depth + 1);
            if (level > depth) {
                while (depth < level) {
                    sb.append(depth == 0 ? "<ol>\n" : "\n<ol>\n");
                    depth++;
                }
            } else {
                sb.append("</li>\n");
                while (depth > level) {
                    sb.append("</ol>\n</li>\n");
                    depth--;
                }
            }
            sb.append("<li><a href=\"").append(e.file()).append(e.id() == null ? "" : "#" + e.id()).append("\">")
                    .append(e.title()).append("</a>");
        }
        if (depth > 0) {
            sb.append("</li>\n");
            while (--depth > 0) {
                sb.append("</ol>\n</li>\n");
            }
            sb.append("</ol>\n");
        }
        sb.append("</nav>\n<nav epub:type=\"landmarks\" id=\"landmarks\" hidden=\"hidden\">\n<h2>Guide</h2>\n<ol>\n")
                .append("<li><a epub:type=\"bodymatter\" href=\"").append(chapters.get(0).file())
                .append("\">Start</a></li>\n</ol>\n</nav>\n</body>\n</html>\n");
        return sb.toString();
    }

    private List<Entry> entries(String notesFile) {
        boolean titledChapters = toc.stream().filter(Entry::titled).count() > 1;
        List<Entry> entries = new ArrayList<>();
        for (Entry e : toc) {
            entries.add(titledChapters && !e.titled() ? new Entry(e.level() + 1, e.title(), e.file(), e.id(), false) : e);
        }
        if (entries.isEmpty()) {
            for (int i = 0; i < chapters.size(); i++) {
                Chapter c = chapters.get(i);
                entries.add(new Entry(1, c.title() != null ? c.title() : "Part " + (i + 1), c.file(), null, false));
            }
        } else if (!toc.get(0).file().equals(chapters.get(0).file()) || chapterStartsLater()) {
            entries.add(0, new Entry(entries.get(0).level(), "Start", chapters.get(0).file(), null, false));
        }
        if (notesFile != null) {
            entries.add(new Entry(entries.stream().mapToInt(Entry::level).min().orElse(1), "Notes", notesFile, null, false));
        }
        return entries;
    }

    private boolean chapterStartsLater() {
        return chapters.get(0).title() == null;
    }

    private String ncx(String bookTitle, String uid, String notesFile) {
        List<Entry> entries = entries(notesFile);
        int base = entries.stream().mapToInt(Entry::level).min().orElse(1);
        StringBuilder points = new StringBuilder(2048);
        int depth = 0;
        int deepest = 1;
        int order = 0;
        for (Entry e : entries) {
            int level = Math.min(e.level() - base + 1, depth + 1);
            while (depth >= level) {
                points.append("</navPoint>\n");
                depth--;
            }
            order++;
            depth++;
            deepest = Math.max(deepest, depth);
            points.append("<navPoint id=\"np").append(order).append("\" playOrder=\"").append(order)
                    .append("\"><navLabel><text>").append(e.title()).append("</text></navLabel><content src=\"")
                    .append(e.file()).append(e.id() == null ? "" : "#" + e.id()).append("\"/>");
            points.append('\n');
        }
        while (depth-- > 0) {
            points.append("</navPoint>\n");
        }
        StringBuilder sb = new StringBuilder(points.length() + 512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n<head>\n")
                .append("<meta name=\"dtb:uid\" content=\"").append(uid).append("\"/>\n")
                .append("<meta name=\"dtb:depth\" content=\"").append(deepest).append("\"/>\n")
                .append("<meta name=\"dtb:totalPageCount\" content=\"0\"/>\n")
                .append("<meta name=\"dtb:maxPageNumber\" content=\"0\"/>\n</head>\n<docTitle><text>")
                .append(escaped(bookTitle)).append("</text></docTitle>\n<navMap>\n").append(points)
                .append("</navMap>\n</ncx>\n");
        return sb.toString();
    }

    private String opf(String bookTitle, String author, String uid, String notesFile) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">\n")
                .append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                .append("<dc:identifier id=\"uid\">").append(uid).append("</dc:identifier>\n")
                .append("<dc:title>").append(escaped(bookTitle)).append("</dc:title>\n")
                .append("<dc:language>und</dc:language>\n");
        if (author != null && !author.isBlank()) {
            sb.append("<dc:creator>").append(escaped(author.strip())).append("</dc:creator>\n");
        }
        sb.append("<meta property=\"dcterms:modified\">")
                .append(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()).append("</meta>\n")
                .append("<meta name=\"generator\" content=\"Stirling Office Convert\"/>\n</metadata>\n<manifest>\n")
                .append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
                .append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
                .append("<item id=\"css\" href=\"styles/book.css\" media-type=\"text/css\"/>\n");
        for (int i = 0; i < chapters.size(); i++) {
            sb.append("<item id=\"ch").append(i + 1).append("\" href=\"").append(chapters.get(i).file())
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        if (notesFile != null) {
            sb.append("<item id=\"notes\" href=\"").append(notesFile).append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        for (int i = 0; i < pictureFiles.size(); i++) {
            Media m = pictureFiles.get(i);
            sb.append("<item id=\"img").append(i + 1).append("\" href=\"").append(m.file()).append("\" media-type=\"")
                    .append(m.type()).append("\"/>\n");
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n");
        for (int i = 0; i < chapters.size(); i++) {
            sb.append("<itemref idref=\"ch").append(i + 1).append("\"/>\n");
        }
        if (notesFile != null) {
            sb.append("<itemref idref=\"notes\"/>\n");
        }
        sb.append("</spine>\n</package>\n");
        return sb.toString();
    }

    private static String unescaped(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }

    private static String escaped(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        HtmlText.escape(sb, NOT_XML.matcher(s).replaceAll(""));
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
            zip.close();
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
