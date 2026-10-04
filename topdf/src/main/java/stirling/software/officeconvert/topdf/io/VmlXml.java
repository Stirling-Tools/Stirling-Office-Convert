package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.w3c.dom.Document;

// A legacy VML drawing part, which Office writes with HTML leftovers: conditional comments, bare <br> and &nbsp;
public final class VmlXml {

    public static final int MAX_BYTES = 8 << 20;

    private static final Pattern CONDITIONAL = Pattern.compile("<!\\[(?:if[^\\]]{0,200}|endif)\\]>");

    private static final Pattern BREAK = Pattern.compile("<br\\s*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]{0,500}\\?>");

    private VmlXml() {}

    public static Document parse(byte[] data) throws IOException {
        if (data.length > MAX_BYTES) {
            throw new IOException("The VML drawing is too large");
        }
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) {
            s = s.substring(1);
        }
        s = DECLARATION.matcher(s).replaceFirst("");
        s = CONDITIONAL.matcher(s).replaceAll("");
        s = BREAK.matcher(s).replaceAll("<br/>");
        s = s.replace("&nbsp;", "&#160;");
        return SecureXml.parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }
}
