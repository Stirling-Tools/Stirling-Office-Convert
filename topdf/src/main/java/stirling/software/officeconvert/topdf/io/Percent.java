package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class Percent {

    private Percent() {}

    public static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("Incomplete percent escape in " + s);
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("Bad percent escape in " + s);
                }
                out.write(hi << 4 | lo);
                i += 3;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
