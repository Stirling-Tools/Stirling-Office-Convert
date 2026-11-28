package stirling.software.officeconvert.topdf.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class RtfBody {

    public enum Kind { HTML, TEXT, RTF }

    public record Body(Kind kind, String content) {}

    static final int MAX_DEPTH = 512;

    private static final Set<String> HIDDEN = Set.of("colortbl", "stylesheet", "info", "pict", "object", "objdata",
            "themedata", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl", "mmathPr", "latentstyles",
            "datastore", "filetbl", "revtbl", "header", "headerl", "headerr", "headerf", "footer", "footerl",
            "footerr", "footerf", "generator", "colorschememapping", "fldinst", "bkmkstart", "bkmkend",
            "nonshppict", "shpinst", "pgdsctbl", "listtext", "pntxtb", "pntxta", "userprops", "docvar");

    private final String rtf;
    private final boolean html;
    private int pos;
    private final StringBuilder out = new StringBuilder();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Charset bytesCharset;
    private final Map<Integer, Integer> fontPages = new HashMap<>();
    private int defaultPage = 1252;
    private int defaultFont = -1;
    private int overflow;

    private static final class State {
        boolean suppressed;
        boolean hidden;
        boolean tag;
        boolean fonts;
        int font = -1;
        int uc = 1;
        int fontNumber = -1;

        State copy() {
            State s = new State();
            s.suppressed = suppressed;
            s.hidden = hidden;
            s.tag = tag;
            s.fonts = fonts;
            s.font = font;
            s.uc = uc;
            s.fontNumber = fontNumber;
            return s;
        }
    }

    private RtfBody(String rtf, boolean html) {
        this.rtf = rtf;
        this.html = html;
    }

    public static Body read(byte[] data) {
        String rtf = new String(data, StandardCharsets.ISO_8859_1);
        Kind kind = kind(rtf);
        if (kind == Kind.RTF) {
            return new Body(Kind.RTF, null);
        }
        RtfBody r = new RtfBody(rtf, kind == Kind.HTML);
        r.run();
        return new Body(kind, r.out.toString());
    }

    static Kind kind(String rtf) {
        int i = 0;
        int n = rtf.length();
        while (i < n && Character.isWhitespace(rtf.charAt(i))) {
            i++;
        }
        if (!rtf.startsWith("{\\rtf1", i) && !rtf.startsWith("{\\rtf", i)) {
            return Kind.RTF;
        }
        int tokens = 0;
        while (i < n && tokens < 10) {
            char c = rtf.charAt(i);
            if (c == '{') {
                tokens++;
                i++;
            } else if (c == '\\') {
                int j = i + 1;
                while (j < n && Character.isLetter(rtf.charAt(j)) && j - i < 34) {
                    j++;
                }
                String word = rtf.substring(i + 1, j);
                int k = j;
                if (k < n && rtf.charAt(k) == '-') {
                    k++;
                }
                while (k < n && Character.isDigit(rtf.charAt(k)) && k - j < 11) {
                    k++;
                }
                String param = rtf.substring(j, k);
                if (word.isEmpty()) {
                    return Kind.RTF;
                }
                if (word.equals("fromhtml") && param.equals("1")) {
                    return Kind.HTML;
                }
                if (word.equals("fromtext")) {
                    return Kind.TEXT;
                }
                tokens++;
                i = k < n && rtf.charAt(k) == ' ' ? k + 1 : k;
            } else if (c == '\r' || c == '\n') {
                i++;
            } else {
                return Kind.RTF;
            }
        }
        return Kind.RTF;
    }

    private void run() {
        Deque<State> stack = new ArrayDeque<>();
        State st = new State();
        int n = rtf.length();
        boolean groupStart = false;
        long budget = 8L * n + 1024;
        while (pos < n) {
            if (out.length() > budget) {
                break;
            }
            char c = rtf.charAt(pos);
            if (c == '{') {
                pos++;
                if (stack.size() < MAX_DEPTH && overflow == 0) {
                    stack.push(st);
                    st = st.copy();
                } else {
                    overflow++;
                }
                groupStart = true;
                continue;
            }
            if (c == '}') {
                pos++;
                if (overflow > 0) {
                    overflow--;
                    groupStart = false;
                    continue;
                }
                if (st.fonts && st.fontNumber >= 0 && !fontPages.containsKey(st.fontNumber)) {
                    fontPages.put(st.fontNumber, defaultPage);
                }
                if (!stack.isEmpty()) {
                    st = stack.pop();
                }
                groupStart = false;
                continue;
            }
            if (c == '\r' || c == '\n') {
                pos++;
                continue;
            }
            if (c != '\\') {
                pos++;
                if (visible(st)) {
                    text(c, st);
                }
                groupStart = false;
                continue;
            }
            int start = pos;
            pos++;
            if (pos >= n) {
                break;
            }
            char d = rtf.charAt(pos);
            if (!Character.isLetter(d)) {
                pos++;
                groupStart = symbol(d, st, groupStart, start);
                continue;
            }
            int j = pos;
            while (j < n && Character.isLetter(rtf.charAt(j)) && j - pos < 32) {
                j++;
            }
            String word = rtf.substring(pos, j);
            int k = j;
            boolean neg = k < n && rtf.charAt(k) == '-';
            if (neg) {
                k++;
            }
            int digits = k;
            while (k < n && Character.isDigit(rtf.charAt(k)) && k - digits < 10) {
                k++;
            }
            boolean hasParam = k > digits;
            int param = 0;
            if (hasParam) {
                try {
                    param = Integer.parseInt(rtf.substring(digits, k));
                } catch (NumberFormatException e) {
                    param = 0;
                }
                if (neg) {
                    param = -param;
                }
            }
            pos = k < n && rtf.charAt(k) == ' ' ? k + 1 : k;
            boolean first = groupStart;
            groupStart = false;
            word(word, hasParam, param, st, first);
        }
        flush();
    }

    private boolean visible(State st) {
        if (overflow > 0 || st.hidden || st.fonts) {
            return false;
        }
        return st.tag || !st.suppressed;
    }

    private boolean symbol(char d, State st, boolean groupStart, int start) {
        switch (d) {
            case '*' -> {
                if (groupStart) {
                    int save = pos;
                    if (rtf.startsWith("\\htmltag", pos) && html) {
                        st.tag = true;
                        st.hidden = false;
                        pos = save + "\\htmltag".length();
                        while (pos < rtf.length() && Character.isDigit(rtf.charAt(pos))) {
                            pos++;
                        }
                        if (pos < rtf.length() && rtf.charAt(pos) == ' ') {
                            pos++;
                        }
                        return false;
                    }
                    st.hidden = true;
                }
                return false;
            }
            case '\'' -> {
                if (pos + 2 <= rtf.length()) {
                    int hi = Character.digit(rtf.charAt(pos), 16);
                    int lo = Character.digit(rtf.charAt(pos + 1), 16);
                    pos += 2;
                    if (hi >= 0 && lo >= 0 && visible(st)) {
                        raw(hi << 4 | lo, st);
                    }
                }
                return false;
            }
            case '{', '}', '\\' -> {
                if (visible(st)) {
                    text(d, st);
                }
                return false;
            }
            case '~' -> {
                if (visible(st)) {
                    unicode(0xA0);
                }
                return false;
            }
            case '_' -> {
                if (visible(st)) {
                    unicode(0xAD);
                }
                return false;
            }
            case '-' -> {
                return false;
            }
            case '\n', '\r' -> {
                if (visible(st)) {
                    unicode('\r');
                    unicode('\n');
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void word(String word, boolean hasParam, int param, State st, boolean first) {
        if (overflow > 0) {
            return;
        }
        if (first && (HIDDEN.contains(word) || word.equals("fonttbl"))) {
            if (word.equals("fonttbl")) {
                st.fonts = true;
            } else {
                st.hidden = true;
            }
            return;
        }
        switch (word) {
            case "htmlrtf" -> st.suppressed = !hasParam || param != 0;
            case "ansicpg" -> {
                if (hasParam && param > 0 && !st.hidden) {
                    defaultPage = param;
                }
            }
            case "deff" -> {
                if (hasParam && !st.hidden) {
                    defaultFont = param;
                }
            }
            case "f" -> {
                if (st.fonts) {
                    st.fontNumber = param;
                } else {
                    st.font = param;
                }
            }
            case "fcharset" -> {
                if (st.fonts && st.fontNumber >= 0) {
                    int page = page(param);
                    fontPages.put(st.fontNumber, page == 0 ? defaultPage : page);
                }
            }
            case "cpg" -> {
                if (st.fonts && st.fontNumber >= 0 && param > 0) {
                    fontPages.put(st.fontNumber, param);
                }
            }
            case "uc" -> st.uc = Math.max(0, Math.min(param, 8));
            case "u" -> {
                if (visible(st)) {
                    unicode(param < 0 ? param + 65536 : param);
                }
                skipFallback(st.uc);
            }
            case "bin" -> pos = (int) Math.min(rtf.length(), (long) pos + Math.max(0, param));
            case "par", "line" -> {
                if (visible(st)) {
                    unicode('\r');
                    unicode('\n');
                }
            }
            case "plain" -> st.font = -1;
            case "tab" -> emit(st, '\t');
            case "lquote" -> emit(st, 0x2018);
            case "rquote" -> emit(st, 0x2019);
            case "ldblquote" -> emit(st, 0x201C);
            case "rdblquote" -> emit(st, 0x201D);
            case "bullet" -> emit(st, 0x2022);
            case "endash" -> emit(st, 0x2013);
            case "emdash" -> emit(st, 0x2014);
            case "emspace", "enspace", "qmspace" -> emit(st, ' ');
            case "mhtmltag" -> st.hidden = true;
            default -> {
                if (!html && first && word.equals("htmltag")) {
                    st.hidden = true;
                }
            }
        }
    }

    private void emit(State st, int cp) {
        if (visible(st)) {
            unicode(cp);
        }
    }

    private void skipFallback(int count) {
        int n = rtf.length();
        int left = count;
        while (left > 0 && pos < n) {
            char c = rtf.charAt(pos);
            if (c == '{' || c == '}') {
                return;
            }
            if (c == '\\') {
                if (pos + 1 < n && rtf.charAt(pos + 1) == '\'') {
                    pos = Math.min(n, pos + 4);
                } else if (pos + 1 < n && Character.isLetter(rtf.charAt(pos + 1))) {
                    int j = pos + 1;
                    while (j < n && Character.isLetter(rtf.charAt(j))) {
                        j++;
                    }
                    if (j < n && rtf.charAt(j) == '-') {
                        j++;
                    }
                    while (j < n && Character.isDigit(rtf.charAt(j))) {
                        j++;
                    }
                    if (j < n && rtf.charAt(j) == ' ') {
                        j++;
                    }
                    pos = j;
                } else {
                    pos = Math.min(n, pos + 2);
                }
            } else if (c == '\r' || c == '\n') {
                pos++;
                continue;
            } else {
                pos++;
            }
            left--;
        }
    }

    private void text(char c, State st) {
        raw(c & 0xFF, st);
    }

    private void raw(int b, State st) {
        Charset cs = charset(st.tag ? defaultPage : fontPages.getOrDefault(st.font >= 0 ? st.font : defaultFont, defaultPage));
        if (bytesCharset != null && !bytesCharset.equals(cs)) {
            flush();
        }
        bytesCharset = cs;
        bytes.write(b);
    }

    private void unicode(int cp) {
        flush();
        if (Character.isValidCodePoint(cp)) {
            out.appendCodePoint(cp);
        }
    }

    private void flush() {
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), bytesCharset == null ? MailCharsets.WESTERN : bytesCharset));
            bytes.reset();
        }
    }

    private final Map<Integer, Charset> charsets = new HashMap<>();

    private Charset charset(int page) {
        return charsets.computeIfAbsent(page, p -> {
            Charset c = switch (p) {
                case 932 -> MailCharsets.forName("windows-31j");
                case 936 -> MailCharsets.forName("gbk");
                case 949 -> MailCharsets.forName("ks_c_5601-1987");
                case 950 -> MailCharsets.forName("big5");
                case 874 -> MailCharsets.forName("windows-874");
                case 65001 -> StandardCharsets.UTF_8;
                case 10000 -> MailCharsets.forName("x-MacRoman");
                case 437 -> MailCharsets.forName("IBM437");
                case 20127 -> StandardCharsets.US_ASCII;
                default -> MailCharsets.forName("windows-" + p);
            };
            return c == null ? MailCharsets.WESTERN : c;
        });
    }

    static int page(int fcharset) {
        return switch (fcharset) {
            case 0 -> 1252;
            case 2 -> 1252;
            case 77 -> 10000;
            case 128 -> 932;
            case 129 -> 949;
            case 130 -> 1361;
            case 134 -> 936;
            case 136 -> 950;
            case 161 -> 1253;
            case 162 -> 1254;
            case 163 -> 1258;
            case 177 -> 1255;
            case 178 -> 1256;
            case 186 -> 1257;
            case 204 -> 1251;
            case 222 -> 874;
            case 238 -> 1250;
            case 254 -> 437;
            case 255 -> 850;
            default -> 0;
        };
    }
}
