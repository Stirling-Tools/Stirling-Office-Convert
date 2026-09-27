package stirling.software.officeconvert.docx;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.model.Picture;

final class PartContext {

    final Map<String, String> imageRels = new LinkedHashMap<>();
    final Map<String, String> linkRels = new LinkedHashMap<>();
    final Map<String, String> headerRelIds = new LinkedHashMap<>();
    final Set<String> fonts = new LinkedHashSet<>();
    private int nextRel = 20;
    private int nextDocPr = 1;
    private int nextBookmark = 1;

    String imageRel(Picture.MediaRef ref) {
        return imageRels.computeIfAbsent(ref.name(), n -> "rId" + nextRel++);
    }

    String linkRel(String url) {
        return linkRels.computeIfAbsent(url, u -> "rId" + nextRel++);
    }

    int docPr() {
        return nextDocPr++;
    }

    int bookmark() {
        return nextBookmark++;
    }
}
