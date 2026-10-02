package stirling.software.officeconvert.topdf.xlsb;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.topdf.xls.Xml;

/** Conditional formats whose rules a reader can apply without evaluating a formula: comparisons with constants, text,
 * blanks, top and bottom ranks and averages. Rules built on expressions are left out. */
final class CondFormats {

    private static final int MAX_FORMATS = 10_000;

    private static final String[] OPERATORS = {null, "between", "notBetween", "equal", "notEqual", "greaterThan",
        "lessThan", "greaterThanOrEqual", "lessThanOrEqual"};

    private static final String[] TEXT = {"containsText", "notContainsText", "beginsWith", "endsWith"};

    private final StringBuilder xml = new StringBuilder();

    private String sqref;

    private final List<String> rules = new ArrayList<>();

    private int formats;

    int skipped;

    void record(int type, Data d) {
        switch (type) {
            case Ids.CONDFORMATTING -> begin(d);
            case Ids.CFRULE -> rule(d);
            case Ids.CONDFORMATTING_END -> end();
            default -> {
            }
        }
    }

    private void begin(Data d) {
        d.skip(8);
        int n = d.i32();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n && i < 1024 && d.remaining() >= 16; i++) {
            String r = Refs.range(d);
            if (r != null) {
                b.append(b.isEmpty() ? "" : " ").append(r);
            }
        }
        sqref = b.isEmpty() ? null : b.toString();
        rules.clear();
    }

    private void end() {
        if (sqref != null && !rules.isEmpty() && formats++ < MAX_FORMATS) {
            xml.append("<conditionalFormatting sqref=\"").append(sqref).append("\">");
            rules.forEach(xml::append);
            xml.append("</conditionalFormatting>");
        }
        sqref = null;
        rules.clear();
    }

    private void rule(Data d) {
        int type = d.i32();
        int sub = d.i32();
        int dxf = d.i32();
        int priority = d.i32();
        int operator = d.i32();
        d.skip(8);
        int flags = d.u16();
        d.skip(12);
        String text = d.string();
        List<String> formulas = new ArrayList<>();
        for (int i = 0; i < 3 && d.remaining() >= 8; i++) {
            formulas.add(Formula.constant(d));
        }
        String head = " dxfId=\"" + dxf + "\" priority=\"" + Math.max(1, priority) + "\""
                + ((flags & 0x0002) != 0 ? " stopIfTrue=\"1\"" : "");
        String rule = switch (type) {
            case 1 -> cellIs(head, operator, formulas);
            case 2 -> expressionKind(sub, head, operator, flags, text);
            case 5 -> "<cfRule type=\"top10\"" + head + " rank=\"" + Math.max(1, operator) + "\""
                    + ((flags & 0x0008) != 0 ? " bottom=\"1\"" : "") + ((flags & 0x0010) != 0 ? " percent=\"1\"" : "")
                    + "/>";
            default -> null;
        };
        if (rule == null || dxf < 0) {
            skipped++;
            return;
        }
        rules.add(rule);
    }

    private static String cellIs(String head, int operator, List<String> formulas) {
        String op = operator > 0 && operator < OPERATORS.length ? OPERATORS[operator] : null;
        int need = operator == 1 || operator == 2 ? 2 : 1;
        if (op == null || formulas.size() < need || formulas.subList(0, need).contains(null)) {
            return null;
        }
        StringBuilder b = new StringBuilder("<cfRule type=\"cellIs\"").append(head).append(" operator=\"").append(op)
                .append("\">");
        for (int i = 0; i < need; i++) {
            b.append("<formula>").append(Xml.attr(formulas.get(i))).append("</formula>");
        }
        return b.append("</cfRule>").toString();
    }

    private static String expressionKind(int sub, String head, int operator, int flags, String text) {
        return switch (sub) {
            case 8 -> operator >= 0 && operator < TEXT.length
                    ? "<cfRule type=\"" + TEXT[operator] + "\"" + head + " operator=\""
                            + new String[] {"containsText", "notContains", "beginsWith", "endsWith"}[operator]
                            + "\" text=\"" + Xml.attr(text) + "\"/>"
                    : null;
            case 9 -> "<cfRule type=\"containsBlanks\"" + head + "/>";
            case 10 -> "<cfRule type=\"notContainsBlanks\"" + head + "/>";
            case 25, 26, 29, 30 -> "<cfRule type=\"aboveAverage\"" + head
                    + (sub == 26 || sub == 30 ? " aboveAverage=\"0\"" : "")
                    + (sub >= 29 ? " equalAverage=\"1\"" : "") + (operator > 0 ? " stdDev=\"" + operator + "\"" : "")
                    + "/>";
            default -> null;
        };
    }

    String xml() {
        return xml.toString();
    }
}
