package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;

final class TextObjects {

    private static final Operator BEGIN = Operator.getOperator(OperatorName.BEGIN_TEXT);

    private static final Set<String> INSIDE_ONLY = Set.of(OperatorName.SHOW_TEXT, OperatorName.SHOW_TEXT_ADJUSTED,
            OperatorName.SHOW_TEXT_LINE, OperatorName.SHOW_TEXT_LINE_AND_SPACE, OperatorName.MOVE_TEXT,
            OperatorName.MOVE_TEXT_SET_LEADING, OperatorName.SET_MATRIX, OperatorName.NEXT_LINE);

    private static final Set<String> OPERATORS = Set.of("b", "B", "b*", "B*", "BDC", "BI", "BMC", "BT", "BX", "c",
            "cm", "CS", "cs", "d", "d0", "d1", "Do", "DP", "EI", "EMC", "ET", "EX", "f", "F", "f*", "G", "g", "gs",
            "h", "i", "ID", "j", "J", "K", "k", "l", "m", "M", "MP", "n", "q", "Q", "re", "RG", "rg", "ri", "s", "S",
            "SC", "sc", "SCN", "scn", "sh", "T*", "Tc", "Td", "TD", "Tf", "TJ", "Tj", "TL", "Tm", "Tr", "Ts", "Tw",
            "Tz", "v", "w", "W", "W*", "y", "'", "\"");

    private static final int MAX_TOKENS = 1_000_000;

    private Boolean garbled;

    void reset() {
        garbled = null;
    }

    Operator before(PDFStreamEngine engine, Operator operator) {
        if (engine.getTextMatrix() != null || !INSIDE_ONLY.contains(operator.getName())) {
            return null;
        }
        if (garbled == null) {
            garbled = garbled(engine.getCurrentPage());
        }
        return garbled ? null : BEGIN;
    }

    private static boolean garbled(PDPage page) {
        if (page == null) {
            return true;
        }
        try {
            PDFStreamParser parser = new PDFStreamParser(page);
            int tokens = 0;
            for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
                if (token instanceof Operator op && !OPERATORS.contains(op.getName()) || ++tokens > MAX_TOKENS) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
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
