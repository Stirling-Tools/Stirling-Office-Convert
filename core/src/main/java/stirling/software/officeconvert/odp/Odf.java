package stirling.software.officeconvert.odp;

import java.util.Locale;

import stirling.software.officeconvert.sink.ColourHex;
import stirling.software.officeconvert.sink.XmlText;

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
            + " xmlns:loext=\"urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0\""
            + " office:version=\"1.2\"";

    private Odf() {}

    static void text(StringBuilder sb, String s) {
        XmlText.escape(sb, s);
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
        return "#" + ColourHex.lower(rgb);
    }
}
