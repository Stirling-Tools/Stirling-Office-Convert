package stirling.software.officeconvert.topdf.markdown;

import java.util.ArrayDeque;
import java.util.Deque;

import org.commonmark.node.Block;
import org.commonmark.node.Node;
import org.commonmark.parser.PostProcessor;

final class Flatten implements PostProcessor {

    static final int MAX_INLINE_DEPTH = 32;

    private record Visit(Node node, int depth) {}

    @Override
    public Node process(Node document) {
        Deque<Visit> todo = new ArrayDeque<>();
        todo.push(new Visit(document, 0));
        while (!todo.isEmpty()) {
            Visit v = todo.pop();
            Node n = v.node();
            int depth = v.depth();
            if (!(n instanceof Block) && n.getFirstChild() != null) {
                if (depth + 1 > MAX_INLINE_DEPTH) {
                    Node child = n.getFirstChild();
                    while (child != null) {
                        Node next = child.getNext();
                        n.insertBefore(child);
                        todo.push(new Visit(child, depth));
                        child = next;
                    }
                    n.unlink();
                    continue;
                }
                depth++;
            }
            for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
                todo.push(new Visit(child, depth));
            }
        }
        return document;
    }
}
