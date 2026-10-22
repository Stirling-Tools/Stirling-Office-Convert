package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MobiBook {

    static final long MAX_FILE_BYTES = 256L << 20;

    static final String DRM = "The eBook is protected with DRM (encryption), so it cannot be converted; only books"
            + " without DRM convert";

    private static final Pattern FILEPOS = Pattern.compile("filepos\\s*=\\s*['\"]?0*(\\d{1,10})['\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LINK = Pattern.compile("(<a\\b[^<>]*?)\\bfilepos\\s*=\\s*['\"]?0*(\\d{1,10})['\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RECINDEX = Pattern.compile("(?<![A-Za-z])recindex\\s*=\\s*['\"]?0*(\\d{1,6})['\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OTHER_RECINDEX = Pattern.compile("\\b(?:hi|lo)recindex\\s*=\\s*['\"]?\\d*['\"]?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GUIDE = Pattern.compile("<guide\\b.*?</guide\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PAGE_BREAK = Pattern.compile("<mbp:pagebreak\\b[^<>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern MBP = Pattern.compile("</?mbp:[^<>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern PARAGRAPH = Pattern.compile("<(p|div|blockquote|h[1-6]|li|ul|ol)\\b([^<>]*)>",
            Pattern.CASE_INSENSITIVE);

    private static final String BASE_STYLE = "<style>blockquote{margin:0 0 0 2em}p{margin:0;text-indent:1.5em}</style>";

    private static final Pattern SIZE = Pattern.compile("\\s(width|height)\\s*=\\s*['\"]?(-?[0-9.]+(?:em|pt|px|%)?)['\"]?",
            Pattern.CASE_INSENSITIVE);

    private MobiBook() {}

    static boolean is(Path source) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(PalmDatabase.HEADER);
        }
        return is(head);
    }

    static boolean named(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".mobi") || n.endsWith(".azw3") || n.endsWith(".azw") || n.endsWith(".prc")
                || n.endsWith(".pdb");
    }

    static boolean is(byte[] head) {
        String type = PalmDatabase.type(head);
        return type.equals("BOOKMOBI") || type.equals("TEXtREAd");
    }

    static Ebook read(Path source) throws IOException {
        if (Files.size(source) > MAX_FILE_BYTES) {
            throw new IOException("The eBook is larger than " + (MAX_FILE_BYTES >> 20) + " MB");
        }
        PalmDatabase pdb = new PalmDatabase(Files.readAllBytes(source));
        boolean mobi = pdb.type().equals("BOOKMOBI");
        MobiHeader h = MobiHeader.parse(pdb.record(0), mobi);
        if (mobi && h.encryption() != 0) {
            throw new IOException(DRM);
        }
        if (mobi) {
            int kf8 = kf8(pdb, h);
            if (kf8 >= 0) {
                return Kf8Book.read(pdb, kf8, h);
            }
        }
        byte[] text = MobiText.read(pdb, 0, h);
        byte[] html = (mobi ? html(text, h.charset()) : plainText(text, h.charset())).getBytes(StandardCharsets.UTF_8);
        int images = h.firstImage();
        HtmlAssets assets = Ebook.assets(reference -> {
            if (reference.equals(Ebook.PAGE)) {
                return html;
            }
            int record = picture(reference, images);
            return record >= 0 && pdb.picture(record) ? pdb.record(record) : null;
        });
        String cover = null;
        int coverOffset = h.number(MobiHeader.EXTH_COVER);
        if (images >= 0 && coverOffset >= 0 && pdb.picture(images + coverOffset)) {
            cover = "images/" + (coverOffset + 1);
        }
        EpubBook.Book book = EpubBook.assemble(assets, List.of(Ebook.PAGE), h.title(),
                h.text(MobiHeader.EXTH_AUTHOR), cover);
        return new Ebook(book, assets);
    }

    static int picture(String reference, int first) {
        if (first < 0 || !reference.startsWith("images/")) {
            return -1;
        }
        try {
            int n = Integer.parseInt(reference.substring(7));
            return n < 1 ? -1 : first + n - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int kf8(PalmDatabase pdb, MobiHeader h) throws IOException {
        if (h.version() >= 8) {
            return 0;
        }
        int boundary = h.number(MobiHeader.EXTH_KF8_BOUNDARY);
        if (boundary <= 0 || !pdb.has(boundary)) {
            return -1;
        }
        MobiHeader k = MobiHeader.parse(pdb.record(boundary), true);
        return k.version() >= 8 ? boundary : -1;
    }

    static final int MAX_TARGETS = 100_000;

    static String html(byte[] text, Charset charset) {
        String raw = new String(text, StandardCharsets.ISO_8859_1);
        TreeSet<Long> positions = new TreeSet<>();
        Matcher m = FILEPOS.matcher(raw);
        while (m.find() && positions.size() < MAX_TARGETS) {
            long p = Long.parseLong(m.group(1));
            if (p > 0 && p < raw.length()) {
                positions.add(p);
            }
        }
        StringBuilder anchored = new StringBuilder(raw.length() + positions.size() * 32);
        int from = 0;
        int scanned = 0;
        boolean inTag = false;
        for (long target : positions) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            int at = (int) target;
            if (at < from) {
                continue;
            }
            for (; scanned < at; scanned++) {
                char c = raw.charAt(scanned);
                if (c == '<') {
                    inTag = true;
                } else if (c == '>') {
                    inTag = false;
                }
            }
            if (inTag) {
                int end = raw.indexOf('>', at);
                at = end < 0 ? raw.length() : end + 1;
                scanned = at;
                inTag = false;
            }
            anchored.append(raw, from, at).append("<a id=\"filepos").append(target).append("\"></a>");
            from = at;
        }
        anchored.append(raw, from, raw.length());
        String s = new String(anchored.toString().getBytes(StandardCharsets.ISO_8859_1), charset);
        s = GUIDE.matcher(s).replaceAll("");
        s = PAGE_BREAK.matcher(s).replaceAll("<div style=\"page-break-before:always\"></div>");
        s = MBP.matcher(s).replaceAll("");
        s = LINK.matcher(s).replaceAll("$1href=\"#filepos$2\"");
        s = OTHER_RECINDEX.matcher(s).replaceAll("");
        s = RECINDEX.matcher(s).replaceAll("src=\"images/$1\"");
        return BASE_STYLE + paragraphSizes(s);
    }

    private static String paragraphSizes(String s) {
        Matcher m = PARAGRAPH.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String attrs = m.group(2);
            Matcher size = SIZE.matcher(attrs);
            StringBuilder style = new StringBuilder();
            while (size.find()) {
                String value = size.group(2);
                if (!value.matches(".*[a-z%]$")) {
                    value += "px";
                }
                style.append(size.group(1).equalsIgnoreCase("width") ? "text-indent:" : "margin-top:").append(value)
                        .append(';');
            }
            String replacement = m.group(0);
            if (!style.isEmpty() && !attrs.toLowerCase(Locale.ROOT).contains("style")) {
                replacement = "<" + m.group(1) + SIZE.matcher(attrs).replaceAll("") + " style=\"" + style + "\">";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String plainText(byte[] text, Charset charset) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String line : new String(text, charset).split("\r\n|\r|\n", -1)) {
            html.append("<p>").append(line.isBlank() ? "&nbsp;" : escape(line)).append("</p>");
        }
        return html.append("</body></html>").toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
