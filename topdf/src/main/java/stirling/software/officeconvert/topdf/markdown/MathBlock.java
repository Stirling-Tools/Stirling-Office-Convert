package stirling.software.officeconvert.topdf.markdown;

import org.commonmark.node.CustomBlock;

final class MathBlock extends CustomBlock {

    private String tex;

    private boolean closed;

    MathBlock(String tex, boolean closed) {
        this.tex = tex;
        this.closed = closed;
    }

    String tex() {
        return tex;
    }

    void tex(String tex) {
        this.tex = tex;
    }

    boolean closed() {
        return closed;
    }

    void closed(boolean closed) {
        this.closed = closed;
    }
}
