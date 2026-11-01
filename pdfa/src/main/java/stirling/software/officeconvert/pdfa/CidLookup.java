package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class CidLookup {

    private static final Map<String, Optional<CMap>> OWN_ENTRIES = new ConcurrentHashMap<>();

    private CidLookup() {}

    static int cid(PDType0Font font, int code) {
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
