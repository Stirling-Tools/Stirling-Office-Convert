package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.font.FontSet;

final class BookFonts {

    private static final long MAX_TOTAL_BYTES = 128L << 20;

    private BookFonts() {}

    static Options with(Options options, Map<String, String> faces, HtmlAssets assets) throws IOException {
        if (faces.isEmpty()) {
            return options;
        }
        FontSet.Builder set = options.fonts().toBuilder();
        long total = 0;
        boolean added = false;
        for (Map.Entry<String, String> face : faces.entrySet()) {
            OfficeToPdf.stopIfInterrupted();
            byte[] data = assets.read(face.getValue());
            if (data == null || data.length > FontSet.MAX_FONT_BYTES || total + data.length > MAX_TOTAL_BYTES) {
                continue;
            }
            SortedSet<String> families = FontLibrary.of(List.of()).withFonts(List.of(data)).families();
            if (families.isEmpty()) {
                continue;
            }
            total += data.length;
            set.font(data);
            String family = families.first();
            if (!family.equalsIgnoreCase(face.getKey())) {
                set.substitute(face.getKey(), family);
            }
            added = true;
        }
        return added ? options.fonts(set.build()) : options;
    }
}
