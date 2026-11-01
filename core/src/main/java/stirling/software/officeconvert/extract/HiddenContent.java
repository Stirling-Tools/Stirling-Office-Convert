package stirling.software.officeconvert.extract;

import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;

public final class HiddenContent {

    private static final Set<String> PATH_PAINTING = Set.of(OperatorName.STROKE_PATH, OperatorName.CLOSE_AND_STROKE,
            OperatorName.FILL_NON_ZERO, OperatorName.LEGACY_FILL_NON_ZERO, OperatorName.FILL_EVEN_ODD,
            OperatorName.FILL_NON_ZERO_AND_STROKE, OperatorName.FILL_EVEN_ODD_AND_STROKE,
            OperatorName.CLOSE_FILL_NON_ZERO_AND_STROKE, OperatorName.CLOSE_FILL_EVEN_ODD_AND_STROKE);
    private static final Set<String> PAINTING = Set.of(OperatorName.SHADING_FILL, OperatorName.DRAW_OBJECT,
            OperatorName.BEGIN_INLINE_IMAGE);
    private static final Operator END_PATH = Operator.getOperator(OperatorName.ENDPATH);

    private final OptionalContent layers;
    private int depth;

    HiddenContent(OptionalContent layers) {
        this.layers = layers;
    }

    public static HiddenContent of(PDDocument document) {
        return new HiddenContent(OptionalContent.of(document));
    }

    public Operator painted(Operator operator, List<COSBase> operands, PDResources resources) {
        operator(operator.getName(), operands, resources);
        if (depth == 0) {
            return operator;
        }
        if (PATH_PAINTING.contains(operator.getName())) {
            return END_PATH;
        }
        return PAINTING.contains(operator.getName()) ? null : operator;
    }

    public void reset() {
        depth = 0;
    }

    public boolean active() {
        return depth > 0;
    }

    void operator(String name, List<COSBase> operands, PDResources resources) {
        switch (name) {
            case OperatorName.BEGIN_MARKED_CONTENT_SEQ -> {
                if (depth > 0) {
                    depth++;
                } else if (operands.size() >= 2 && COSName.OC.equals(operands.get(0))
                        && layers.hidden(properties(operands.get(1), resources))) {
                    depth = 1;
                }
            }
            case OperatorName.BEGIN_MARKED_CONTENT -> {
                if (depth > 0) {
                    depth++;
                }
            }
            case OperatorName.END_MARKED_CONTENT -> {
                if (depth > 0) {
                    depth--;
                }
            }
            default -> { }
        }
    }

    public boolean hidden(PDXObject object) {
        return object != null && layers.hidden(object.getCOSObject().getDictionaryObject(COSName.OC));
    }

    private static COSBase properties(COSBase operand, PDResources resources) {
        if (operand instanceof COSDictionary inline) {
            return inline;
        }
        if (!(operand instanceof COSName name) || resources == null
                || !(resources.getCOSObject().getDictionaryObject(COSName.PROPERTIES) instanceof COSDictionary named)) {
            return null;
        }
        return named.getDictionaryObject(name);
    }
}
