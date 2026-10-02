package stirling.software.officeconvert.topdf.wordml;

import java.util.LinkedHashMap;
import java.util.Map;

final class Part {

    private static final String OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    final String name;

    final String contentType;

    final String root;

    final StringBuilder xml = new StringBuilder(1 << 12);

    private final StringBuilder rels = new StringBuilder();

    private final Map<String, String> targets = new LinkedHashMap<>();

    private int next = 1;

    boolean busy;

    Part(String name, String contentType, String root) {
        this.name = name;
        this.contentType = contentType;
        this.root = root;
    }

    String relate(String type, String target) {
        return targets.computeIfAbsent(type + ' ' + target, k -> {
            String id = "rId" + next++;
            rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(OFFICE).append(type)
                    .append("\" Target=\"").append(Esc.attr(target)).append("\"/>");
            return id;
        });
    }

    String link(String url) {
        return targets.computeIfAbsent("link " + url, k -> {
            String id = "rId" + next++;
            rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(OFFICE).append("hyperlink")
                    .append("\" Target=\"").append(Esc.attr(url)).append("\" TargetMode=\"External\"/>");
            return id;
        });
    }

    boolean hasRels() {
        return next > 1;
    }

    String relsXml() {
        return Esc.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels
                + "</Relationships>";
    }

    Part append(String s) {
        xml.append(s);
        return this;
    }
}
