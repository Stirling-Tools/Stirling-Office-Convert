package stirling.software.officeconvert.topdf.markdown;

import java.util.Locale;
import java.util.regex.Pattern;

import org.commonmark.renderer.html.UrlSanitizer;

final class SafeUrls implements UrlSanitizer {

    private static final Pattern LINK = Pattern.compile("(?i)(?:https?://|mailto:|#).*", Pattern.DOTALL);

    private static final Pattern PICTURE = Pattern.compile(
            "(?i)data:image/(?:png|jpeg|jpg|gif|webp|bmp|svg\\+xml)[;,].*", Pattern.DOTALL);

    @Override
    public String sanitizeLinkUrl(String url) {
        String u = url.strip();
        return LINK.matcher(u).matches() ? u : "";
    }

    @Override
    public String sanitizeImageUrl(String url) {
        String u = url.strip();
        if (PICTURE.matcher(u).matches()) {
            return u;
        }
        String lower = u.toLowerCase(Locale.ROOT);
        return u.isEmpty() || lower.indexOf(':') >= 0 || u.startsWith("/") || u.startsWith("\\") ? "" : u;
    }
}
