package stirling.software.officeconvert.topdf.pdf;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;

public record DocumentInfo(String title, String author, String subject, String keywords, String language) {

    public static final DocumentInfo EMPTY = new DocumentInfo(null, null, null, null, null);

    public static final int MAX_LENGTH = 2000;

    public DocumentInfo {
        title = clean(title);
        author = clean(author);
        subject = clean(subject);
        keywords = clean(keywords);
        language = clean(language);
    }

    public DocumentInfo title(String value) {
        return new DocumentInfo(value, author, subject, keywords, language);
    }

    public DocumentInfo author(String value) {
        return new DocumentInfo(title, value, subject, keywords, language);
    }

    public DocumentInfo subject(String value) {
        return new DocumentInfo(title, author, value, keywords, language);
    }

    public DocumentInfo keywords(String value) {
        return new DocumentInfo(title, author, subject, value, language);
    }

    public DocumentInfo language(String value) {
        return new DocumentInfo(title, author, subject, keywords, value);
    }

    public static DocumentInfo read(OfficeZip zip) throws IOException {
        Relationship core = zip.packageRelationships().first("core-properties");
        if (core == null || core.part() == null || !zip.exists(core.part())) {
            return EMPTY;
        }
        Element root;
        try {
            root = zip.xml(core.part()).getDocumentElement();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return EMPTY;
        }
        DocumentInfo info = EMPTY;
        for (Node n = root == null ? null : root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el) || el.getLocalName() == null) {
                continue;
            }
            String text = el.getTextContent();
            info = switch (el.getLocalName()) {
                case "title" -> info.title(text);
                case "creator" -> info.author(text);
                case "subject" -> info.subject(text);
                case "keywords" -> info.keywords(text);
                case "language" -> info.language(text);
                default -> info;
            };
        }
        return info;
    }

    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int i = 0; i < value.length() && sb.length() < MAX_LENGTH; ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
            } else if (!(cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069 || cp == 0x200E || cp == 0x200F
                    || cp == 0x061C || cp == 0xFEFF)) {
                sb.appendCodePoint(cp);
            }
        }
        String s = sb.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
