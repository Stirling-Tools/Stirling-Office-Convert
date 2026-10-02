package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentFixer {

    private static final Set<String> OPERATORS = Set.of("b", "B", "b*", "B*", "BDC", "BI", "BMC", "BT", "BX", "c",
            "cm", "CS", "cs", "d", "d0", "d1", "Do", "DP", "EMC", "ET", "EX", "f", "F", "f*", "G", "g", "gs", "h",
            "i", "j", "J", "K", "k", "l", "m", "M", "MP", "n", "q", "Q", "re", "RG", "rg", "ri", "s", "S", "SC",
            "sc", "SCN", "scn", "sh", "T*", "Tc", "Td", "TD", "Tf", "Tj", "TJ", "TL", "Tm", "Tr", "Ts", "Tw", "Tz", "v",
            "w", "W", "W*", "y", "'", "\"");

    private static final Set<String> INTENTS = Set.of("RelativeColorimetric", "AbsoluteColorimetric", "Perceptual",
            "Saturation");

    private ContentFixer() {}

    private record Shared(List<COSStream> streams, COSDictionary resources) {}

    static void run(ContentGraph graph, PdfALevel level, Report report, FontUsage usage, DeviceColours colours)
            throws IOException {
        ExplicitResources.run(graph);
        Map<Shared, COSBase> pages = new HashMap<>();
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            Shared shared = new Shared(n.streams(), n.resources());
            COSBase known = n.kind() == ContentGraph.Kind.PAGE ? pages.get(shared) : null;
            if (known != null) {
                n.owner().setItem(COSName.CONTENTS, known);
                continue;
            }
            ContentTokens.Salvaged parsed;
            try {
                parsed = ContentTokens.salvage(n.streams());
            } catch (IOException e) {
                Decoded.rethrowFatal(e);
                PdfFiles.stopIfInterrupted();
                colours.unknown();
                continue;
            }
            List<Object> tokens = parsed.tokens();
            List<Object> out = new ArrayList<>(tokens.size());
            boolean changed = !parsed.complete();
            if (changed) {
                colours.unknown();
                report.warn("Removed a broken token at the end of a content stream");
            }
            int start = 0;
            for (int i = 0; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator op)) {
                    continue;
                }
                List<Object> operation = tokens.subList(start, i + 1);
                start = i + 1;
                boolean copied = false;
                boolean limits = false;
                for (int k = 0; k < operation.size() - 1; k++) {
                    colours.operand(operation.get(k));
                    limits |= ContentLimits.candidate(operation.get(k), level);
                    if (operation.get(k) instanceof COSBase b) {
                        COSBase f = Limits.number(b, level);
                        if (f != b) {
                            if (!copied) {
                                operation = new ArrayList<>(operation);
                                copied = true;
                            }
                            operation.set(k, f);
                            changed = true;
                        }
                    }
                }
                String name = op.getName();
                if (level.tagged() && ("BDC".equals(name) || "DP".equals(name))) {
                    for (Object operand : operation) {
                        if (operand instanceof COSDictionary properties) {
                            Tagging.language(properties, report);
                        }
                    }
                }
                colours.operator(name);
                if (!OPERATORS.contains(name)) {
                    changed = true;
                    report.warn("Removed content operators that PDF/A does not allow (" + clip(name) + ")");
                    continue;
                }
                if ("ri".equals(name) && operation.size() == 2
                        && !(operation.get(0) instanceof COSName intent && INTENTS.contains(intent.getName()))) {
                    operation = new ArrayList<>(operation);
                    operation.set(0, COSName.getPDFName("RelativeColorimetric"));
                    changed = true;
                }
                if ("BI".equals(name)) {
                    colours.inlineImage(op.getImageParameters());
                    InlineImages.Outcome o = InlineImages.fix(op);
                    if (o == InlineImages.Outcome.UNREADABLE) {
                        changed = true;
                        report.warn("Removed inline images without data, or whose filter PDF/A does not allow and "
                                + "that could not be decoded");
                        continue;
                    }
                    if (o == InlineImages.Outcome.REENCODED) {
                        changed = true;
                        report.warn("Decoded inline images whose filter PDF/A does not allow");
                    }
                }
                if ("BI".equals(name) && inlineImage(op.getImageParameters())) {
                    changed = true;
                }
                List<Object> limited = limits || "BI".equals(name) ? ContentLimits.fix(operation, level) : null;
                if (limited != null) {
                    operation = limited;
                    changed = true;
                }
                out.addAll(operation);
            }
            List<Object> result = changed ? out : tokens;
            usage.scan(result, n.resources(), n.owner());
            if (Nesting.depth(result) > Nesting.MAX_DEPTH) {
                COSDictionary res = resources(n);
                if (res != null) {
                    result = Nesting.flatten(result, res, level, report);
                    changed = true;
                }
            }
            ContentTokens.replace(n, result);
            if (n.kind() == ContentGraph.Kind.PAGE) {
                pages.put(shared, n.owner().getItem(COSName.CONTENTS));
            }
        }
        usage.resolve();
    }

    private static COSDictionary resources(ContentGraph.Node n) {
        if (n.resources() != null) {
            n.resources().setDirect(false);
            return n.resources();
        }
        if (n.kind() != ContentGraph.Kind.PAGE) {
            return null;
        }
        COSDictionary res = new COSDictionary();
        n.owner().setItem(COSName.RESOURCES, res);
        return res;
    }

    private static boolean inlineImage(COSDictionary params) {
        if (params == null) {
            return false;
        }
        boolean changed = false;
        for (COSName k : List.of(COSName.I, COSName.INTERPOLATE)) {
            if (params.getDictionaryObject(k) == COSBoolean.TRUE) {
                params.setItem(k, COSBoolean.FALSE);
                changed = true;
            }
        }
        if (params.getDictionaryObject(COSName.INTENT) instanceof COSName n && !INTENTS.contains(n.getName())) {
            params.removeItem(COSName.INTENT);
            changed = true;
        }
        return changed;
    }

    private static String clip(String s) {
        String c = s.replaceAll("[^!-~]", "?");
        return c.length() > 12 ? c.substring(0, 12) : c;
    }
}
