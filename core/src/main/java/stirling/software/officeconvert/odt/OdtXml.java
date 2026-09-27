package stirling.software.officeconvert.odt;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class OdtXml {

    static final String NS = " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
            + " xmlns:number=\"urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\""
            + " xmlns:loext=\"urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0\""
            + " office:version=\"1.3\"";

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private OdtXml() {}

    static void esc(StringBuilder sb, String s) {
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
        esc(sb, s);
        return sb.toString();
    }

    static String pt(float v) {
        if (!Float.isFinite(v)) {
            v = 0;
        }
        BigDecimal d = BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.signum() == 0) {
            return "0pt";
        }
        return d.toPlainString() + "pt";
    }

    static String colour(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
