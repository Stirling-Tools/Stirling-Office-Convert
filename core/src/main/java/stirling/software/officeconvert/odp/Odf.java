package stirling.software.officeconvert.odp;

import java.util.Locale;

final class Odf {

    static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    static final String NAMESPACES = " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\""
            + " xmlns:smil=\"urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0\""
            + " office:version=\"1.2\"";

    private Odf() {}

    static void text(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF) {
                        if (Character.isHighSurrogate(c)) {
                            if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                                sb.append(c).append(s.charAt(++i));
                            }
                        } else if (!Character.isLowSurrogate(c)) {
                            sb.append(c);
                        }
                    }
                }
            }
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        text(sb, s);
        return sb.toString();
    }

    static String cm(float pt) {
        return String.format(Locale.ROOT, "%.4fcm", pt * 2.54f / 72f);
    }

    static String pt(float pt) {
        return String.format(Locale.ROOT, "%.2fpt", pt);
    }

    static String colour(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
