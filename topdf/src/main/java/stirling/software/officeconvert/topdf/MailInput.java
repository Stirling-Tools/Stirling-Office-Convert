package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.html.HtmlDocument;
import stirling.software.officeconvert.topdf.mail.EmlReader;
import stirling.software.officeconvert.topdf.mail.Mail;
import stirling.software.officeconvert.topdf.mail.MailFiles;
import stirling.software.officeconvert.topdf.mail.MailCharsets;
import stirling.software.officeconvert.topdf.mail.MailPage;
import stirling.software.officeconvert.topdf.mail.MailRtf;
import stirling.software.officeconvert.topdf.mail.Mime;
import stirling.software.officeconvert.topdf.mail.MimeAssets;
import stirling.software.officeconvert.topdf.mail.MsgReader;

final class MailInput {

    static final long MAX_BYTES = 192L << 20;

    enum Kind { EML, MSG, MHTML }

    private static final Set<String> MAIL_HEADERS = Set.of("from", "to", "cc", "subject", "date", "message-id",
            "received", "mime-version", "return-path", "delivered-to", "x-mailer", "content-type", "reply-to",
            "sender", "x-originating-ip", "in-reply-to", "references", "thread-topic", "x-ms-has-attach",
            "content-transfer-encoding", "snapshot-content-location", "x-mimeole", "user-agent", "list-id",
            "dkim-signature", "authentication-results", "x-received", "arc-seal", "importance", "x-priority");

    private MailInput() {}

    static Kind kind(Path source, Format requested) throws IOException {
        if (requested != null && requested != Format.HTML && requested != Format.DOCX) {
            return null;
        }
        String ext = extension(source);
        boolean named = ext.equals("eml") || ext.equals("msg") || ext.equals("mht") || ext.equals("mhtml");
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(16 << 10);
        }
        if (head.length >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11
                && (head[3] & 0xFF) == 0xE0) {
            if (requested == Format.DOCX && !ext.equals("msg")) {
                return null;
            }
            return outlook(source) ? Kind.MSG : null;
        }
        if (requested == Format.DOCX && !named) {
            return mhtml(head, true) ? Kind.MHTML : null;
        }
        if (ext.equals("mht") || ext.equals("mhtml")) {
            return html(head) && !mailHeaders(head) ? null : Kind.MHTML;
        }
        if (ext.equals("eml") || ext.equals("msg")) {
            return mailHeaders(head) || !html(head) ? (mhtml(head, false) ? Kind.MHTML : Kind.EML) : null;
        }
        if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml")) {
            return null;
        }
        if (!mailHeaders(head)) {
            return null;
        }
        return mhtml(head, false) ? Kind.MHTML : Kind.EML;
    }

    private static boolean outlook(Path source) {
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            return root.hasEntryCaseInsensitive("__substg1.0_0037001F")
                    || root.hasEntryCaseInsensitive("__substg1.0_0037001E")
                    || root.hasEntryCaseInsensitive("__nameid_version1.0")
                    || root.hasEntryCaseInsensitive("__properties_version1.0")
                    && root.hasEntryCaseInsensitive("__substg1.0_001A001F");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean html(byte[] head) {
        String s = new String(head, 0, Math.min(head.length, 512), StandardCharsets.ISO_8859_1).stripLeading();
        return s.startsWith("<");
    }

    static boolean mailHeaders(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n|\n|\r", 200);
        int known = 0;
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i < lines.length && lines[i].startsWith("From ")) {
            i++;
        }
        int headerLines = 0;
        for (; i < lines.length - 1; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break;
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (headerLines == 0) {
                    return false;
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            String name = line.substring(0, colon);
            for (int k = 0; k < name.length(); k++) {
                char c = name.charAt(k);
                if (c <= ' ' || c >= 127) {
                    return false;
                }
            }
            headerLines++;
            if (MAIL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                known++;
            }
        }
        return known >= 2;
    }

    private static boolean mhtml(byte[] head, boolean strict) {
        Mime.Part top = Mime.parse(head);
        String type = top.type();
        if (top.header("Snapshot-Content-Location") != null) {
            return true;
        }
        String from = top.header("From");
        if (from != null && from.contains("Saved by")) {
            return true;
        }
        if (!type.equals("multipart/related")) {
            return false;
        }
        String root = top.param("type");
        boolean htmlRoot = root == null || root.equalsIgnoreCase("text/html");
        if (strict) {
            return htmlRoot;
        }
        return htmlRoot && top.header("To") == null && top.header("Received") == null
                && top.header("Message-ID") == null && top.header("Cc") == null;
    }

    static String extension(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    static Long estimate(Path source, Format requested) throws IOException {
        Kind kind = kind(source, requested);
        if (kind == null) {
            return null;
        }
        long size = Files.size(source);
        return 4 * size + HtmlDocument.estimate(Math.min(size, HtmlInput.MAX_BYTES)) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        Kind kind = kind(source, requested);
        if (kind == null) {
            return null;
        }
        long size = Files.size(source);
        if (size > MAX_BYTES) {
            throw new IOException("The " + (kind == Kind.MHTML ? "web archive" : "e-mail") + " is larger than "
                    + (MAX_BYTES >> 20) + " MB");
        }
        MimeAssets assets = new MimeAssets();
        String html;
        Mail mail = null;
        List<MailFiles.File> files = List.of();
        Admission.Ticket ticket = Admission.jvm().enter(4 * size + Admission.BASE_BYTES);
        try {
            switch (kind) {
                case MSG -> mail = MsgReader.read(source, assets);
                case EML -> mail = EmlReader.read(Files.readAllBytes(source), assets);
                case MHTML -> {}
            }
            OfficeToPdf.stopIfInterrupted();
            Admission.checkpoint();
            if (mail != null) {
                MailPage.dropInline(mail);
                OfficeToPdf.MailAttachments limits = options.mailAttachments();
                if (limits != null) {
                    files = MailFiles.plan(mail, limits.maxEachBytes(), limits.maxTotalBytes());
                }
                if (mail.rtf() != null) {
                    byte[] rtf = MailRtf.withHeader(mail.rtf(), mail);
                    Path page = Files.createTempFile("office-to-pdf-", ".rtf");
                    try {
                        Files.write(page, rtf);
                        ticket.close();
                        ticket = null;
                        return attach(files, sink, out -> OfficeToPdf.render(page, Format.DOCX, out, options, renderer));
                    } finally {
                        OfficeToPdf.deleteQuietly(page);
                    }
                }
                html = MailPage.html(mail);
            } else {
                html = archive(Files.readAllBytes(source), assets);
            }
        } finally {
            if (ticket != null) {
                ticket.close();
            }
        }
        OfficeToPdf.stopIfInterrupted();
        String page = html;
        return attach(files, sink, out -> HtmlInput.renderPage(page, out, options, renderer, assets));
    }

    private interface Drawing {
        Result to(OutputStream out) throws IOException;
    }

    private static Result attach(List<MailFiles.File> files, OutputStream sink, Drawing drawing) throws IOException {
        if (files.isEmpty()) {
            return drawing.to(sink);
        }
        Path drawn = Files.createTempFile("office-to-pdf-", ".pdf");
        try {
            Result r;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(drawn), 1 << 16)) {
                r = drawing.to(out);
            }
            OfficeToPdf.stopIfInterrupted();
            MailFiles.embed(drawn, files, sink);
            return r;
        } finally {
            OfficeToPdf.deleteQuietly(drawn);
        }
    }

    static String archive(byte[] bytes, MimeAssets assets) throws IOException {
        Mime.Part top = Mime.parse(bytes);
        Mime.Part root = top;
        if (top.multipart()) {
            if (top.children().isEmpty()) {
                throw new UnsupportedFormatException("The web archive holds no page");
            }
            String start = top.param("start");
            root = null;
            if (start != null) {
                String id = start.strip().replaceAll("^<|>$", "");
                for (Mime.Part c : top.children()) {
                    if (id.equals(c.contentId())) {
                        root = c;
                        break;
                    }
                }
            }
            if (root == null) {
                for (Mime.Part c : top.children()) {
                    if (c.type().equals("text/html") || c.type().equals("application/xhtml+xml")) {
                        root = c;
                        break;
                    }
                }
            }
            if (root == null) {
                root = top.children().get(0);
            }
            for (Mime.Part c : top.children()) {
                if (c != root && !c.multipart()) {
                    assets.add(c.contentId(), c.location(), c.body());
                }
            }
        }
        String location = root.location();
        if (location == null) {
            location = top.header("Snapshot-Content-Location");
        }
        assets.root(location);
        String type = root.type();
        if (type.equals("text/html") || type.equals("application/xhtml+xml")) {
            String charset = root.charsetName();
            byte[] body = root.body();
            if (charset == null) {
                charset = MailCharsets.metaCharset(body);
            }
            Map<String, Mime.Part> pages = new HashMap<>();
            for (Mime.Part c : top.children()) {
                if (c != root && (c.type().equals("text/html") || c.type().equals("application/xhtml+xml"))) {
                    if (c.contentId() != null) {
                        pages.putIfAbsent("cid:" + c.contentId(), c);
                    }
                    if (c.location() != null) {
                        pages.putIfAbsent(c.location(), c);
                    }
                }
            }
            return MailPage.web(MailCharsets.decode(body, charset), src -> {
                String s = src.strip();
                Mime.Part p = pages.get(s);
                if (p == null && s.regionMatches(true, 0, "cid:", 0, 4)) {
                    p = pages.get("cid:" + s.substring(4).replaceAll("^<|>$", ""));
                }
                if (p == null) {
                    return null;
                }
                String cs = p.charsetName();
                return MailCharsets.decode(p.body(), cs != null ? cs : MailCharsets.metaCharset(p.body()));
            });
        }
        if (type.startsWith("text/")) {
            return MailPage.webText(root.text());
        }
        throw new UnsupportedFormatException("The web archive's page is " + type + ", not HTML");
    }
}
