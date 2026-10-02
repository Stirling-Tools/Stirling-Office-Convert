package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;

import stirling.software.officeconvert.extract.PdfFiles;

final class VisibilityExpression {

    private final Predicate<COSBase> visible;

    private final Set<COSArray> path = Collections.newSetFromMap(new IdentityHashMap<>());

    private int visits;

    private VisibilityExpression(Predicate<COSBase> visible) {
        this.visible = visible;
    }

    static boolean visible(COSBase expression, Predicate<COSBase> visible) throws IOException {
        return new VisibilityExpression(visible).evaluate(expression);
    }

    private boolean evaluate(COSBase expression) throws IOException {
        PdfFiles.stopIfInterrupted();
        if (++visits > 10_000 || path.size() >= 64) {
            throw new IOException("An optional content visibility expression is too large");
        }
        COSDictionary group = ContentGraph.dict(expression);
        if (group != null && COSName.OCG.equals(group.getCOSName(COSName.TYPE))) {
            return visible.test(group);
        }
        COSArray array = ContentGraph.array(expression);
        if (array == null || array.size() < 2 || !(array.getObject(0) instanceof COSName operator)
                || !Set.of("Not", "And", "Or").contains(operator.getName()) || !path.add(array)) {
            throw new IOException("An optional content visibility expression is invalid or recursive");
        }
        try {
            if ("Not".equals(operator.getName())) {
                if (array.size() != 2) {
                    throw new IOException("An optional content Not expression must have one operand");
                }
                return !evaluate(array.getObject(1));
            }
            boolean and = "And".equals(operator.getName());
            boolean result = and;
            for (int i = 1; i < array.size(); i++) {
                boolean item = evaluate(array.getObject(i));
                result = and ? result && item : result || item;
            }
            return result;
        } finally {
            path.remove(array);
        }
    }
}
