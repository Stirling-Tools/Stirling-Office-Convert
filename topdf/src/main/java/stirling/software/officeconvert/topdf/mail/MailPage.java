package stirling.software.officeconvert.topdf.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

public final class MailPage {

    static final String FONT = "font-family:Arial,Helvetica,sans-serif;";

    static final String BOX = "display:block;float:none;position:static;width:auto;max-width:none;height:auto;"
            + "border:0;background:#ffffff;color:#1f1f1f;" + FONT + "font-size:13px;font-weight:normal;"
            + "font-style:normal;line-height:1.45;text-align:left;text-transform:none;letter-spacing:normal;";

    private MailPage() {}

    static final int MAX_FRAMES = 32;

    static final int MAX_HTML_CHARS = 64 << 20;

    static final int MAX_FRAME_CHARS = 16 << 20;

    public static String web(String html, Function<String, String> frames) throws IOException {
        require(html.length());
        Document doc = Jsoup.parse(html);
        shadowRoots(doc.body());
        int[] budget = {MAX_FRAMES, MAX_FRAME_CHARS};
        inlineFrames(doc.body(), frames, budget, 0);
        tidy(doc);
        return doc.outerHtml();
    }

    static void require(long chars) throws IOException {
        if (chars > MAX_HTML_CHARS) {
            throw new IOException("The page is larger than " + (MAX_HTML_CHARS >> 20) + " million characters");
        }
    }

    static final int MAX_SHADOW_ROOTS = 2_000;

    private static void shadowRoots(Element root) {
        List<Element> templates = new ArrayList<>();
        Set<Element> open = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Element> drop = new ArrayList<>();
        List<Element> slots = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (!(node instanceof Element e)) {
                    return;
                }
                if (e.normalName().equals("template") && (e.hasAttr("shadowmode") || e.hasAttr("shadowrootmode"))
                        && templates.size() < MAX_SHADOW_ROOTS) {
                    templates.add(e);
                    open.add(e);
                } else if (!open.isEmpty()) {
                    switch (e.normalName()) {
                        case "style", "link", "script" -> drop.add(e);
                        case "slot" -> slots.add(e);
                        default -> {}
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e) {
                    open.remove(e);
                }
            }
        }, root);
        for (Element e : drop) {
            e.remove();
        }
        for (Element e : slots) {
            e.unwrap();
        }
        for (int i = templates.size() - 1; i >= 0; i--) {
            templates.get(i).unwrap();
        }
    }

    private static void inlineFrames(Element root, Function<String, String> frames, int[] budget, int depth) {
        for (Element frame : root.select("iframe[src], frame[src]")) {
            if (budget[0] <= 0 || depth >= 3) {
                return;
            }
            String html = frames.apply(frame.attr("src"));
            if (html == null || html.length() > budget[1]) {
                continue;
            }
            budget[0]--;
            budget[1] -= html.length();
            Document inner = Jsoup.parse(html);
            inlineFrames(inner.body(), frames, budget, depth + 1);
            String width = frame.attr("width").strip();
            Element box = new Element("div").attr("style", "display:block;clear:both;margin:4px 0;"
                    + "border:1px solid #d0d0d0;" + (width.matches("\\d{1,4}") ? "max-width:" + width + "px;" : ""));
            for (Node n : new ArrayList<>(inner.body().childNodes())) {
                n.remove();
                box.appendChild(n);
            }
            box.select("script, style, link").remove();
            frame.replaceWith(box);
        }
    }

    public static String webText(String text) {
        Document doc = Jsoup.parse("<!DOCTYPE html><html><head></head><body></body></html>");
        doc.body().appendChild(pre(text));
        tidy(doc);
        return doc.outerHtml();
    }

    public static void dropInline(Mail mail) {
        Set<String> ids = new HashSet<>();
        Set<String> values = new HashSet<>();
        for (Mail.Segment s : mail.body) {
            if (s instanceof Mail.Html h) {
                references(h.html(), ids, values);
            } else if (s instanceof Mail.Image i) {
                references("\"" + i.reference() + "\"", ids, values);
            }
        }
        mail.attachments.removeIf(a -> referenced(a, ids, values));
        for (Mail n : mail.nested) {
            dropInline(n);
        }
    }

    static void references(String html, Set<String> ids, Set<String> values) {
        int n = html.length();
        for (int i = 0; i < n; i++) {
            char c = html.charAt(i);
            if ((c == 'c' || c == 'C') && html.regionMatches(true, i, "cid:", 0, 4)) {
                int e = i + 4;
                while (e < n && e - i < 1_000 && " \t\r\n\"'()<>".indexOf(html.charAt(e)) < 0) {
                    e++;
                }
                if (ids.size() < 100_000) {
                    ids.add(html.substring(i + 4, e).toLowerCase(Locale.ROOT));
                }
                i = e - 1;
            } else if ((c == '"' || c == '\'') && i > 0 && html.charAt(i - 1) == '=') {
                int e = html.indexOf(c, i + 1);
                if (e > i && e - i < 2_048 && values.size() < 100_000) {
                    String v = html.substring(i + 1, e).toLowerCase(Locale.ROOT).strip();
                    if (v.startsWith("cid:")) {
                        ids.add(v.substring(4));
                    }
                    values.add(v);
                    values.add(v.substring(v.lastIndexOf('/') + 1));
                    i = e;
                }
            }
        }
    }

    private static boolean referenced(Mail.Attachment a, Set<String> ids, Set<String> values) {
        String id = a.contentId() == null ? null : a.contentId().toLowerCase(Locale.ROOT);
        if (id != null && !id.isEmpty() && (ids.contains(id) || ids.contains(MimeAssets.percentDecoded(id))
                || ids.contains("<" + id + ">"))) {
            return true;
        }
        String location = a.location() == null ? null : a.location().toLowerCase(Locale.ROOT);
        String type = a.type() == null ? "" : a.type().toLowerCase(Locale.ROOT);
        return location != null && location.length() > 3 && (type.startsWith("image/") || type.isEmpty()
                || type.equals("application/octet-stream")) && values.contains(location);
    }

    public static String html(Mail mail) throws IOException {
        require(chars(mail, 0));
        Document doc = null;
        for (Mail.Segment s : mail.body) {
            if (s instanceof Mail.Html h) {
                doc = Jsoup.parse(h.html());
                break;
            }
        }
        boolean own = doc == null;
        if (own) {
            doc = Jsoup.parse("<!DOCTYPE html><html><head></head><body style=\"margin:8px\"></body></html>");
        }
        Element body = doc.body();
        List<Node> first = new ArrayList<>(body.childNodes());
        for (Node n : first) {
            n.remove();
        }
        boolean usedFirst = own;
        for (Mail.Segment s : mail.body) {
            if (s instanceof Mail.Html h) {
                if (!usedFirst) {
                    usedFirst = true;
                    for (Node n : first) {
                        body.appendChild(n);
                    }
                } else {
                    appendDocument(doc, body, Jsoup.parse(h.html()));
                }
            } else if (s instanceof Mail.Text t) {
                body.appendChild(pre(t.text()));
            } else if (s instanceof Mail.Image i) {
                body.appendElement("img").attr("src", i.reference()).attr("alt", "")
                        .attr("style", "display:block;max-width:100%;height:auto;margin:8px 0");
            }
        }
        Element target = body;
        Element section = body.firstElementChild();
        if (section != null && section.normalName().equals("div") && section.className().startsWith("WordSection")) {
            target = section;
        }
        target.prependChild(header(mail, false));
        for (Mail nested : mail.nested) {
            target.appendChild(nested(doc, nested, 1));
        }
        tidy(doc);
        return doc.outerHtml();
    }

    private static long chars(Mail mail, int depth) {
        long n = 0;
        for (Mail.Segment s : mail.body) {
            n += s instanceof Mail.Html h ? h.html().length() : s instanceof Mail.Text t ? t.text().length() : 0;
        }
        if (depth < 8) {
            for (Mail m : mail.nested) {
                n += chars(m, depth + 1);
            }
        }
        return n;
    }

    private static Element nested(Document doc, Mail mail, int depth) {
        Element box = new Element("div").attr("style", "display:block;clear:both;margin:24px 0 0 0;padding:12px 0 0 0;"
                + "border-top:2px solid #9a9a9a;background:transparent;");
        box.appendChild(header(mail, true));
        Element content = box.appendElement("div").attr("style", "display:block;clear:both");
        for (Mail.Segment s : mail.body) {
            if (s instanceof Mail.Html h) {
                appendDocument(doc, content, Jsoup.parse(h.html()));
            } else if (s instanceof Mail.Text t) {
                content.appendChild(pre(t.text()));
            } else if (s instanceof Mail.Image i) {
                content.appendElement("img").attr("src", i.reference()).attr("alt", "")
                        .attr("style", "display:block;max-width:100%;height:auto;margin:8px 0");
            }
        }
        if (depth < 8) {
            for (Mail n : mail.nested) {
                box.appendChild(nested(doc, n, depth + 1));
            }
        }
        return box;
    }

    private static void appendDocument(Document doc, Element target, Document part) {
        for (Element style : part.head().select("style, link[rel~=(?i)stylesheet]")) {
            doc.head().appendChild(style.clone());
        }
        Element wrap = target.appendElement("div").attr("style", "display:block;clear:both");
        String bodyStyle = part.body().attr("style");
        String bg = part.body().attr("bgcolor");
        if (!bodyStyle.isBlank() || !bg.isBlank()) {
            wrap.attr("style", (bg.isBlank() ? "" : "background-color:" + bg + ";") + bodyStyle + ";display:block;clear:both");
        }
        for (Node n : new ArrayList<>(part.body().childNodes())) {
            n.remove();
            wrap.appendChild(n);
        }
    }

    static Element pre(String text) {
        Element pre = new Element("pre").attr("style", "white-space:pre-wrap;overflow-wrap:anywhere;word-wrap:break-word;"
                + "font-family:'Courier New',Courier,monospace;font-size:13px;line-height:1.35;margin:0 0 8px 0;"
                + "color:#1f1f1f;background:transparent;text-align:left");
        pre.text(text.replace("\r\n", "\n").replace('\r', '\n'));
        return pre;
    }

    static String shownSender(Mail mail) {
        return mail.sender != null && mail.from != null && !mail.from.toLowerCase(Locale.ROOT).contains(
                address(mail.sender).toLowerCase(Locale.ROOT)) ? mail.sender : null;
    }

    static String shownReplyTo(Mail mail) {
        return mail.replyTo != null && (mail.from == null || !mail.from.toLowerCase(Locale.ROOT).contains(
                address(mail.replyTo).toLowerCase(Locale.ROOT))) ? mail.replyTo : null;
    }

    static Element header(Mail mail, boolean nested) {
        Element box = new Element("div").attr("style", BOX + "margin:0 0 16px 0;padding:0 0 10px 0;"
                + "border-bottom:1px solid #c8c8c8;clear:both;");
        if (nested) {
            box.appendElement("div").attr("style", BOX + "font-size:11px;color:#5f5f5f;margin:0 0 4px 0;")
                    .text("Attached message");
        }
        if (mail.kind != null) {
            box.appendElement("div").attr("style", BOX + "font-size:11px;color:#5f5f5f;margin:0 0 4px 0;")
                    .text(mail.kind);
        }
        String subject = mail.subject == null ? "(no subject)" : mail.subject;
        box.appendElement("div").attr("style", BOX + "font-size:" + (nested ? "15px" : "18px")
                + ";font-weight:bold;line-height:1.3;margin:0 0 8px 0;").text(subject);
        row(box, mail.organizer ? "Organizer" : "From", mail.from);
        row(box, "Sender", shownSender(mail));
        row(box, "To", mail.to);
        row(box, "Cc", mail.cc);
        row(box, "Bcc", mail.bcc);
        row(box, "Reply-To", shownReplyTo(mail));
        row(box, "Date", mail.date);
        for (String[] f : mail.fields) {
            row(box, f[0], f[1]);
        }
        List<String> files = new ArrayList<>();
        for (Mail.Attachment a : mail.attachments) {
            files.add(describe(mail, a));
        }
        if (!files.isEmpty()) {
            Element r = row(box, files.size() == 1 ? "Attachment" : "Attachments (" + files.size() + ")", null);
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) {
                    r.appendText("; ");
                }
                r.appendText(files.get(i));
            }
        }
        return box;
    }

    private static String address(String list) {
        int lt = list.indexOf('<');
        int gt = list.indexOf('>', lt + 1);
        return lt >= 0 && gt > lt ? list.substring(lt + 1, gt) : list;
    }

    private static Element row(Element box, String label, String value) {
        if (value == null && !label.startsWith("Attachment")) {
            return null;
        }
        Element row = box.appendElement("div").attr("style", BOX + "margin:0;padding:1px 0;");
        row.appendElement("span").attr("style", "font-weight:bold;color:#5f5f5f;" + FONT).text(label + ":");
        row.appendText(" ");
        if (value != null) {
            row.appendText(value);
        }
        return row;
    }

    static String describe(Mail mail, Mail.Attachment a) {
        String name = a.name() == null || a.name().isBlank() ? "(unnamed)" : a.name();
        String type = a.type() == null || a.type().isBlank() ? "" : a.type() + ", ";
        String note = mail.notes.get(a);
        return name + " (" + type + size(a.size()) + (note == null ? "" : ", " + note) + ")";
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static void tidy(Document doc) {
        doc.select("meta[http-equiv~=(?i)content-type], meta[charset], script, noscript, base").remove();
        doc.head().prependElement("meta").attr("charset", "utf-8");
        doc.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8).escapeMode(Entities.EscapeMode.base);
    }
}
