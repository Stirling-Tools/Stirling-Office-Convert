package stirling.software.officeconvert.topdf.mail;

import java.nio.charset.Charset;
import java.util.List;

public final class EmlReader {

    static final int MAX_NESTED = 64;

    private final MimeAssets assets;
    private int nestedCount;

    private EmlReader(MimeAssets assets) {
        this.assets = assets;
    }

    public static Mail read(byte[] bytes, MimeAssets assets) {
        return new EmlReader(assets).mail(Mime.parse(bytes), 0);
    }

    private Mail mail(Mime.Part root, int depth) {
        Mail mail = new Mail();
        Charset fallback = headerCharset(root);
        mail.subject = clean(root.decodedHeader("Subject", fallback));
        mail.from = addresses(root.decodedHeader("From", fallback));
        mail.sender = addresses(root.decodedHeader("Sender", fallback));
        mail.to = addresses(root.decodedHeader("To", fallback));
        mail.cc = addresses(root.decodedHeader("Cc", fallback));
        mail.bcc = addresses(root.decodedHeader("Bcc", fallback));
        mail.replyTo = addresses(root.decodedHeader("Reply-To", fallback));
        mail.date = clean(root.decodedHeader("Date", fallback));
        walk(root, mail, depth);
        return mail;
    }

    private void walk(Mime.Part part, Mail mail, int depth) {
        String type = part.type();
        if (part.message() != null) {
            if (nestedCount++ < MAX_NESTED && depth < 8) {
                mail.nested.add(mail(part.message(), depth + 1));
            }
            return;
        }
        if (part.multipart()) {
            List<Mime.Part> children = part.children();
            if (children.isEmpty()) {
                if (part.encodedLength() > 0) {
                    mail.body.add(new Mail.Text(part.text()));
                }
                return;
            }
            switch (type) {
                case "multipart/alternative" -> walk(best(children), mail, depth);
                case "multipart/related" -> related(part, children, mail, depth);
                case "multipart/signed" -> {
                    walk(children.get(0), mail, depth);
                    for (int i = 1; i < children.size(); i++) {
                        attach(children.get(i), mail);
                    }
                }
                default -> {
                    for (Mime.Part c : children) {
                        walk(c, mail, depth);
                    }
                }
            }
            return;
        }
        String disposition = part.disposition();
        String name = part.fileName();
        boolean attachment = disposition.equals("attachment");
        if (type.startsWith("image/") && part.contentId() != null) {
            assets.add(part.contentId(), part.location(), part.body());
        }
        if (attachment || name != null && !type.startsWith("text/")) {
            attach(part, mail);
            if (!attachment && type.startsWith("image/") && part.contentId() == null) {
                String ref = "cid:" + inlineId(part);
                assets.add(ref.substring(4), null, part.body());
                mail.body.add(new Mail.Image(ref));
            }
            return;
        }
        if (name != null) {
            attach(part, mail);
        }
        if (type.equals("text/html")) {
            mail.body.add(new Mail.Html(part.text()));
        } else if (type.equals("text/plain") || type.equals("text/enriched") || type.equals("text/richtext")
                || type.equals("message/delivery-status") || type.equals("text/rfc822-headers")) {
            String text = part.text();
            if ("flowed".equalsIgnoreCase(part.param("format"))) {
                text = unflow(text, "yes".equalsIgnoreCase(part.param("delsp")));
            }
            if (type.equals("text/enriched") || type.equals("text/richtext")) {
                text = text.replaceAll("<[^<>\\n]{0,40}>", "");
            }
            mail.body.add(new Mail.Text(text));
        } else if (type.startsWith("text/")) {
            mail.body.add(new Mail.Text(part.text()));
        } else if (type.startsWith("image/")) {
            String ref = "cid:" + (part.contentId() != null ? part.contentId() : inlineId(part));
            if (part.contentId() == null) {
                assets.add(ref.substring(4), null, part.body());
            }
            if (!disposition.isEmpty() || part.contentId() == null) {
                mail.body.add(new Mail.Image(ref));
            }
        } else if (part.encodedLength() > 0) {
            attach(part, mail);
        }
    }

    private String inlineId(Mime.Part part) {
        return assets.inlineId();
    }

    private void related(Mime.Part part, List<Mime.Part> children, Mail mail, int depth) {
        String start = part.param("start");
        Mime.Part root = null;
        if (start != null) {
            String id = start.strip();
            if (id.startsWith("<") && id.endsWith(">") && id.length() > 1) {
                id = id.substring(1, id.length() - 1);
            }
            for (Mime.Part c : children) {
                if (id.equals(c.contentId())) {
                    root = c;
                    break;
                }
            }
        }
        if (root == null) {
            root = children.get(0);
        }
        for (Mime.Part c : children) {
            if (c != root && !c.multipart() && c.message() == null) {
                assets.add(c.contentId(), c.location(), c.body());
            }
        }
        walk(root, mail, depth);
        for (Mime.Part c : children) {
            if (c == root) {
                continue;
            }
            if (c.multipart() || c.message() != null) {
                walk(c, mail, depth);
            } else if (!c.type().startsWith("text/") || c.fileName() != null) {
                attach(c, mail);
            }
        }
    }

    private void attach(Mime.Part part, Mail mail) {
        if (mail.attachments.size() >= 1_000) {
            return;
        }
        String name = part.fileName();
        if (name == null) {
            String type = part.type();
            name = type.equals("application/pkcs7-signature") || type.equals("application/x-pkcs7-signature")
                    ? "smime.p7s" : "";
        }
        byte[] data = part.body();
        mail.attachments.add(new Mail.Attachment(name, part.type(), data.length, data, part.contentId(),
                part.location()));
    }

    private static Mime.Part best(List<Mime.Part> children) {
        Mime.Part html = null;
        Mime.Part text = null;
        for (Mime.Part c : children) {
            String t = c.type();
            if (t.equals("text/html") || c.multipart() && containsHtml(c, 0)) {
                html = c;
            } else if (t.startsWith("text/") || c.multipart()) {
                text = c;
            }
        }
        if (html != null) {
            return html;
        }
        return text != null ? text : children.get(children.size() - 1);
    }

    private static boolean containsHtml(Mime.Part p, int depth) {
        if (p.type().equals("text/html")) {
            return true;
        }
        if (depth > 8) {
            return false;
        }
        for (Mime.Part c : p.children()) {
            if (containsHtml(c, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static Charset headerCharset(Mime.Part root) {
        Mime.Part p = root;
        for (int i = 0; i < 16 && p != null; i++) {
            if (!p.multipart()) {
                Charset c = MailCharsets.forName(p.charsetName());
                return c == null ? MailCharsets.WESTERN : c;
            }
            p = p.children().isEmpty() ? null : p.children().get(0);
        }
        return MailCharsets.WESTERN;
    }

    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").strip();
        return t.isEmpty() ? null : t;
    }

    private static String addresses(String header) {
        String h = clean(header);
        if (h == null) {
            return null;
        }
        List<String> list = MimeWords.addresses(h);
        return list.isEmpty() ? null : String.join(", ", list);
    }

    static String unflow(String text, boolean delsp) {
        String[] lines = text.split("\r\n|\n|\r", -1);
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder para = new StringBuilder();
        int quote = -1;
        for (String raw : lines) {
            int q = 0;
            while (q < raw.length() && raw.charAt(q) == '>') {
                q++;
            }
            String line = raw.substring(q);
            if (line.startsWith(" ")) {
                line = line.substring(1);
            }
            boolean soft = line.endsWith(" ") && !line.equals("-- ");
            if (para.length() > 0 && q != quote) {
                out.append(">".repeat(quote)).append(quote > 0 ? " " : "").append(para).append('\n');
                para.setLength(0);
            }
            quote = q;
            para.append(soft && delsp ? line.substring(0, line.length() - 1) : line);
            if (!soft) {
                out.append(">".repeat(q)).append(q > 0 ? " " : "").append(para).append('\n');
                para.setLength(0);
            }
        }
        if (para.length() > 0) {
            out.append(">".repeat(Math.max(0, quote))).append(quote > 0 ? " " : "").append(para).append('\n');
        }
        return out.toString();
    }
}
