package stirling.software.officeconvert.topdf.docx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Liang patterns from the bundled Hyphen dictionaries (BSD-style licence beside them in hyph/)
final class Hyphenator {

    private static final Map<String, Hyphenator> LOADED = new HashMap<>();

    private final Map<String, byte[]> patterns = new HashMap<>();

    private int left = 2;

    private int right = 3;

    private int longest;

    private Hyphenator(String resource) {
        try (InputStream in = Hyphenator.class.getResourceAsStream("hyph/" + resource + ".dic")) {
            if (in == null) {
                throw new IOException("missing hyphenation patterns " + resource);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("%")) {
                    continue;
                }
                if (line.startsWith("LEFTHYPHENMIN ")) {
                    left = Integer.parseInt(line.substring(14).strip());
                } else if (line.startsWith("RIGHTHYPHENMIN ")) {
                    right = Integer.parseInt(line.substring(15).strip());
                } else if (!Character.isUpperCase(line.charAt(0))) {
                    add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void add(String pattern) {
        StringBuilder letters = new StringBuilder();
        byte[] values = new byte[pattern.length() + 1];
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c >= '0' && c <= '9') {
                values[letters.length()] = (byte) (c - '0');
            } else {
                letters.append(c);
            }
        }
        byte[] v = new byte[letters.length() + 1];
        System.arraycopy(values, 0, v, 0, v.length);
        patterns.put(letters.toString(), v);
        longest = Math.max(longest, letters.length());
    }

    static synchronized Hyphenator forLanguage(String lang) {
        String l = lang == null ? "en-us" : lang.toLowerCase(Locale.ROOT).replace('_', '-');
        if (!l.equals("en") && !l.startsWith("en-")) {
            return null;
        }
        boolean british = l.startsWith("en-gb") || l.startsWith("en-au") || l.startsWith("en-nz")
                || l.startsWith("en-ie") || l.startsWith("en-za") || l.startsWith("en-in");
        return LOADED.computeIfAbsent(british ? "en_GB" : "en_US", Hyphenator::new);
    }

    // Offsets inside the word where a hyphen may go, in ascending order
    int[] points(String word) {
        int n = word.length();
        if (n < left + right) {
            return new int[0];
        }
        String w = "." + word.toLowerCase(Locale.ROOT) + ".";
        byte[] best = new byte[w.length() + 1];
        for (int i = 0; i < w.length(); i++) {
            for (int j = i + 1; j <= Math.min(w.length(), i + longest); j++) {
                byte[] v = patterns.get(w.substring(i, j));
                if (v != null) {
                    for (int k = 0; k < v.length; k++) {
                        best[i + k] = (byte) Math.max(best[i + k], v[k]);
                    }
                }
            }
        }
        int count = 0;
        int[] out = new int[n];
        for (int p = left; p <= n - right; p++) {
            if ((best[p + 1] & 1) == 1) {
                out[count++] = p;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }
}
