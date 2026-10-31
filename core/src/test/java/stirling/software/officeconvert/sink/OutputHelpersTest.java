package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

class OutputHelpersTest {

    private static final int[] COLOURS = {0, 1, 0xF, 0x10, 0xABCDEF, 0xFFFFFF, 0x1000000, 0x7FFFFFFF, -1,
        Integer.MIN_VALUE, 0x80FEDCBA, 0x00A0B0C0};

    @Test
    void hexMatchesFormat() {
        Random random = new Random(7);
        for (int i = 0; i < COLOURS.length + 10_000; i++) {
            int rgb = i < COLOURS.length ? COLOURS[i] : random.nextInt();
            assertEquals(String.format("%06X", rgb & 0xFFFFFF), ColourHex.upper(rgb));
            assertEquals(String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF), "#" + ColourHex.lower(rgb));
        }
    }

    @Test
    void escapeMatchesTheWriterPolicies() {
        String[] samples = {"", "plain", "<a href=\"x\">&amp;</a>", "tab\there\nline\rret\0nul",
            "￾￿�", "pair 😀 end", "lone \uD83D x", "lone \uDE00 x", "end \uD83D",
            "\uDE00\uD83D", "\u001F \u007F­"};
        Random random = new Random(11);
        for (int i = 0; i < samples.length + 2_000; i++) {
            String s = i < samples.length ? samples[i] : randomText(random);
            StringBuilder dropped = new StringBuilder();
            XmlText.escape(dropped, s);
            assertEquals(reference(s, false), dropped.toString());
            StringBuilder kept = new StringBuilder();
            XmlText.escapeKeepingTabs(kept, s);
            assertEquals(reference(s, true), kept.toString());
        }
    }

    @Test
    void keepOpenForwardsBulkWritesAndFlushesOnClose() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        try (KeepOpen out = new KeepOpen(target)) {
            out.write(new byte[] {1, 2, 3, 4}, 1, 2);
            out.write(9);
        }
        target.write(5);
        assertEquals("[2, 3, 9, 5]", Arrays.toString(target.toByteArray()));
    }

    private static String randomText(Random random) {
        char[] chars = new char[random.nextInt(12)];
        char[] pool = {'\t', '\n', '\r', '\0', '<', '>', '&', '"', 'a', '\uD83D', '\uDE00', '￾', '￿', 'é'};
        for (int i = 0; i < chars.length; i++) {
            chars[i] = random.nextBoolean() ? pool[random.nextInt(pool.length)] : (char) random.nextInt(0x10000);
        }
        return new String(chars);
    }

    private static String reference(String s, boolean tab) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF || tab && c == '\t') {
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
        return sb.toString();
    }
}
