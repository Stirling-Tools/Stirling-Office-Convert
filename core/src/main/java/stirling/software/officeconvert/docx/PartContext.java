package stirling.software.officeconvert.docx;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.html.PdfWebFonts;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;

final class PartContext {

    final Map<String, String> imageRels = new LinkedHashMap<>();
    final Map<String, String> linkRels = new LinkedHashMap<>();
    final Map<String, String> headerRelIds = new LinkedHashMap<>();
    final Set<String> fonts = new LinkedHashSet<>();
    Scripts.Profile scripts = new Scripts.Profile();
    PdfWebFonts sourceFonts;
    private int nextRel = 20;
    private int nextDocPr = 1;
    private int nextBookmark = 1;

    String imageRel(Picture.MediaRef ref) {
        return imageRels.computeIfAbsent(ref.name(), n -> "rId" + nextRel++);
    }

    String linkRel(String url) {
        return linkRels.computeIfAbsent(url, u -> "rId" + nextRel++);
    }

    void useFont(RunStyle style, RunStyle base, String text) {
        RunStyle used = style != null ? style : base;
        if (sourceFonts == null || used == null) {
            return;
        }
        String font = used.font() != null || base == null ? used.font() : base.font();
        if (!PdfWebFonts.expected(font)) {
            sourceFonts.use(font, used.bold(), used.italic(), text);
        }
    }

    int docPr() {
        return nextDocPr++;
    }

    int bookmark() {
        return nextBookmark++;
    }
}
