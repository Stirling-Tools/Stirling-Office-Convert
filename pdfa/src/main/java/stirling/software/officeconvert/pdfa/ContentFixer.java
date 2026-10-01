package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentFixer {

    private static final Set<String> OPERATORS = Set.of("b", "B", "b*", "B*", "BDC", "BI", "BMC", "BT", "BX", "c",
            "cm", "CS", "cs", "d", "d0", "d1", "Do", "DP", "EI", "EMC", "ET", "EX", "f", "F", "f*", "G", "g", "gs", "h",
            "i", "ID", "j", "J", "K", "k", "l", "m", "M", "MP", "n", "q", "Q", "re", "RG", "rg", "ri", "s", "S", "SC",
            "sc", "SCN", "scn", "sh", "T*", "Tc", "Td", "TD", "Tf", "Tj", "TJ", "TL", "Tm", "Tr", "Ts", "Tw", "Tz", "v",
            "w", "W", "W*", "y", "'", "\"");

    private static final Set<String> INTENTS = Set.of("RelativeColorimetric", "AbsoluteColorimetric", "Perceptual",
            "Saturation");

    private ContentFixer() {}

    static void run(ContentGraph graph, PdfALevel level, Report report, FontUsage usage) throws IOException {
        for (ContentGraph.Node n : graph.nodes()) {
            PdfFiles.stopIfInterrupted();
            List<Object> tokens;
            try {
                tokens = ContentTokens.parse(n.streams());
            } catch (IOException e) {
                PdfFiles.stopIfInterrupted();
                continue;
            }
            List<Object> out = new ArrayList<>(tokens.size());
            boolean changed = false;
            int start = 0;
            for (int i = 0; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator op)) {
                    continue;
                }
                List<Object> operation = new ArrayList<>(tokens.subList(start, i + 1));
                start = i + 1;
                for (int k = 0; k < operation.size() - 1; k++) {
                    if (operation.get(k) instanceof COSBase b) {
                        COSBase f = Limits.number(b, level);
                        if (f != b) {
                            operation.set(k, f);
                            changed = true;
                        }
                    }
                }
                String name = op.getName();
                if (!OPERATORS.contains(name)) {
                    changed = true;
                    report.warn("Removed content operators that PDF/A does not allow (" + clip(name) + ")");
                    continue;
                }
                if ("ri".equals(name) && operation.size() == 2
                        && !(operation.get(0) instanceof COSName intent && INTENTS.contains(intent.getName()))) {
                    operation.set(0, COSName.getPDFName("RelativeColorimetric"));
                    changed = true;
                }
                if ("BI".equals(name) && inlineImage(op.getImageParameters())) {
                    changed = true;
                }
                out.addAll(operation);
            }
            usage.scan(changed ? out : tokens, n.resources());
            if (changed) {
                COSStream target = n.streams().get(0);
                ContentTokens.write(target, out);
                if (n.kind() == ContentGraph.Kind.PAGE && n.streams().size() > 1) {
                    n.owner().setItem(COSName.CONTENTS, target);
                }
            }
        }
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
