package stirling.software.officeconvert.topdf.odf;

final class Rels {

    private final StringBuilder xml = new StringBuilder();

    private int next = 1;

    String add(String type, String target) {
        String id = "rId" + next++;
        xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(Xml.REL_TYPE).append(type)
                .append("\" Target=\"").append(Xml.esc(target)).append("\"/>");
        return id;
    }

    String external(String type, String target) {
        String id = "rId" + next++;
        xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(Xml.REL_TYPE).append(type)
                .append("\" Target=\"").append(Xml.esc(target)).append("\" TargetMode=\"External\"/>");
        return id;
    }

    boolean isEmpty() {
        return next == 1;
    }

    String xml() {
        return Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + xml + "</Relationships>";
    }
}
