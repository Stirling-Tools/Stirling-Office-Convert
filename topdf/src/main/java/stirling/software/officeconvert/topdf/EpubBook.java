package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import stirling.software.officeconvert.topdf.html.HtmlDocument;
import stirling.software.officeconvert.topdf.html.HtmlRefs;

final class EpubBook {

    static final int MAX_CHAPTERS = 5_000;

    static final long MAX_BOOK_CHARS = 48L << 20;

    static final long MAX_SCOPED_SHEET_CHARS = 8L << 20;

    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "source", "track", "wbr", "param", "keygen");

    private static final Pattern SELF_CLOSED = Pattern.compile("<([A-Za-z][A-Za-z0-9:_-]*)(\\s[^<>]*?)?/>");

    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*>");

    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);

    private EpubBook() {}

    record Book(String html, String title, String author, Map<String, String> fonts) {

        Book(String html, String title, String author) {
            this(html, title, author, Map.of());
        }
    }

    static final int MAX_FONT_FACES = 64;

    private static final Pattern FONT_FACE = Pattern.compile("@font-face\\s*\\{([^}]{0,4000})}", Pattern.CASE_INSENSITIVE);

    private static final Pattern FACE_FAMILY = Pattern.compile("font-family\\s*:\\s*([\"']?)([^;\"'}]{1,100})\\1",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FACE_URL = Pattern.compile("url\\(\\s*([\"']?)([^\"')]{1,1000})\\1\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    static void fontFaces(String css, String sheet, Map<String, String> into) {
        Matcher m = FONT_FACE.matcher(withoutComments(css));
        while (m.find() && into.size() < MAX_FONT_FACES) {
            Matcher family = FACE_FAMILY.matcher(m.group(1));
            Matcher url = FACE_URL.matcher(m.group(1));
            if (family.find() && url.find()) {
                String path = resolve(sheet, url.group(2).strip());
                String name = family.group(2).strip();
                if (path != null && !name.isEmpty()) {
                    into.putIfAbsent(name, path);
                }
            }
        }
    }

    static boolean is(HtmlPackage pkg) throws IOException {
        byte[] mimetype = pkg.read("mimetype");
        if (mimetype != null) {
            return new String(mimetype, StandardCharsets.US_ASCII).strip().equalsIgnoreCase("application/epub+zip");
        }
        return pkg.read("META-INF/container.xml") != null;
    }

    static Book read(HtmlPackage pkg) throws IOException {
        String opfPath = rootFile(pkg);
        if (opfPath == null) {
            throw new IOException("The EPUB has no package document (META-INF/container.xml names none)");
        }
        byte[] opfBytes = pkg.read(opfPath);
        if (opfBytes == null) {
            throw new IOException("The EPUB's package document " + opfPath + " is missing");
        }
        Document opf = Jsoup.parse(markup(opfBytes), "", Parser.xmlParser());
        Map<String, Element> manifest = new HashMap<>();
        for (Element item : opf.select("manifest > item, opf|manifest > opf|item")) {
            manifest.putIfAbsent(item.attr("id"), item);
        }
        List<String> chapters = new ArrayList<>();
        Set<String> folded = new HashSet<>();
        for (Element ref : opf.select("spine > itemref, opf|spine > opf|itemref")) {
            Element item = manifest.get(ref.attr("idref"));
            OfficeToPdf.stopIfInterrupted();
            if (item == null) {
                throw new IOException("The EPUB spine references a missing manifest item: " + ref.attr("idref"));
            }
            String path = resolve(opfPath, item.attr("href"));
            if (path == null) {
                throw new IOException("The EPUB spine references an invalid chapter path: " + item.attr("href"));
            }
            if (folded.add(path.toLowerCase(Locale.ROOT))) {
                if (chapters.size() >= MAX_CHAPTERS) {
                    throw new IOException("The EPUB has more than " + MAX_CHAPTERS + " chapters");
                }
                chapters.add(path);
            }
        }
        if (chapters.isEmpty()) {
            throw new IOException("The EPUB's reading order (spine) lists no documents");
        }
        refuseDrm(pkg, opfPath, manifest.values(), chapters);
        String title = text(opf, "dc|title, title");
        String author = text(opf, "dc|creator, creator");
        String cover = cover(opf, manifest, opfPath);
        return assemble(pkg, chapters, title, author, cover);
    }

    static Book assemble(HtmlAssets files, List<String> chapters, String title, String author, String cover)
            throws IOException {
        if (chapters.size() > MAX_CHAPTERS) {
            throw new IOException("The eBook has more than " + MAX_CHAPTERS + " chapters");
        }
        Map<String, Integer> chapterOf = new HashMap<>();
        for (int i = 0; i < chapters.size(); i++) {
            chapterOf.put(chapters.get(i), i);
        }
        Map<String, List<Integer>> linkedBy = new LinkedHashMap<>();
        int linking = 0;
        StringBuilder styles = new StringBuilder();
        StringBuilder body = new StringBuilder();
        Map<String, String> fonts = new LinkedHashMap<>();
        boolean coverShown = false;
        boolean afterCover = false;
        for (int i = 0; i < chapters.size(); i++) {
            OfficeToPdf.stopIfInterrupted();
            String path = chapters.get(i);
            byte[] bytes = files.read(path);
            if (bytes == null) {
                throw new IOException("The eBook chapter " + path + " is missing or exceeds the asset size limit");
            }
            Document doc = Jsoup.parse(xhtmlAsHtml(markup(bytes)), "");
            List<Element> links = doc.select("link[rel~=(?i)stylesheet][href]");
            if (!links.isEmpty()) {
                linking++;
            }
            for (Element link : links) {
                String sheet = resolve(path, link.attr("href"));
                if (sheet != null) {
                    List<Integer> by = linkedBy.computeIfAbsent(sheet, k -> new ArrayList<>());
                    if (!by.contains(i)) {
                        by.add(i);
                    }
                }
            }
            for (Element style : doc.select("style")) {
                fontFaces(style.data(), path, fonts);
                if (styles.length() + body.length() + style.data().length() > MAX_BOOK_CHARS) {
                    throw new IOException("The eBook text exceeds " + (MAX_BOOK_CHARS >> 20) + " MB");
                }
                styles.append(scoped(style.data(), i, 0)).append('\n');
            }
            Element b = doc.body();
            unwrapSvgPictures(b);
            rewrite(b, path, i, chapterOf);
            if (cover != null && b.select("img[src], image").stream()
                    .anyMatch(e -> ("/" + cover).equals(e.attr("src")) || ("/" + cover).equals(e.attr("href"))
                            || ("/" + cover).equals(e.attr("xlink:href")))) {
                coverShown = true;
            }
            boolean coverPage = coverPage(doc, b);
            if (coverPage) {
                for (Element img : b.select("img")) {
                    String w = img.attr("width");
                    String h = img.attr("height");
                    img.removeAttr("width").removeAttr("height");
                    img.attr("style", positive(w) && positive(h) ? "width:100%;aspect-ratio:" + w + "/" + h : "width:100%");
                }
            }
            String style = (i == 0 || !afterCover && brief(b) ? "" : "page-break-before:always;") + b.attr("style");
            afterCover = coverPage;
            body.append("<section id=\"epub-c").append(i).append("\"");
            if (!b.attr("class").isBlank()) {
                body.append(" class=\"").append(escape(b.attr("class"))).append('"');
            }
            if (!style.isBlank()) {
                body.append(" style=\"").append(escape(style)).append('"');
            }
            String chapterHtml = b.html();
            if (styles.length() + body.length() + chapterHtml.length() > MAX_BOOK_CHARS) {
                throw new IOException("The eBook text exceeds " + (MAX_BOOK_CHARS >> 20) + " MB");
            }
            body.append('>').append(chapterHtml).append("</section>\n");
        }
        Set<String> sheets = new LinkedHashSet<>();
        long inlined = 0;
        for (Map.Entry<String, List<Integer>> e : linkedBy.entrySet()) {
            byte[] read = fonts.size() < MAX_FONT_FACES || e.getValue().size() < linking ? files.read(e.getKey()) : null;
            if (read != null) {
                fontFaces(new String(read, StandardCharsets.UTF_8), e.getKey(), fonts);
            }
            byte[] css = e.getValue().size() < linking ? read : null;
            long cost = css == null ? 0 : (long) css.length * e.getValue().size();
            if (css == null || inlined + cost > MAX_SCOPED_SHEET_CHARS) {
                sheets.add(e.getKey());
                continue;
            }
            inlined += cost;
            String text = packageUrls(new String(css, StandardCharsets.UTF_8), e.getKey());
            for (int chapter : e.getValue()) {
                styles.append(scoped(text, chapter, 0)).append('\n');
            }
        }
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        if (title != null) {
            html.append("<title>").append(escape(title)).append("</title>");
        }
        if (author != null) {
            html.append("<meta name=\"author\" content=\"").append(escape(author)).append("\">");
        }
        for (String sheet : sheets) {
            html.append("<link rel=\"stylesheet\" href=\"/").append(escape(sheet)).append("\">");
        }
        if (!styles.isEmpty()) {
            html.append("<style>").append(styles.toString().replace("</style", "<\\/style")).append("</style>");
        }
        html.append("<style>@page{").append(HtmlDocument.BOOK_PAGE).append(":1}</style>");
        html.append("</head><body>");
        if (cover != null && !coverShown) {
            html.append("<div style=\"page-break-after:always;text-align:center\"><img src=\"/").append(escape(cover))
                    .append("\" alt=\"\" style=\"max-width:100%\"></div>");
        }
        html.append(body).append("</body></html>");
        if (html.length() > MAX_BOOK_CHARS) {
            throw new IOException("The eBook text exceeds " + (MAX_BOOK_CHARS >> 20) + " MB");
        }
        return new Book(html.toString(), title, author, fonts);
    }

    private static final int BRIEF_TEXT = 600;

    private static boolean brief(Element body) {
        return body.selectFirst("[epub:type~=(?i)\\b(foot|end|rear)notes?\\b]") != null
                || body.selectFirst("h1, h2, h3, img, svg, image, table") == null && body.text().length() < BRIEF_TEXT;
    }

    private static final Pattern DECLARED = Pattern.compile(
            "^\\s*<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]{1,40})[\"']|<meta[^>]*charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]{1,40})",
            Pattern.CASE_INSENSITIVE);

    static String markup(byte[] bytes) {
        int n = bytes.length;
        if (n >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, n - 3, StandardCharsets.UTF_8);
        }
        if (n >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, n - 2, StandardCharsets.UTF_16LE);
        }
        if (n >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, n - 2, StandardCharsets.UTF_16BE);
        }
        if (n >= 4 && bytes[0] == '<' && bytes[1] == 0) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        if (n >= 4 && bytes[0] == 0 && bytes[1] == '<') {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        Matcher m = DECLARED.matcher(new String(bytes, 0, Math.min(n, 2048), StandardCharsets.ISO_8859_1));
        if (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                Charset charset = Charset.forName(name);
                if (!charset.equals(StandardCharsets.UTF_8) && !name.toLowerCase(Locale.ROOT).startsWith("utf-16")) {
                    return new String(bytes, charset);
                }
            } catch (IllegalArgumentException e) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final String IDPF_FONTS = "http://www.idpf.org/2008/embedding";

    private static final String ADOBE_FONTS = "http://ns.adobe.com/pdf/enc#RC";

    private static final Set<String> CONTENT_TYPES = Set.of("application/xhtml+xml", "text/html", "image/svg+xml",
            "application/x-dtbook+xml");

    private static void refuseDrm(HtmlPackage pkg, String opfPath, Iterable<Element> items, List<String> chapters)
            throws IOException {
        if (pkg.read("META-INF/rights.xml") != null) {
            throw new IOException(MobiBook.DRM);
        }
        byte[] encryption = pkg.read("META-INF/encryption.xml");
        if (encryption == null) {
            return;
        }
        Set<String> content = new HashSet<>();
        for (String chapter : chapters) {
            content.add(folded(chapter));
        }
        for (Element item : items) {
            String path = resolve(opfPath, item.attr("href"));
            if (path != null && CONTENT_TYPES.contains(item.attr("media-type").strip().toLowerCase(Locale.ROOT))) {
                content.add(folded(path));
            }
        }
        Document enc = Jsoup.parse(markup(encryption), "", Parser.xmlParser());
        for (Element data : local(enc, "encrypteddata")) {
            List<Element> methods = local(data, "encryptionmethod");
            String algorithm = methods.isEmpty() ? "" : methods.get(0).attr("Algorithm").strip();
            if (algorithm.equals(IDPF_FONTS) || algorithm.equals(ADOBE_FONTS)) {
                continue;
            }
            for (Element ref : local(data, "cipherreference")) {
                String path = resolve("", ref.attr("URI"));
                if (path != null && content.contains(folded(path))) {
                    throw new IOException(MobiBook.DRM);
                }
            }
        }
    }

    private static String folded(String path) {
        return path.replaceFirst("^/+", "").toLowerCase(Locale.ROOT);
    }

    static HtmlAssets fonts(HtmlPackage pkg) throws IOException {
        byte[] encryption = pkg.read("META-INF/encryption.xml");
        String opfPath = rootFile(pkg);
        byte[] opfBytes = opfPath == null ? null : pkg.read(opfPath);
        if (encryption == null || opfBytes == null) {
            return pkg;
        }
        Document opf = Jsoup.parse(markup(opfBytes), "", Parser.xmlParser());
        Element pack = opf.selectFirst("package, opf|package");
        String uidId = pack == null ? "" : pack.attr("unique-identifier");
        String uid = null;
        String uuid = null;
        for (Element id : opf.select("dc|identifier, identifier")) {
            String text = id.text().strip();
            if (uid == null && (uidId.isEmpty() || uidId.equals(id.id()))) {
                uid = text;
            }
            if (uuid == null && text.toLowerCase(Locale.ROOT).startsWith("urn:uuid:")) {
                uuid = text.substring(9);
            }
        }
        Map<String, byte[]> keys = new HashMap<>();
        Document enc = Jsoup.parse(markup(encryption), "", Parser.xmlParser());
        for (Element data : local(enc, "encrypteddata")) {
            List<Element> methods = local(data, "encryptionmethod");
            List<Element> refs = local(data, "cipherreference");
            Element method = methods.isEmpty() ? null : methods.get(0);
            Element ref = refs.isEmpty() ? null : refs.get(0);
            if (method == null || ref == null || keys.size() >= 1_000) {
                continue;
            }
            String path = resolve("", ref.attr("URI"));
            byte[] key = switch (method.attr("Algorithm")) {
                case IDPF_FONTS -> uid == null ? null : sha1(uid.replaceAll("[ \\t\\r\\n]", ""));
                case ADOBE_FONTS -> uuid == null ? null : adobeKey(uuid);
                default -> null;
            };
            if (path != null && key != null) {
                keys.put(path.toLowerCase(Locale.ROOT), key);
            }
        }
        if (keys.isEmpty()) {
            return pkg;
        }
        return new HtmlAssets() {
            @Override
            public String base() {
                return pkg.base();
            }

            @Override
            public byte[] read(String reference) throws IOException {
                byte[] bytes = pkg.read(reference);
                byte[] key = reference == null || bytes == null ? null
                        : keys.get(reference.replaceFirst("^/+", "").toLowerCase(Locale.ROOT));
                if (key == null) {
                    return bytes;
                }
                byte[] plain = bytes.clone();
                int count = Math.min(plain.length, key.length == 20 ? 1040 : 1024);
                for (int i = 0; i < count; i++) {
                    plain[i] ^= key[i % key.length];
                }
                return plain;
            }
        };
    }

    private static List<Element> local(Element root, String name) {
        List<Element> out = new ArrayList<>();
        for (Element e : root.getAllElements()) {
            String n = e.normalName();
            if (n.equals(name) || n.endsWith(":" + name)) {
                out.add(e);
            }
        }
        return out;
    }

    private static byte[] sha1(String s) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] adobeKey(String uuid) {
        String hex = uuid.replace("-", "").strip();
        if (!hex.matches("[0-9A-Fa-f]{32}")) {
            return null;
        }
        return HexFormat.of().parseHex(hex);
    }

    private static String rootFile(HtmlPackage pkg) throws IOException {
        byte[] container = pkg.read("META-INF/container.xml");
        if (container != null) {
            Document c = Jsoup.parse(new String(container, StandardCharsets.UTF_8), "", Parser.xmlParser());
            for (Element root : c.select("rootfile[full-path]")) {
                String type = root.attr("media-type");
                if (type.isEmpty() || type.equalsIgnoreCase("application/oebps-package+xml")) {
                    String path = resolve("", root.attr("full-path"));
                    if (path != null) {
                        return path;
                    }
                }
            }
        }
        for (String name : pkg.names()) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                return name;
            }
        }
        return null;
    }

    private static String cover(Document opf, Map<String, Element> manifest, String opfPath) {
        for (Element item : manifest.values()) {
            if (item.attr("properties").toLowerCase(Locale.ROOT).contains("cover-image")) {
                return resolve(opfPath, item.attr("href"));
            }
        }
        Element meta = opf.selectFirst("meta[name=cover][content]");
        if (meta != null) {
            Element item = manifest.get(meta.attr("content"));
            if (item != null && item.attr("media-type").toLowerCase(Locale.ROOT).startsWith("image/")) {
                return resolve(opfPath, item.attr("href"));
            }
        }
        return null;
    }

    private static String text(Document opf, String query) {
        Element e = opf.selectFirst(query);
        if (e == null) {
            return null;
        }
        String t = e.text().strip();
        return t.isEmpty() ? null : t;
    }

    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*+(?:(['\"])([^'\"]*+)\\1|([^'\"()]++))\\s*+\\)");

    static String packageUrls(String css, String sheet) {
        Matcher m = CSS_URL.matcher(css);
        StringBuilder out = new StringBuilder(css.length());
        while (m.find()) {
            String ref = (m.group(2) != null ? m.group(2) : m.group(3)).strip();
            String replacement = m.group(0);
            if (!ref.regionMatches(true, 0, "data:", 0, 5) && !ref.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                String path = resolve(sheet, ref);
                replacement = path == null ? "none" : "url(\"/" + path.replace("\"", "%22") + "\")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String xhtmlAsHtml(String xhtml) {
        String s = XML_DECLARATION.matcher(xhtml).replaceFirst("");
        s = DOCTYPE.matcher(s).replaceFirst("");
        Matcher m = SELF_CLOSED.matcher(s);
        StringBuilder out = new StringBuilder(s.length() + 64);
        while (m.find()) {
            String tag = m.group(1);
            String attrs = m.group(2) == null ? "" : m.group(2);
            String local = tag.substring(tag.indexOf(':') + 1).toLowerCase(Locale.ROOT);
            String replacement = VOID.contains(local) ? "<" + tag + attrs + ">" : "<" + tag + attrs + "></" + tag + ">";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Set<String> DRAWN = Set.of("path", "rect", "circle", "ellipse", "line", "polyline",
            "polygon", "text", "use");

    static void unwrapSvgPictures(Element body) {
        for (Element svg : body.select("svg")) {
            List<Element> images = svg.select("image");
            if (images.size() != 1 || svg.select("*").stream().anyMatch(e -> DRAWN.contains(e.normalName()))) {
                continue;
            }
            Element image = images.get(0);
            String href = image.hasAttr("xlink:href") ? image.attr("xlink:href") : image.attr("href");
            if (href.isBlank()) {
                continue;
            }
            Element img = new Element("img").attr("src", href).attr("alt", "").attr("style", "max-width:100%");
            String[] box = pictureBox(svg, image);
            if (box != null) {
                img.attr("width", box[0]).attr("height", box[1]);
            }
            svg.replaceWith(img);
        }
    }

    private static boolean coverPage(Document chapter, Element body) {
        Element meta = chapter.selectFirst("meta[name=calibre:cover]");
        boolean marked = meta != null && !meta.attr("content").equalsIgnoreCase("false");
        return body.select("img").size() == 1 && body.text().isBlank()
                && (marked || chapter.title().toLowerCase(Locale.ROOT).contains("cover"));
    }

    private static String[] pictureBox(Element svg, Element image) {
        String w = image.attr("width").replace("px", "").strip();
        String h = image.attr("height").replace("px", "").strip();
        if (!positive(w) || !positive(h)) {
            String[] view = svg.attr("viewBox").strip().split("[\\s,]+");
            if (view.length != 4 || !positive(view[2]) || !positive(view[3])) {
                return null;
            }
            w = view[2];
            h = view[3];
        }
        return new String[] {w, h};
    }

    private static boolean positive(String number) {
        try {
            double v = Double.parseDouble(number);
            return v > 0 && v < 100_000;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String scoped(String css, int chapter, int depth) throws IOException {
        StringBuilder out = new StringBuilder(css.length() + 64);
        String s = withoutComments(css);
        int i = 0;
        int selectors = 0;
        while (i < s.length()) {
            OfficeToPdf.stopIfInterrupted();
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = open;
            int level = 0;
            for (; close < s.length(); close++) {
                if ((close & 4095) == 0) {
                    OfficeToPdf.stopIfInterrupted();
                }
                char c = s.charAt(close);
                if (c == '{') {
                    level++;
                } else if (c == '}' && --level == 0) {
                    break;
                }
            }
            String head = s.substring(i, open);
            int semi = head.lastIndexOf(';');
            if (semi >= 0) {
                head = head.substring(semi + 1);
            }
            head = head.strip();
            String inner = s.substring(open + 1, Math.min(close, s.length()));
            i = Math.min(close, s.length()) + 1;
            String at = head.toLowerCase(Locale.ROOT);
            if (at.startsWith("@media") || at.startsWith("@supports")) {
                if (depth >= 8) {
                    throw new IOException("The eBook style sheet has more than eight nested conditions");
                }
                out.append(head).append('{').append(scoped(inner, chapter, depth + 1)).append("}\n");
            } else if (at.startsWith("@font-face")) {
                out.append(head).append('{').append(inner).append("}\n");
            } else if (!at.startsWith("@") && !head.isEmpty()) {
                boolean first = true;
                int from = 0;
                while (from <= head.length()) {
                    OfficeToPdf.stopIfInterrupted();
                    int comma = head.indexOf(',', from);
                    String selector = head.substring(from, comma < 0 ? head.length() : comma).strip();
                    if (!selector.isEmpty()) {
                        if (++selectors > 100_000) {
                            throw new IOException("The eBook style sheet has more than 100000 selectors");
                        }
                        if (!first) {
                            out.append(", ");
                        }
                        out.append(scopedSelector(selector, chapter));
                        first = false;
                    }
                    if (comma < 0) {
                        break;
                    }
                    from = comma + 1;
                }
                out.append('{').append(inner).append("}\n");
            }
            if (out.length() > MAX_BOOK_CHARS) {
                throw new IOException("The eBook style sheet exceeds " + (MAX_BOOK_CHARS >> 20) + " MB");
            }
        }
        return out.toString();
    }

    private static final Pattern ROOT_STEP = Pattern.compile("^(?:html\\s*>?\\s*)?body(?![A-Za-z0-9_-])|^html(?![A-Za-z0-9_-])");

    private static final Pattern ID_STEP = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");

    static String scopedSelector(String selector, int chapter) {
        String scope = "#epub-c" + chapter;
        Matcher m = ROOT_STEP.matcher(selector);
        String rest = m.find() ? selector.substring(m.end()) : " " + selector;
        return scope + ID_STEP.matcher(rest).replaceAll("#epub-c" + chapter + "-$1");
    }

    static String withoutComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            int open = css.indexOf("/*", i);
            if (open < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, open);
            int close = css.indexOf("*/", open + 2);
            if (close < 0) {
                break;
            }
            i = close + 2;
        }
        return out.toString();
    }

    private static void rewrite(Element body, String chapterPath, int chapter, Map<String, Integer> chapterOf) {
        for (Element e : body.select("[id]")) {
            e.attr("id", "epub-c" + chapter + "-" + e.id());
        }
        for (Element e : body.select("[name]")) {
            if (e.normalName().equals("a")) {
                e.attr("name", "epub-c" + chapter + "-" + e.attr("name"));
            }
        }
        for (Element img : body.select("img[src]")) {
            packageAbsolute(img, "src", chapterPath);
        }
        for (Element img : body.select("img[srcset]")) {
            img.removeAttr("srcset");
        }
        for (Element image : body.select("image")) {
            for (String attr : new String[] {"href", "xlink:href"}) {
                if (image.hasAttr(attr)) {
                    packageAbsolute(image, attr, chapterPath);
                }
            }
        }
        for (Element a : body.select("a[href]")) {
            String href = a.attr("href").strip();
            if (href.startsWith("#")) {
                a.attr("href", "#epub-c" + chapter + "-" + href.substring(1));
                continue;
            }
            if (href.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                continue;
            }
            int hash = href.indexOf('#');
            String target = resolve(chapterPath, hash < 0 ? href : href.substring(0, hash));
            Integer other = target == null ? null : chapterOf.get(target);
            if (other == null) {
                a.removeAttr("href");
            } else if (hash < 0 || hash == href.length() - 1) {
                a.attr("href", "#epub-c" + other);
            } else {
                a.attr("href", "#epub-c" + other + "-" + href.substring(hash + 1));
            }
        }
    }

    private static void packageAbsolute(Element e, String attr, String chapterPath) {
        String value = e.attr(attr).strip();
        if (value.regionMatches(true, 0, "data:", 0, 5)) {
            return;
        }
        String path = resolve(chapterPath, value);
        if (path == null) {
            e.removeAttr(attr);
        } else {
            e.attr(attr, "/" + path);
        }
    }

    static String resolve(String base, String reference) {
        return HtmlRefs.resolve(base, reference);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
