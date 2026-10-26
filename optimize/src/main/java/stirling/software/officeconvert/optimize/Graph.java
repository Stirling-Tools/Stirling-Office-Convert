package stirling.software.officeconvert.optimize;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class Graph {

    static final int MAX_DEPTH = 200;

    static final int MAX_NODES = 8_000_000;

    private final List<COSBase> nodes = new ArrayList<>();

    private final Map<COSBase, Integer> ids = new IdentityHashMap<>();

    private final ArrayDeque<Integer> queue = new ArrayDeque<>();

    private Graph() {}

    static Graph walk(List<COSBase> roots) throws InterruptedIOException {
        Graph g = new Graph();
        for (COSBase root : roots) {
            COSBase target = resolve(root);
            if (container(target)) {
                g.add(target);
            }
        }
        ArrayDeque<COSBase> stack = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        int visited = 0;
        while (!g.queue.isEmpty()) {
            if ((++visited & 1023) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            COSBase node = g.nodes.get(g.queue.poll());
            stack.push(node);
            depths.push(0);
            while (!stack.isEmpty()) {
                COSBase b = stack.pop();
                int depth = depths.pop();
                if (b instanceof COSDictionary d) {
                    for (COSBase v : d.getValues()) {
                        g.child(v, depth + 1, stack, depths);
                    }
                } else if (b instanceof COSArray a) {
                    for (int i = 0; i < a.size(); i++) {
                        g.child(a.get(i), depth + 1, stack, depths);
                    }
                }
            }
        }
        return g;
    }

    private void child(COSBase v, int depth, ArrayDeque<COSBase> stack, ArrayDeque<Integer> depths) {
        if (v instanceof COSObject o) {
            COSBase t = o.getObject();
            if (container(t)) {
                add(t);
            }
        } else if (v instanceof COSStream || container(v) && (depth > MAX_DEPTH || ids.containsKey(v))) {
            add(v);
        } else if (container(v)) {
            stack.push(v);
            depths.push(depth);
        }
    }

    private void add(COSBase t) {
        if (ids.containsKey(t)) {
            return;
        }
        if (nodes.size() >= MAX_NODES) {
            throw new IllegalStateException("The PDF has more than " + MAX_NODES + " objects");
        }
        ids.put(t, nodes.size());
        queue.add(nodes.size());
        nodes.add(t);
    }

    static COSBase resolve(COSBase b) {
        return b instanceof COSObject o ? o.getObject() : b;
    }

    static boolean container(COSBase b) {
        return b instanceof COSDictionary || b instanceof COSArray;
    }

    int size() {
        return nodes.size();
    }

    COSBase node(int id) {
        return nodes.get(id);
    }

    int id(COSBase b) {
        Integer i = ids.get(b);
        return i == null ? -1 : i;
    }

    List<COSBase> nodes() {
        return nodes;
    }
}
