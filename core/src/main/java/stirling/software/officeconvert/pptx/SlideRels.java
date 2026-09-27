package stirling.software.officeconvert.pptx;

import java.util.LinkedHashMap;
import java.util.Map;

final class SlideRels {

    private final Map<String, String> ids = new LinkedHashMap<>();
    private final StringBuilder xml = new StringBuilder();

    SlideRels(String layout) {
        add("slideLayout", "../slideLayouts/" + layout, false);
    }

    String image(String mediaName) {
        return add("image", "../media/" + mediaName, false);
    }

    String link(String url) {
        return add("hyperlink", url, true);
    }

    String slide(int number) {
        return add("slide", "slide" + number + ".xml", false);
    }

    private String add(String type, String target, boolean external) {
        String key = type + "|" + target;
        String id = ids.get(key);
        if (id == null) {
            id = "rId" + (ids.size() + 1);
            ids.put(key, id);
            xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(Ooxml.REL).append(type)
                    .append("\" Target=\"").append(Ooxml.esc(target)).append('"')
                    .append(external ? " TargetMode=\"External\"/>" : "/>");
        }
        return id;
    }

    String xml() {
        return Ooxml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + xml + "</Relationships>";
    }
}
