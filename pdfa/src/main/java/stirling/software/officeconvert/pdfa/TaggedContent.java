package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;

final class TaggedContent {

    private static final Set<String> PAINT = Set.of("f", "F", "f*", "B", "B*", "b", "b*", "S", "s");

    private static final Set<String> PATH = Set.of("m", "l", "c", "v", "y", "re", "h");

    private static final Set<String> TEXT = Set.of("Tj", "TJ", "'", "\"");

    private static final COSName ARTIFACT = COSName.getPDFName("Artifact");

    private static final int MAX_FORM_DEPTH = 12;

    int text;

    int images;

    int artifacts;

    int orphans;

    final Set<Integer> mcids = new HashSet<>();

    private final Set<Integer> referenced;

    private final Map<COSDictionary, Set<Integer>> byOwner;

    private final Map<COSStream, Boolean> formsWithMarks;

    private final int depth;

    TaggedContent(Set<Integer> referenced, Map<COSDictionary, Set<Integer>> byOwner) {
        this(referenced, byOwner, new IdentityHashMap<>(), 0);
    }

    private TaggedContent(Set<Integer> referenced, Map<COSDictionary, Set<Integer>> byOwner,
            Map<COSStream, Boolean> formsWithMarks, int depth) {
        this.referenced = referenced;
        this.byOwner = byOwner;
        this.formsWithMarks = formsWithMarks;
        this.depth = depth;
    }

    List<Object> scan(List<Object> tokens, COSDictionary resources) {
        List<Object> out = new ArrayList<>(tokens.size() + 16);
        Deque<Boolean> marked = new ArrayDeque<>();
        int pathStart = -1;
        int start = 0;
        boolean changed = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            List<Object> operation = tokens.subList(start, i + 1);
            start = i + 1;
            String name = op.getName();
            boolean tagged = !marked.isEmpty() && marked.peek();
            switch (name) {
                case "BMC" -> marked.push(tagged || ARTIFACT.equals(operand(operation, 0)));
                case "BDC" -> {
                    Integer mcid = tagged ? null : mcid(operation, resources);
                    if (mcid != null && referenced != null && !referenced.contains(mcid)) {
                        if (!paints(tokens, i, resources)) {
                            out.add(ARTIFACT);
                            out.add(Operator.getOperator("BMC"));
                            marked.push(true);
                            artifacts++;
                            changed = true;
                            continue;
                        }
                        orphans++;
                    }
                    marked.push(tagged || mcid != null || ARTIFACT.equals(operand(operation, 0)));
                }
                case "EMC" -> {
                    if (!marked.isEmpty()) {
                        marked.pop();
                    }
                }
                default -> {
                }
            }
            if (PATH.contains(name) && pathStart < 0) {
                pathStart = out.size();
            }
            if (tagged || name.equals("BMC") || name.equals("BDC")) {
                if (PAINT.contains(name) || "n".equals(name)) {
                    pathStart = -1;
                }
                out.addAll(operation);
                continue;
            }
            if (PAINT.contains(name) || "sh".equals(name)) {
                int at = PAINT.contains(name) && pathStart >= 0 ? pathStart : out.size();
                out.add(at, ARTIFACT);
                out.add(at + 1, Operator.getOperator("BMC"));
                out.addAll(operation);
                out.add(Operator.getOperator("EMC"));
                artifacts++;
                changed = true;
                pathStart = -1;
                continue;
            }
            if ("n".equals(name)) {
                pathStart = -1;
            } else if (TEXT.contains(name)) {
                text++;
            } else if ("BI".equals(name)) {
                images++;
            } else if ("Do".equals(name) && operand(operation, 0) instanceof COSName x) {
                COSDictionary xo = TransparencyScan.lookup(resources, COSName.XOBJECT, x);
                if (xo instanceof COSStream s && COSName.IMAGE.equals(s.getCOSName(COSName.SUBTYPE))) {
                    images++;
                } else if (xo instanceof COSStream s && untaggedForm(s, resources)) {
                    out.add(ARTIFACT);
                    out.add(Operator.getOperator("BMC"));
                    out.addAll(operation);
                    out.add(Operator.getOperator("EMC"));
                    artifacts++;
                    changed = true;
                    continue;
                }
            }
            out.addAll(operation);
        }
        out.addAll(tokens.subList(start, tokens.size()));
        return changed ? out : null;
    }

    private boolean untaggedForm(COSStream form, COSDictionary parentResources) {
        if (form.containsKey(COSName.STRUCT_PARENT)) {
            return false;
        }
        Boolean known = formsWithMarks.get(form);
        if (known != null) {
            return known;
        }
        formsWithMarks.put(form, Boolean.FALSE);
        if (depth >= MAX_FORM_DEPTH) {
            return false;
        }
        boolean structured = form.containsKey(COSName.STRUCT_PARENTS) || byOwner.containsKey(form);
        COSDictionary res = ContentGraph.dict(form.getDictionaryObject(COSName.RESOURCES));
        TaggedContent inner = new TaggedContent(structured ? byOwner.getOrDefault(form, Set.of()) : null, byOwner,
                formsWithMarks, depth + 1);
        List<Object> rewritten;
        try {
            rewritten = inner.scan(ContentTokens.parse(List.of(form)), res == null ? parentResources : res);
        } catch (IOException e) {
            return false;
        }
        if (structured) {
            if (rewritten != null) {
                try {
                    ContentTokens.write(form, rewritten);
                } catch (IOException e) {
                    text += inner.text + 1;
                    return false;
                }
            }
            text += inner.text;
            images += inner.images;
            orphans += inner.orphans;
            artifacts += inner.artifacts;
            return false;
        }
        boolean onlyPaths = inner.text == 0 && inner.images == 0;
        text += inner.text;
        images += inner.images;
        formsWithMarks.put(form, onlyPaths);
        return onlyPaths;
    }

    private Integer mcid(List<Object> operation, COSDictionary resources) {
        COSBase props = operand(operation, 1);
        COSDictionary d = props instanceof COSName n ? TransparencyScan.lookup(resources, COSName.PROPERTIES, n)
                : ContentGraph.dict(props);
        if (d != null && d.getDictionaryObject(COSName.MCID) instanceof COSNumber mcid) {
            mcids.add(mcid.intValue());
            return mcid.intValue();
        }
        return null;
    }

    private boolean paints(List<Object> tokens, int bdc, COSDictionary resources) {
        int depth = 0;
        for (int i = bdc + 1; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            String name = op.getName();
            if ("BMC".equals(name) || "BDC".equals(name)) {
                depth++;
            } else if ("EMC".equals(name) && depth-- == 0) {
                return false;
            } else if (TEXT.contains(name) || "BI".equals(name)) {
                return true;
            } else if ("Do".equals(name) && tokens.get(i - 1) instanceof COSName x) {
                COSDictionary xo = TransparencyScan.lookup(resources, COSName.XOBJECT, x);
                if (!(xo instanceof COSStream s) || !COSName.FORM.equals(s.getCOSName(COSName.SUBTYPE))
                        || !untaggedForm(s, resources)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static COSBase operand(List<Object> operation, int i) {
        return i < operation.size() - 1 && operation.get(i) instanceof COSBase b ? b : null;
    }
}
