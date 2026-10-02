package stirling.software.officeconvert.pdfa;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

final class NameText {

    private static final List<String> LEGACY = List.of("x-windows-949", "GBK", "Shift_JIS", "Big5");

    private NameText() {}

    static boolean accepted(byte[] b) {
        for (byte x : b) {
            if (x < 0) {
                String s = decode(b, StandardCharsets.UTF_8);
                return s != null && s.chars().noneMatch(c -> c >= 0x80 && c <= 0xFF);
            }
        }
        return true;
    }

    static String repair(byte[] b) {
        String s = decode(b, StandardCharsets.UTF_8);
        if (s == null) {
            for (String name : LEGACY) {
                if (Charset.isSupported(name)) {
                    String t = decode(b, Charset.forName(name));
                    if (t != null && t.chars().allMatch(c -> c < 0x80 || c > 0xFF)) {
                        return t;
                    }
                }
            }
            s = new String(b, StandardCharsets.ISO_8859_1);
        }
        String folded = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder(folded.length());
        folded.codePoints().forEach(c -> out.appendCodePoint(c >= 0x80 && c <= 0xFF ? '_' : c));
        return out.toString();
    }

    private static String decode(byte[] b, Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
