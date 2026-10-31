package stirling.software.officeconvert.extract;

import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;

final class TextObjects {

    static final Operator BEGIN = Operator.getOperator(OperatorName.BEGIN_TEXT);

    private static final Set<String> INSIDE_ONLY = Set.of(OperatorName.SHOW_TEXT, OperatorName.SHOW_TEXT_ADJUSTED,
            OperatorName.SHOW_TEXT_LINE, OperatorName.SHOW_TEXT_LINE_AND_SPACE, OperatorName.MOVE_TEXT,
            OperatorName.MOVE_TEXT_SET_LEADING, OperatorName.SET_MATRIX, OperatorName.NEXT_LINE);

    private TextObjects() {}

    static boolean opensImplicitly(PDFStreamEngine engine, Operator operator) {
        return engine.getTextMatrix() == null && INSIDE_ONLY.contains(operator.getName());
    }

    static List<COSBase> sized(Operator operator, List<COSBase> operands) {
        if (OperatorName.SET_FONT_AND_SIZE.equals(operator.getName()) && operands.size() == 2
                && operands.get(0) instanceof COSName && operands.get(1) instanceof COSNumber size
                && size.floatValue() == 0) {
            return List.of(operands.get(0), new COSFloat(1));
        }
        return operands;
    }
}
