package stirling.software.officeconvert.topdf.markdown;

import org.commonmark.node.CustomNode;

final class HeadingId extends CustomNode {

    private final String id;

    HeadingId(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }
}
