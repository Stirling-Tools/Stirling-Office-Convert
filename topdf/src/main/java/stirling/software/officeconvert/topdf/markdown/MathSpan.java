package stirling.software.officeconvert.topdf.markdown;

import org.commonmark.node.CustomNode;

final class MathSpan extends CustomNode {

    private final String tex;

    private final boolean display;

    MathSpan(String tex, boolean display) {
        this.tex = tex;
        this.display = display;
    }

    String tex() {
        return tex;
    }

    boolean display() {
        return display;
    }
}
