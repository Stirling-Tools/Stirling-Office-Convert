package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.poi.hmef.CompressedRTF;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

public final class MsgReader {

    static final int MAX_RTF_BYTES = 64 << 20;

    private final MimeAssets assets;
    private int nestedCount;

    private MsgReader(MimeAssets assets) {
        this.assets = assets;
    }

    public static Mail read(Path source, MimeAssets assets) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true)) {
            return new MsgReader(assets).mail(new MAPIMessage(fs), 0);
        } catch (RuntimeException e) {
            throw new IOException("The Outlook message could not be read: " + e.getMessage(), e);
        }
    }

    private Mail mail(MAPIMessage msg, int depth) {
        msg.setReturnNullOnMissingChunk(true);
        try {
            msg.guess7BitEncoding();
        } catch (RuntimeException ignored) {
        }
        Mail mail = new Mail();
        Chunks main = msg.getMainChunks();
        mail.subject = EmlReader.clean(string(msg, main.getSubjectChunk()));
        mail.from = sender(msg, main);
        List<String> to = new ArrayList<>();
        List<String> cc = new ArrayList<>();
        List<String> bcc = new ArrayList<>();
        RecipientChunks[] recipients = safeRecipients(msg);
        for (RecipientChunks r : recipients) {
            String name = r.getRecipientName();
            String address = recipientAddress(r);
            String shown = name == null || name.isBlank() ? address
                    : address == null || address.isBlank() || name.equalsIgnoreCase(address) ? name
                    : name + " <" + address + ">";
            if (shown == null || shown.isBlank()) {
                continue;
            }
            int type = recipientType(r);
            (type == 2 ? cc : type == 3 ? bcc : to).add(shown.strip());
        }
        mail.to = to.isEmpty() ? EmlReader.clean(string(msg, main.getDisplayToChunk())) : String.join(", ", to);
        mail.cc = cc.isEmpty() ? EmlReader.clean(string(msg, main.getDisplayCCChunk())) : String.join(", ", cc);
        mail.bcc = bcc.isEmpty() ? EmlReader.clean(string(msg, main.getDisplayBCCChunk())) : String.join(", ", bcc);
        mail.date = date(msg);
        attachments(msg, mail, depth);
        if (!signed(mail)) {
            body(msg, main, mail);
        }
        return mail;
    }

    private boolean signed(Mail mail) {
        for (Mail.Attachment a : mail.attachments) {
            String type = a.type() == null ? "" : a.type().toLowerCase(Locale.ROOT);
            String name = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
            if ((type.startsWith("multipart/signed") || name.equals("smime.p7m")) && mime(a.data())) {
                Mail inner = EmlReader.read(a.data(), assets);
                mail.attachments.remove(a);
                mail.body.addAll(inner.body);
                mail.attachments.addAll(inner.attachments);
                mail.nested.addAll(inner.nested);
                return !mail.body.isEmpty();
            }
        }
        return false;
    }

    private static boolean mime(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.ISO_8859_1)
                .stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("content-type:") || head.startsWith("mime-version:");
    }

    private static RecipientChunks[] safeRecipients(MAPIMessage msg) {
        try {
            RecipientChunks[] r = msg.getRecipientDetailsChunks();
            return r == null ? new RecipientChunks[0] : r;
        } catch (RuntimeException e) {
            return new RecipientChunks[0];
        }
    }

    private static String recipientAddress(RecipientChunks r) {
        StringChunk smtp = r.getRecipientSMTPChunk();
        if (smtp != null && smtp.getValue() != null && smtp.getValue().contains("@")) {
            return smtp.getValue().strip();
        }
        String a = r.getRecipientEmailAddress();
        if (a == null || !a.contains("@")) {
            return null;
        }
        return a.strip();
    }

    private static int recipientType(RecipientChunks r) {
        try {
            for (var e : r.getProperties().entrySet()) {
                if (e.getKey().id == 0x0C15 && !e.getValue().isEmpty()) {
                    Object v = e.getValue().get(0).getValue();
                    if (v instanceof Number n) {
                        return n.intValue();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return 1;
    }

    private static String sender(MAPIMessage msg, Chunks main) {
        String name = EmlReader.clean(string(msg, main.getDisplayFromChunk()));
        String address = null;
        String smtp = stringProperty(main, 0x5D01);
        if (smtp != null && smtp.contains("@")) {
            address = smtp;
        }
        if (address == null) {
            String e = EmlReader.clean(string(msg, main.getEmailFromChunk()));
            if (e != null && e.contains("@")) {
                address = e;
            }
        }
        if (address == null) {
            String headers = string(msg, main.getMessageHeaders());
            if (headers != null) {
                Mime.Part h = Mime.parse(headers.getBytes(MailCharsets.WESTERN));
                String from = h.decodedHeader("From", MailCharsets.WESTERN);
                if (from != null) {
                    List<String> list = MimeWords.addresses(from);
                    if (!list.isEmpty()) {
                        return list.get(0);
                    }
                }
            }
        }
        if (name == null) {
            return address;
        }
        if (address == null || name.equalsIgnoreCase(address) || name.contains(address)) {
            return name;
        }
        return name + " <" + address + ">";
    }

    private static String stringProperty(Chunks main, int id) {
        try {
            for (var e : main.getAll().entrySet()) {
                if (e.getKey().id == id) {
                    for (Chunk c : e.getValue()) {
                        if (c instanceof StringChunk s && s.getValue() != null) {
                            return s.getValue().strip();
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String string(MAPIMessage msg, StringChunk chunk) {
        if (chunk == null) {
            return null;
        }
        try {
            String v = chunk.getValue();
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String date(MAPIMessage msg) {
        Calendar c = systemTime(msg.getMainChunks(), 0x0039);
        if (c == null) {
            c = systemTime(msg.getMainChunks(), 0x0E06);
        }
        if (c == null) {
            String headers = string(msg, msg.getMainChunks().getMessageHeaders());
            if (headers != null) {
                Mime.Part h = Mime.parse(headers.getBytes(MailCharsets.WESTERN));
                String d = EmlReader.clean(h.decodedHeader("Date", MailCharsets.WESTERN));
                if (d != null) {
                    return d;
                }
            }
            try {
                c = msg.getMessageDate();
            } catch (Exception ignored) {
            }
        }
        if (c == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    private static Calendar systemTime(Chunks main, int id) {
        try {
            for (var e : main.getProperties().entrySet()) {
                if (e.getKey().id == id && !e.getValue().isEmpty() && e.getValue().get(0).getValue() instanceof Calendar c) {
                    return c;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private void attachments(MAPIMessage msg, Mail mail, int depth) {
        AttachmentChunks[] files;
        try {
            files = msg.getAttachmentFiles();
        } catch (RuntimeException e) {
            return;
        }
        if (files == null) {
            return;
        }
        for (AttachmentChunks a : files) {
            if (mail.attachments.size() >= 1_000) {
                break;
            }
            try {
                if (a.isEmbeddedMessage()) {
                    if (nestedCount++ < EmlReader.MAX_NESTED && depth < 8) {
                        MAPIMessage inner = a.getEmbeddedMessage();
                        if (inner != null) {
                            mail.nested.add(mail(inner, depth + 1));
                        }
                    }
                    continue;
                }
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            String name = value(a.getAttachLongFileName());
            if (name == null) {
                name = value(a.getAttachFileName());
            }
            if (name == null) {
                name = value(a.getAttachDisplayName());
            }
            String type = value(a.getAttachMimeTag());
            String cid = value(a.getAttachContentId());
            String location = value(a.getAttachContentLocation());
            ByteChunk data = a.getAttachData();
            byte[] bytes = data == null ? null : data.getValue();
            if (bytes == null) {
                bytes = new byte[0];
            }
            if (type == null || type.isBlank()) {
                type = guessType(name);
            }
            if (cid != null && cid.startsWith("<") && cid.endsWith(">") && cid.length() > 1) {
                cid = cid.substring(1, cid.length() - 1);
            }
            assets.add(cid, location, bytes);
            if (name != null) {
                assets.add(null, name, bytes);
            }
            mail.attachments.add(new Mail.Attachment(name == null ? "" : name, type, bytes.length, bytes, cid,
                    location == null ? name : location));
        }
    }

    private static String guessType(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "htm", "html" -> "text/html";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "zip" -> "application/zip";
            case "msg" -> "application/vnd.ms-outlook";
            default -> "application/octet-stream";
        };
    }

    private static String value(StringChunk c) {
        if (c == null) {
            return null;
        }
        try {
            String v = c.getValue();
            return v == null || v.isBlank() ? null : v.strip();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void body(MAPIMessage msg, Chunks main, Mail mail) {
        String html = html(msg, main);
        if (html != null) {
            mail.body.add(new Mail.Html(html));
            return;
        }
        ByteChunk rtfChunk = main.getRtfBodyChunk();
        byte[] rtf = rtfChunk == null ? null : rtf(rtfChunk.getValue());
        if (rtf != null) {
            RtfBody.Body b = RtfBody.read(rtf);
            if (b.kind() == RtfBody.Kind.HTML && b.content() != null && !b.content().isBlank()) {
                mail.body.add(new Mail.Html(b.content()));
                return;
            }
            String text = string(msg, main.getTextBodyChunk());
            if (b.kind() == RtfBody.Kind.TEXT && b.content() != null && !b.content().isBlank()) {
                mail.body.add(new Mail.Text(text != null ? text : b.content()));
                return;
            }
            if (b.kind() == RtfBody.Kind.RTF) {
                mail.rtf = rtf;
                return;
            }
        }
        String text = string(msg, main.getTextBodyChunk());
        if (text != null) {
            mail.body.add(new Mail.Text(text));
        }
    }

    private static String html(MAPIMessage msg, Chunks main) {
        ByteChunk binary = main.getHtmlBodyChunkBinary();
        if (binary != null && binary.getValue() != null && binary.getValue().length > 0) {
            byte[] b = binary.getValue();
            Charset cs = codePage(main);
            String declared = MailCharsets.metaCharset(b);
            String name = declared != null ? declared : cs == null ? null : cs.name();
            return MailCharsets.decode(b, name);
        }
        String s = string(msg, main.getHtmlBodyChunkString());
        return s == null || s.isBlank() ? null : s;
    }

    private static Charset codePage(Chunks main) {
        try {
            for (var e : main.getProperties().entrySet()) {
                MAPIProperty p = e.getKey();
                if ((p.id == 0x3FDE || p.id == 0x3FFD) && !e.getValue().isEmpty()) {
                    PropertyValue v = e.getValue().get(0);
                    if (v.getValue() instanceof Number n) {
                        int page = n.intValue();
                        return page == 65001 ? StandardCharsets.UTF_8
                                : MailCharsets.forName(page == 936 ? "gbk" : page == 950 ? "big5"
                                        : page == 949 ? "ks_c_5601-1987" : page == 932 ? "shift_jis"
                                        : page == 20127 ? "us-ascii" : page == 28591 ? "iso-8859-1"
                                        : page == 50220 ? "iso-2022-jp" : page == 51932 ? "euc-jp"
                                        : page == 20866 ? "koi8-r" : page >= 28592 && page <= 28605
                                        ? "iso-8859-" + (page - 28590) : "windows-" + page);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    static byte[] rtf(byte[] compressed) {
        if (compressed == null || compressed.length < 16) {
            return null;
        }
        try {
            CompressedRTF c = new CompressedRTF();
            ByteArrayOutputStream out = new ByteArrayOutputStream() {
                @Override
                public synchronized void write(int b) {
                    room(1);
                    super.write(b);
                }

                @Override
                public synchronized void write(byte[] b, int off, int len) {
                    room(len);
                    super.write(b, off, len);
                }

                private void room(int n) {
                    if (size() + (long) n > MAX_RTF_BYTES) {
                        throw new IllegalStateException("The message's RTF body is too large");
                    }
                }
            };
            c.decompress(new ByteArrayInputStream(compressed), out);
            byte[] b = out.toByteArray();
            int i = 0;
            while (i < b.length && i < 64 && Character.isWhitespace(b[i])) {
                i++;
            }
            return b.length - i >= 5 && b[i] == '{' && b[i + 1] == '\\' && b[i + 2] == 'r' && b[i + 3] == 't'
                    && b[i + 4] == 'f' ? b : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
