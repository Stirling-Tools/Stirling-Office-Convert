package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public final class CidLookup {

    private static final Map<String, Optional<CMap>> OWN_ENTRIES = new ConcurrentHashMap<>();

    private CidLookup() {}

    public static int cid(PDType0Font font, int code) {
        int inherited = font.codeToCID(code);
        if (!(font.getCOSObject().getDictionaryObject(COSName.ENCODING) instanceof COSName name)) {
            return inherited;
        }
        CMap own = OWN_ENTRIES.computeIfAbsent(name.getName(), CidLookup::ownEntries).orElse(null);
        if (own == null) {
            return inherited;
        }
        int length = code > 0xFFFFFF ? 4 : code > 0xFFFF ? 3 : code > 0xFF ? 2 : 1;
        int cid = own.toCID(code, length);
        return cid > 0 ? cid : inherited;
    }

    private static final Set<String> CJK_ORDERINGS = Set.of("GB1", "CNS1", "Japan1", "Korea1");

    private static final Pattern UNICODE_CMAP = Pattern.compile("Uni[A-Za-z0-9]+-(UCS2|UTF16)(-HW)?-[HV]");

    /** Unicode CMaps carry the text in the code itself; the CID round trip would lose some of it. */
    public static String text(PDType0Font font, int code) {
        String cmap = font.getCOSObject().getNameAsString(COSName.ENCODING);
        String t;
        if (cmap != null && UNICODE_CMAP.matcher(cmap).matches()) {
            t = (code & 0xFFFF0000) == 0 ? String.valueOf((char) code)
                    : new String(new char[] {(char) (code >>> 16), (char) code});
            if (privateUse(t) && font.getCMapUCS2() != null) {
                String collected = font.getCMapUCS2().toUnicode(cid(font, code));
                t = collected != null && !privateUse(collected) ? collected : t;
            }
        } else {
            try {
                t = font.getCMapUCS2() != null ? font.getCMapUCS2().toUnicode(cid(font, code))
                        : font.toUnicode(code);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return t == null || t.indexOf('\uFFFD') >= 0 || !pairedSurrogates(t) ? null : t;
    }

    private static boolean privateUse(String t) {
        int cp = t.codePointAt(0);
        return t.length() == Character.charCount(cp) && Character.getType(cp) == Character.PRIVATE_USE;
    }

    private static boolean pairedSurrogates(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /** An Adobe CJK collection fixes each CID's meaning, so its UCS2 map is the spec's own answer. */
    public static boolean adobe(PDType0Font font) {
        PDCIDFont cid = font.getDescendantFont();
        PDCIDSystemInfo info = cid == null ? null : cid.getCIDSystemInfo();
        return info != null && "Adobe".equals(info.getRegistry()) && CJK_ORDERINGS.contains(info.getOrdering());
    }

    static Optional<CMap> ownEntries(String name) {
        if (name.contains("/") || name.contains("..") || name.startsWith("Identity-")) {
            return Optional.empty();
        }
        try (InputStream in = CMap.class.getResourceAsStream("/org/apache/fontbox/cmap/" + name)) {
            if (in == null) {
                return Optional.empty();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            if (!text.contains("usecmap")) {
                return Optional.empty();
            }
            String alone = text.replaceAll("/[^\\s/]+\\s+usecmap", "");
            return Optional.of(new CMapParser().parse(
                    new RandomAccessReadBuffer(alone.getBytes(StandardCharsets.ISO_8859_1))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
