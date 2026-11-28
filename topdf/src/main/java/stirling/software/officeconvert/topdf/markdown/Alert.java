package stirling.software.officeconvert.topdf.markdown;

import org.commonmark.node.CustomBlock;

final class Alert extends CustomBlock {

    private final String kind;

    Alert(String kind) {
        this.kind = kind;
    }

    String kind() {
        return kind;
    }
}
