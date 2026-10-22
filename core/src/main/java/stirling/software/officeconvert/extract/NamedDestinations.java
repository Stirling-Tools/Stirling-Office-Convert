package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDestinationDictionary;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;

final class NamedDestinations {

    static final long MAX_STEPS = 20_000_000;

    private final PDDocumentCatalog catalog;
    private final Map<String, PDPageDestination> found = new HashMap<>();
    private final Map<COSDictionary, Node> nodes = new IdentityHashMap<>();
    private long steps;

    NamedDestinations(PDDocument document) {
        catalog = document.getDocumentCatalog();
    }

    PDPageDestination find(String name) throws IOException {
        if (found.containsKey(name)) {
            return found.get(name);
        }
        PDPageDestination dest = fromTree(name);
        if (dest == null) {
            PDDocumentNameDestinationDictionary dests = catalog.getDests();
            if (dests != null) {
                dest = (PDPageDestination) dests.getDestination(name);
            }
        }
        found.put(name, dest);
        return dest;
    }

    private PDPageDestination fromTree(String name) throws IOException {
        PDDocumentNameDictionary names = catalog.getNames();
        PDNameTreeNode<PDPageDestination> at = names == null ? null : names.getDests();
        Set<COSDictionary> path = Collections.newSetFromMap(new IdentityHashMap<>());
        while (at != null) {
            if (!path.add(at.getCOSObject())) {
                throw new IOException("The named destination tree loops");
            }
            Node node = node(at);
            if (node.names != null) {
                return node.names.get(name);
            }
            at = node.next(name, this);
        }
        return null;
    }

    private Node node(PDNameTreeNode<PDPageDestination> tree) throws IOException {
        Node node = nodes.get(tree.getCOSObject());
        if (node == null) {
            node = new Node(tree);
            nodes.put(tree.getCOSObject(), node);
        }
        node.rethrow();
        return node;
    }

    private void step() throws IOException {
        if (++steps > MAX_STEPS) {
            throw new IOException("The named destination tree is too large to search");
        }
    }

    private static final class Node {

        private Map<String, PDPageDestination> names;
        private List<Kid> kids;
        private IOException ioFailure;
        private RuntimeException failure;

        Node(PDNameTreeNode<PDPageDestination> tree) {
            try {
                names = tree.getNames();
                if (names == null) {
                    List<PDNameTreeNode<PDPageDestination>> children = tree.getKids();
                    if (children != null) {
                        kids = new ArrayList<>(children.size());
                        for (PDNameTreeNode<PDPageDestination> child : children) {
                            kids.add(new Kid(child, child.getLowerLimit(), child.getUpperLimit()));
                        }
                    }
                }
            } catch (IOException e) {
                ioFailure = e;
            } catch (RuntimeException e) {
                failure = e;
            }
        }

        void rethrow() throws IOException {
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (failure != null) {
                throw failure;
            }
        }

        PDNameTreeNode<PDPageDestination> next(String name, NamedDestinations owner) throws IOException {
            if (kids == null) {
                return null;
            }
            for (Kid kid : kids) {
                owner.step();
                if (kid.holds(name)) {
                    return kid.node;
                }
            }
            return null;
        }
    }

    private record Kid(PDNameTreeNode<PDPageDestination> node, String lower, String upper) {

        boolean holds(String name) {
            return upper == null || lower == null || upper.compareTo(lower) < 0
                    || (lower.compareTo(name) <= 0 && upper.compareTo(name) >= 0);
        }
    }
}
