package stirling.software.officeconvert.topdf.mail;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class Mail {

    public record Attachment(String name, String type, long size, byte[] data, String contentId, String location) {}

    public sealed interface Segment permits Html, Text, Image {}

    public record Html(String html) implements Segment {}

    public record Text(String text) implements Segment {}

    public record Image(String reference) implements Segment {}

    String subject;
    String from;
    String sender;
    String to;
    String cc;
    String bcc;
    String replyTo;
    String date;
    byte[] rtf;
    final List<Segment> body = new ArrayList<>();
    final List<Attachment> attachments = new ArrayList<>();
    final List<Mail> nested = new ArrayList<>();
    final Map<Attachment, String> notes = new IdentityHashMap<>();

    public String subject() {
        return subject;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String cc() {
        return cc;
    }

    public String date() {
        return date;
    }

    public List<Segment> body() {
        return body;
    }

    public List<Attachment> attachments() {
        return attachments;
    }

    public List<Mail> nested() {
        return nested;
    }

    public byte[] rtf() {
        return rtf;
    }

    boolean hasHtml() {
        for (Segment s : body) {
            if (s instanceof Html) {
                return true;
            }
        }
        return false;
    }
}
