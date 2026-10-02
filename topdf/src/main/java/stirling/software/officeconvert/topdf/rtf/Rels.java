package stirling.software.officeconvert.topdf.rtf;

import java.util.HashMap;
import java.util.Map;

final class Rels {

    static final String NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final StringBuilder xml = new StringBuilder();

    private final Map<String, String> seen = new HashMap<>();

    private final String prefix;

    private int next = 1;

    Rels(String prefix) {
        this.prefix = prefix;
    }

    String add(String type, String target, boolean external) {
        String key = type + '\u0000' + target + '\u0000' + external;
        String have = seen.get(key);
        if (have != null) {
            return have;
        }
        String id = prefix + next++;
        xml.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(NS).append('/').append(type)
                .append("\" Target=\"").append(Xml.attr(target)).append('"');
        if (external) {
            xml.append(" TargetMode=\"External\"");
        }
        xml.append("/>");
        seen.put(key, id);
        return id;
    }

    boolean empty() {
        return xml.isEmpty();
    }

    String xml() {
        return Xml.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + xml
                + "</Relationships>";
    }
}
