package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.Word;

public final class ScriptMarks {

    public static final char SUPER = '﷐';
    public static final char SUB = '﷑';
    public static final char END = '﷒';

    public record Run(String text, int level) {}

    private ScriptMarks() {}

    public static boolean has(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SUPER || c == SUB || c == END) {
                return true;
            }
        }
        return false;
    }

    public static String strip(String s) {
        if (!has(s)) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != SUPER && c != SUB && c != END) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static List<Run> runs(String s) {
        List<Run> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int level = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int next = c == SUPER ? 1 : c == SUB ? -1 : c == END ? 0 : level;
            if (next != level || c == SUPER || c == SUB || c == END) {
                if (!sb.isEmpty()) {
                    out.add(new Run(sb.toString(), level));
                    sb.setLength(0);
                }
                level = next;
                continue;
            }
            sb.append(c);
        }
        if (!sb.isEmpty()) {
            out.add(new Run(sb.toString(), level));
        }
        return out;
    }

    static String mark(Line line, String text) {
        List<Glyph> glyphs = new ArrayList<>();
        boolean scripted = false;
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                glyphs.add(g);
                scripted |= level(g) != 0;
            }
        }
        if (!scripted || has(text)) {
            return text;
        }
        int[] levels = new int[text.length()];
        int at = 0;
        for (Glyph g : glyphs) {
            if (g.text.isBlank()) {
                continue;
            }
            int found = text.indexOf(g.text, at);
            if (found < 0 || !text.substring(at, found).isBlank()) {
                return text;
            }
            for (int k = found; k < found + g.text.length(); k++) {
                levels[k] = level(g);
            }
            at = found + g.text.length();
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        int open = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            int want = c == ' ' && open != 0 && k + 1 < text.length() && levels[k + 1] == open ? open : levels[k];
            if (want != open) {
                if (open != 0) {
                    sb.append(END);
                }
                if (want != 0) {
                    sb.append(want > 0 ? SUPER : SUB);
                }
                open = want;
            }
            sb.append(c);
        }
        if (open != 0) {
            sb.append(END);
        }
        return sb.toString();
    }

    private static int level(Glyph g) {
        return g.vertAlign == 1 || g.vertAlign == -1 ? g.vertAlign : 0;
    }
}
