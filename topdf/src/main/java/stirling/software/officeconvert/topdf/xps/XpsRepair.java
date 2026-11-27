package stirling.software.officeconvert.topdf.xps;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class XpsRepair {

    private XpsRepair() {}

    static byte[] uniqueAttributes(byte[] data) {
        Charset cs = StandardCharsets.UTF_8;
        int skip = 0;
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            cs = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            cs = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            skip = 3;
        }
        String s = new String(data, skip, data.length - skip, cs);
        StringBuilder out = new StringBuilder(s.length());
        boolean changed = false;
        int i = 0;
        int n = s.length();
        Set<String> seen = new HashSet<>();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '<' || i + 1 >= n || !nameStart(s.charAt(i + 1))) {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && nameChar(s.charAt(j))) {
                j++;
            }
            out.append(s, i, j);
            seen.clear();
            while (true) {
                int k = j;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k >= n) {
                    return null;
                }
                char d = s.charAt(k);
                if (d == '>' || d == '/') {
                    out.append(s, j, k);
                    j = k;
                    break;
                }
                int nameStart = k;
                while (k < n && nameChar(s.charAt(k))) {
                    k++;
                }
                if (k == nameStart) {
                    return null;
                }
                String name = s.substring(nameStart, k);
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k >= n || s.charAt(k) != '=') {
                    return null;
                }
                k++;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k >= n || s.charAt(k) != '"' && s.charAt(k) != '\'') {
                    return null;
                }
                char q = s.charAt(k);
                int end = s.indexOf(q, k + 1);
                if (end < 0) {
                    return null;
                }
                if (seen.add(name)) {
                    out.append(s, j, end + 1);
                } else {
                    changed = true;
                }
                j = end + 1;
            }
            i = j;
        }
        if (!changed) {
            return null;
        }
        String fixed = out.toString();
        if (fixed.startsWith("<?xml")) {
            int end = fixed.indexOf("?>");
            if (end > 0) {
                fixed = fixed.substring(0, end).replaceAll("encoding\\s*=\\s*[\"'][^\"']*[\"']", "encoding=\"UTF-8\"")
                        + fixed.substring(end);
            }
        }
        return fixed.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean nameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.' || c == '-';
    }
}
