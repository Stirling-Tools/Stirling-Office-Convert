package stirling.software.officeconvert.topdf.mail;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MailCharsets {

    public static final Charset WESTERN = Charset.forName("windows-1252");

    private MailCharsets() {}

    public static Charset forName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (n.length() > 1 && (n.startsWith("\"") && n.endsWith("\"") || n.startsWith("'") && n.endsWith("'"))) {
            n = n.substring(1, n.length() - 1).strip();
        }
        int star = n.indexOf('*');
        if (star > 0) {
            n = n.substring(0, star);
        }
        if (n.endsWith("_charset")) {
            n = n.substring(0, n.length() - "_charset".length());
        }
        String alias = switch (n) {
            case "", "us-ascii", "ascii", "ansi_x3.4-1968", "iso-8859-1", "iso8859-1", "iso_8859-1", "latin1",
                    "latin-1", "l1", "cp819", "default", "unknown-8bit", "x-unknown", "unknown", "x-user-defined",
                    "8bit", "cp1252", "windows-1252", "ansi" -> "windows-1252";
            case "utf8", "utf-8", "unicode-1-1-utf-8", "x-unicode20utf8" -> "UTF-8";
            case "gb2312", "gbk", "x-gbk", "cp936", "ms936", "windows-936", "euc-cn", "gb_2312-80", "csgb2312",
                    "chinese", "x-euc-cn", "gb2312-80", "gb18030" -> "GB18030";
            case "big5", "big-5", "chinesebig5", "cn-big5", "x-x-big5", "csbig5", "cp950", "ms950", "windows-950",
                    "x-windows-950" -> "x-windows-950";
            case "big5-hkscs" -> "Big5-HKSCS";
            case "ks_c_5601-1987", "ks_c_5601", "ks_c_5601-1989", "ksc5601", "ksc_5601", "euc-kr", "korean", "cp949",
                    "ms949", "windows-949", "x-windows-949", "csksc56011987", "iso-ir-149" -> "x-windows-949";
            case "shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "cp932", "ms932", "windows-31j",
                    "csshiftjis", "x-ms-cp932" -> "windows-31j";
            case "iso-2022-jp", "csiso2022jp", "iso-2022-jp-1", "iso-2022-jp-2", "iso-2022-jp-3" -> "x-windows-iso2022jp";
            case "euc-jp", "x-euc-jp", "cseucpkdfmtjapanese" -> "EUC-JP";
            case "iso-8859-8-i", "iso-8859-8-e", "logical", "visual" -> "ISO-8859-8";
            case "iso-8859-6-i", "iso-8859-6-e" -> "ISO-8859-6";
            case "tis-620", "tis620", "windows-874", "cp874", "x-windows-874", "iso-8859-11" -> "x-windows-874";
            case "koi8", "koi8r", "koi8-r", "cskoi8r" -> "KOI8-R";
            case "koi8-u", "koi8-ru" -> "KOI8-U";
            case "utf-16", "unicode", "ucs-2" -> "UTF-16";
            default -> n;
        };
        try {
            return Charset.forName(alias);
        } catch (RuntimeException e) {
            if (n.startsWith("cp") || n.startsWith("ms")) {
                try {
                    return Charset.forName("windows-" + n.substring(2));
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    public static String decode(byte[] bytes, String name) {
        Charset c = forName(name);
        if (c == null || c.equals(WESTERN) || c.equals(StandardCharsets.UTF_8)) {
            String utf8 = strictUtf8(bytes);
            return stripBom(utf8 != null ? utf8 : new String(bytes, WESTERN));
        }
        if (c.name().equals("UTF-16") && !bom16(bytes)) {
            c = StandardCharsets.UTF_16LE;
        }
        return stripBom(new String(bytes, c));
    }

    public static String decode(byte[] bytes, Charset fallback) {
        String utf8 = strictUtf8(bytes);
        if (utf8 != null) {
            return utf8;
        }
        return new String(bytes, fallback == null ? WESTERN : fallback);
    }

    static String raw(String latin1, Charset fallback) {
        boolean high = false;
        for (int i = 0; i < latin1.length(); i++) {
            if (latin1.charAt(i) >= 0x80) {
                high = true;
                break;
            }
        }
        if (!high) {
            return latin1;
        }
        byte[] b = latin1.getBytes(StandardCharsets.ISO_8859_1);
        return decode(b, fallback);
    }

    static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static boolean bom16(byte[] b) {
        return b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF
                || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE);
    }

    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    public static String metaCharset(byte[] html) {
        int n = Math.min(html.length, 4096);
        String head = new String(html, 0, n, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        int i = head.indexOf("charset=");
        if (i < 0) {
            return null;
        }
        int s = i + 8;
        while (s < head.length() && (head.charAt(s) == '"' || head.charAt(s) == '\'' || head.charAt(s) == ' ')) {
            s++;
        }
        int e = s;
        while (e < head.length() && (Character.isLetterOrDigit(head.charAt(e)) || "-_:.".indexOf(head.charAt(e)) >= 0)) {
            e++;
        }
        return e > s ? head.substring(s, e) : null;
    }
}
