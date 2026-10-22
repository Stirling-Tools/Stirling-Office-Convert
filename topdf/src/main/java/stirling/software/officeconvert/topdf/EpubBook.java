package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
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

import stirling.software.officeconvert.topdf.html.HtmlRefs;

final class EpubBook {

    static final int MAX_CHAPTERS = 5_000;

    static final long MAX_BOOK_CHARS = 48L << 20;

    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "source", "track", "wbr", "param", "keygen");

    private static final Pattern SELF_CLOSED = Pattern.compile("<([A-Za-z][A-Za-z0-9:_-]*)(\\s[^<>]*?)?/>");

    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*>");

    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);

    private EpubBook() {}

    record Book(String html, String title, String author) {}

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
        Document opf = Jsoup.parse(new String(opfBytes, StandardCharsets.UTF_8), "", Parser.xmlParser());
        Map<String, Element> manifest = new HashMap<>();
        for (Element item : opf.select("manifest > item, opf|manifest > opf|item")) {
            manifest.putIfAbsent(item.attr("id"), item);
        }
        List<String> chapters = new ArrayList<>();
        for (Element ref : opf.select("spine > itemref, opf|spine > opf|itemref")) {
            Element item = manifest.get(ref.attr("idref"));
            if (item == null || chapters.size() >= MAX_CHAPTERS) {
                continue;
            }
            String path = resolve(opfPath, item.attr("href"));
            if (path != null && !chapters.contains(path)) {
                chapters.add(path);
            }
        }
        if (chapters.isEmpty()) {
            throw new IOException("The EPUB's reading order (spine) lists no documents");
        }
        String title = text(opf, "dc|title, title");
        String author = text(opf, "dc|creator, creator");
        String cover = cover(opf, manifest, opfPath);

        Map<String, Integer> chapterOf = new HashMap<>();
        for (int i = 0; i < chapters.size(); i++) {
            chapterOf.put(chapters.get(i), i);
        }
        Set<String> sheets = new LinkedHashSet<>();
        StringBuilder styles = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean coverShown = false;
        for (int i = 0; i < chapters.size(); i++) {
            String path = chapters.get(i);
            byte[] bytes = pkg.read(path);
            if (bytes == null) {
                continue;
            }
            Document doc = Jsoup.parse(xhtmlAsHtml(new String(bytes, StandardCharsets.UTF_8)), "");
            for (Element link : doc.select("link[rel~=(?i)stylesheet][href]")) {
                String sheet = resolve(path, link.attr("href"));
                if (sheet != null) {
                    sheets.add(sheet);
                }
            }
            for (Element style : doc.select("style")) {
                styles.append(scoped(style.data(), "#epub-c" + i, 0)).append('\n');
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
            String style = (i == 0 ? "" : "page-break-before:always;") + b.attr("style");
            body.append("<section id=\"epub-c").append(i).append("\"");
            if (!b.attr("class").isBlank()) {
                body.append(" class=\"").append(escape(b.attr("class"))).append('"');
            }
            if (!style.isBlank()) {
                body.append(" style=\"").append(escape(style)).append('"');
            }
            body.append('>').append(b.html()).append("</section>\n");
            if (body.length() > MAX_BOOK_CHARS) {
                break;
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
        html.append("</head><body>");
        if (cover != null && !coverShown) {
            html.append("<div style=\"page-break-after:always;text-align:center\"><img src=\"/").append(escape(cover))
                    .append("\" alt=\"\" style=\"max-width:100%\"></div>");
        }
        html.append(body).append("</body></html>");
        return new Book(html.toString(), title, author);
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

    static String scoped(String css, String scope, int depth) {
        StringBuilder out = new StringBuilder(css.length() + 64);
        String s = css.replaceAll("(?s)/\\*.*?\\*/", "");
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            int close = open;
            int level = 0;
            for (; close < s.length(); close++) {
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
                if (depth < 8) {
                    out.append(head).append('{').append(scoped(inner, scope, depth + 1)).append("}\n");
                }
            } else if (at.startsWith("@font-face")) {
                out.append(head).append('{').append(inner).append("}\n");
            } else if (!at.startsWith("@") && !head.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (String sel : head.split(",")) {
                    String t = sel.strip();
                    if (!t.isEmpty()) {
                        parts.add(scopedSelector(t, scope));
                    }
                }
                out.append(String.join(", ", parts)).append('{').append(inner).append("}\n");
            }
        }
        return out.toString();
    }

    private static final Pattern ROOT_STEP = Pattern.compile("^(?:html\\s*>?\\s*)?body(?![A-Za-z0-9_-])|^html(?![A-Za-z0-9_-])");

    static String scopedSelector(String selector, String scope) {
        Matcher m = ROOT_STEP.matcher(selector);
        if (m.find()) {
            return scope + selector.substring(m.end());
        }
        return scope + " " + selector;
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
