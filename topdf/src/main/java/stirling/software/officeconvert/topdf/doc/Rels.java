package stirling.software.officeconvert.topdf.doc;

import java.util.LinkedHashMap;
import java.util.Map;

final class Rels {

    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private final StringBuilder xml = new StringBuilder();

    private final Map<String, String> media = new LinkedHashMap<>();

    private final Map<String, String> links = new LinkedHashMap<>();

    private int next = 1;

    String add(String type, String target) {
        String id = "rId" + next++;
        xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(OFFICE).append(type)
                .append("\" Target=\"").append(Xml.esc(target)).append("\"/>");
        return id;
    }

    String image(String target) {
        return media.computeIfAbsent(target, t -> add("image", "media/" + t));
    }

    String link(String url) {
        return links.computeIfAbsent(url, u -> {
            String id = "rId" + next++;
            xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(OFFICE).append("hyperlink")
                    .append("\" Target=\"").append(Xml.esc(u)).append("\" TargetMode=\"External\"/>");
            return id;
        });
    }

    boolean isEmpty() {
        return next == 1;
    }

    String part() {
        return Xml.HEAD + "<Relationships xmlns=\"" + Xml.PKG_REL + "\">" + xml + "</Relationships>";
    }
}
