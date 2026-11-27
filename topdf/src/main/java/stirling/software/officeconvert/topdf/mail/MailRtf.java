package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class MailRtf {

    private static final Set<String> HEADER = Set.of("fonttbl", "colortbl", "stylesheet", "info", "filetbl",
            "listtable", "listoverridetable", "revtbl", "rsidtbl", "generator", "xmlnstbl", "latentstyles",
            "themedata", "colorschememapping", "datastore", "mmathPr", "pgdsctbl", "defchp", "defpap");

    private MailRtf() {}

    public static byte[] withHeader(byte[] rtf, Mail mail) {
        int at = bodyStart(rtf);
        StringBuilder h = new StringBuilder();
        if (mail.kind != null) {
            h.append("{\\uc1\\pard\\plain\\ltrpar\\ql\\sb0\\sa20\\fs18 ").append(escape(mail.kind)).append("\\par}");
        }
        h.append("{\\uc1\\pard\\plain\\ltrpar\\ql\\sb0\\sa60\\fs26\\b ");
        h.append(escape(mail.subject == null ? "(no subject)" : mail.subject)).append("\\b0\\par}");
        row(h, mail.organizer ? "Organizer" : "From", mail.from);
        row(h, "Sender", MailPage.shownSender(mail));
        row(h, "To", mail.to);
        row(h, "Cc", mail.cc);
        row(h, "Bcc", mail.bcc);
        row(h, "Reply-To", MailPage.shownReplyTo(mail));
        row(h, "Date", mail.date);
        for (String[] f : mail.fields) {
            row(h, f[0], f[1]);
        }
        if (!mail.attachments.isEmpty()) {
            StringBuilder files = new StringBuilder();
            for (Mail.Attachment a : mail.attachments) {
                if (!files.isEmpty()) {
                    files.append("; ");
                }
                files.append(MailPage.describe(mail, a));
            }
            row(h, mail.attachments.size() == 1 ? "Attachment" : "Attachments (" + mail.attachments.size() + ")",
                    files.toString());
        }
        for (Mail nested : mail.nested) {
            row(h, "Attached message", (nested.subject == null ? "(no subject)" : nested.subject)
                    + (nested.from == null ? "" : ", from " + nested.from));
        }
        h.append("{\\uc1\\pard\\plain\\ltrpar\\sb0\\sa240\\brdrb\\brdrs\\brdrw10\\brsp40\\fs4\\par}");
        byte[] insert = h.toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(rtf.length + insert.length);
        out.write(rtf, 0, at);
        out.write(insert, 0, insert.length);
        out.write(rtf, at, rtf.length - at);
        return out.toByteArray();
    }

    private static void row(StringBuilder h, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        h.append("{\\uc1\\pard\\plain\\ltrpar\\ql\\sb0\\sa20\\fs20{\\b ").append(escape(label)).append(":} ")
                .append(escape(value)).append("\\par}");
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '{' || c == '}') {
                out.append('\\').append(c);
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c < 0x20) {
                continue;
            } else if (c < 0x80) {
                out.append(c);
            } else {
                out.append("\\u").append((int) (short) c).append('?');
            }
        }
        return out.toString();
    }

    static int bodyStart(byte[] b) {
        int n = b.length;
        int i = 0;
        while (i < n && b[i] != '{') {
            i++;
        }
        if (i >= n) {
            return 0;
        }
        i++;
        while (i < n) {
            byte c = b[i];
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (c == '{') {
                int j = i + 1;
                boolean star = j + 1 < n && b[j] == '\\' && b[j + 1] == '*';
                String word = word(b, star ? j + 2 : j);
                if (star || HEADER.contains(word)) {
                    i = skipGroup(b, i);
                    continue;
                }
                return i;
            }
            if (c == '\\') {
                String word = word(b, i);
                if (word.isEmpty() || word.equals("pard") || word.equals("plain") || word.equals("sectd")
                        || word.equals("par") || word.equals("ltrpar") || word.equals("rtlpar")
                        || word.equals("trowd") || word.startsWith("pn") || word.equals("s")) {
                    return i;
                }
                i = controlEnd(b, i);
                continue;
            }
            if (c == '}') {
                return i;
            }
            if (c == ' ') {
                i++;
                continue;
            }
            return i;
        }
        return n;
    }

    private static String word(byte[] b, int at) {
        if (at >= b.length || b[at] != '\\') {
            return "";
        }
        int j = at + 1;
        while (j < b.length && j - at < 33 && (b[j] >= 'a' && b[j] <= 'z' || b[j] >= 'A' && b[j] <= 'Z')) {
            j++;
        }
        return new String(b, at + 1, j - at - 1, StandardCharsets.US_ASCII);
    }

    private static int controlEnd(byte[] b, int at) {
        int j = at + 1;
        if (j < b.length && !(b[j] >= 'a' && b[j] <= 'z' || b[j] >= 'A' && b[j] <= 'Z')) {
            return Math.min(b.length, j + 1);
        }
        while (j < b.length && (b[j] >= 'a' && b[j] <= 'z' || b[j] >= 'A' && b[j] <= 'Z')) {
            j++;
        }
        if (j < b.length && b[j] == '-') {
            j++;
        }
        while (j < b.length && b[j] >= '0' && b[j] <= '9') {
            j++;
        }
        if (j < b.length && b[j] == ' ') {
            j++;
        }
        return j;
    }

    private static int skipGroup(byte[] b, int at) {
        int depth = 0;
        int i = at;
        while (i < b.length) {
            byte c = b[i];
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return b.length;
    }
}
