package stirling.software.officeconvert.topdf.xls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.poi.hssf.record.CFRuleBase;
import org.apache.poi.hssf.record.aggregates.CFRecordsAggregate;
import org.apache.poi.hssf.record.aggregates.ConditionalFormattingTable;
import org.apache.poi.hssf.record.cf.BorderFormatting;
import org.apache.poi.hssf.record.cf.FontFormatting;
import org.apache.poi.hssf.record.cf.PatternFormatting;
import org.apache.poi.hssf.usermodel.HSSFColorScaleFormatting;
import org.apache.poi.hssf.usermodel.HSSFConditionalFormattingRule;
import org.apache.poi.hssf.usermodel.HSSFDataBarFormatting;
import org.apache.poi.hssf.usermodel.HSSFFormulaEvaluator;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.formula.ConditionalFormattingEvaluator;
import org.apache.poi.ss.formula.EvaluationConditionalFormatRule;
import org.apache.poi.ss.formula.ptg.BoolPtg;
import org.apache.poi.ss.formula.ptg.IntPtg;
import org.apache.poi.ss.formula.ptg.NumberPtg;
import org.apache.poi.ss.formula.ptg.ParenthesisPtg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.StringPtg;
import org.apache.poi.ss.formula.ptg.UnaryMinusPtg;
import org.apache.poi.ss.formula.ptg.UnaryPlusPtg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ConditionType;
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold;
import org.apache.poi.ss.usermodel.ExtendedColor;
import org.apache.poi.ss.util.CellRangeAddress;

final class CondFormats {

    static final int MAX_RULES = 10_000;

    static final long MAX_EVALUATED_CELLS = 200_000;

    private static final int MAX_REFS = 4_000;

    private static final String[] OPERATORS = {null, "between", "notBetween", "equal", "notEqual", "greaterThan",
        "lessThan", "greaterThanOrEqual", "lessThanOrEqual"};

    private static final String[] BORDERS = {"none", "thin", "medium", "dashed", "dotted", "thick", "double", "hair",
        "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot", "mediumDashDotDot", "slantDashDot"};

    private static final String[] PATTERNS = {"none", "solid", "mediumGray", "darkGray", "lightGray",
        "darkHorizontal", "darkVertical", "darkDown", "darkUp", "darkGrid", "darkTrellis", "lightHorizontal",
        "lightVertical", "lightDown", "lightUp", "lightGrid", "lightTrellis", "gray125", "gray0625"};

    private final Map<String, Integer> dxfs = new LinkedHashMap<>();

    private int rules;

    private int priority;

    private long evaluatedCells;

    private ConditionalFormattingEvaluator evaluator;

    private List<EvaluationConditionalFormatRule> rulesOfSheet;

    int skipped;

    String sheet(HSSFSheet sheet) {
        ConditionalFormattingTable table = sheet.getSheet().getConditionalFormattingTable();
        StringBuilder b = new StringBuilder();
        StringBuilder extra = new StringBuilder();
        rulesOfSheet = null;
        for (int i = 0; i < table.size() && rules < MAX_RULES; i++) {
            CFRecordsAggregate cf = table.get(i);
            String sqref = sqref(cf.getHeader().getCellRanges());
            if (sqref == null) {
                continue;
            }
            List<String> out = new ArrayList<>();
            for (int k = 0; k < cf.getNumberOfRules() && rules < MAX_RULES; k++) {
                String rule = rule(cf.getRule(k));
                if (rule == null) {
                    rule = scale(sheet, i, k);
                }
                if (rule == null) {
                    String evaluated = evaluated(sheet, i, k, cf.getRule(k));
                    if (evaluated == null) {
                        skipped++;
                    } else {
                        extra.append(evaluated);
                        rules++;
                    }
                } else {
                    out.add(rule);
                    rules++;
                }
            }
            if (!out.isEmpty()) {
                b.append("<conditionalFormatting sqref=\"").append(sqref).append("\">");
                out.forEach(b::append);
                b.append("</conditionalFormatting>");
            }
        }
        return b.append(extra).toString();
    }

    private String scale(HSSFSheet sheet, int format, int index) {
        HSSFConditionalFormattingRule rule;
        try {
            rule = sheet.getSheetConditionalFormatting().getConditionalFormattingAt(format).getRule(index);
        } catch (RuntimeException e) {
            return null;
        }
        if (rule.getConditionType() == ConditionType.COLOR_SCALE && rule.getColorScaleFormatting() != null) {
            HSSFColorScaleFormatting c = rule.getColorScaleFormatting();
            ConditionalFormattingThreshold[] at = c.getThresholds();
            ExtendedColor[] colors = c.getColors();
            if (at == null || colors == null || at.length < 2 || at.length != colors.length || at.length > 3) {
                return null;
            }
            StringBuilder b = new StringBuilder("<cfRule type=\"colorScale\" priority=\"").append(++priority)
                    .append("\"><colorScale>");
            for (ConditionalFormattingThreshold t : at) {
                b.append(cfvo(t));
            }
            for (ExtendedColor color : colors) {
                b.append(color(color));
            }
            return b.append("</colorScale></cfRule>").toString();
        }
        if (rule.getConditionType() == ConditionType.DATA_BAR && rule.getDataBarFormatting() != null) {
            HSSFDataBarFormatting d = rule.getDataBarFormatting();
            return "<cfRule type=\"dataBar\" priority=\"" + ++priority + "\"><dataBar minLength=\""
                    + Math.max(0, Math.min(100, d.getWidthMin())) + "\" maxLength=\""
                    + Math.max(0, Math.min(100, d.getWidthMax() == 0 ? 90 : d.getWidthMax())) + "\">"
                    + cfvo(d.getMinThreshold()) + cfvo(d.getMaxThreshold()) + color(d.getColor())
                    + "</dataBar></cfRule>";
        }
        return null;
    }

    private static String cfvo(ConditionalFormattingThreshold t) {
        if (t == null) {
            return "<cfvo type=\"min\"/>";
        }
        String type = switch (t.getRangeType()) {
            case NUMBER -> "num";
            case MAX -> "max";
            case PERCENT -> "percent";
            case PERCENTILE -> "percentile";
            case FORMULA -> "formula";
            default -> "min";
        };
        Double v = t.getValue();
        return "<cfvo type=\"" + type + "\"" + (v != null && Double.isFinite(v) ? " val=\"" + SheetPart.number(v)
                + "\"" : "") + "/>";
    }

    private static String color(ExtendedColor c) {
        if (c == null) {
            return "<color rgb=\"FF638EC6\"/>";
        }
        String tint = c.getTint() == 0 ? "" : " tint=\"" + c.getTint() + "\"";
        if (c.isThemed()) {
            return "<color theme=\"" + c.getTheme() + "\"" + tint + "/>";
        }
        byte[] rgb = c.getRGB();
        if (c.isRGB() && rgb != null && rgb.length >= 3) {
            return String.format("<color rgb=\"FF%02X%02X%02X\"%s/>", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF,
                    tint);
        }
        if (c.isIndexed() && c.getIndex() >= 0 && c.getIndex() < 64) {
            return "<color indexed=\"" + c.getIndex() + "\"" + tint + "/>";
        }
        return "<color rgb=\"FF638EC6\"/>";
    }

    private String evaluated(HSSFSheet sheet, int format, int index, CFRuleBase r) {
        String dxf = dxf(r);
        if (dxf == null || evaluatedCells >= MAX_EVALUATED_CELLS) {
            return null;
        }
        try {
            if (evaluator == null) {
                HSSFFormulaEvaluator formulas = new HSSFFormulaEvaluator(sheet.getWorkbook());
                formulas.setIgnoreMissingWorkbooks(true);
                evaluator = new ConditionalFormattingEvaluator(sheet.getWorkbook(), formulas);
            }
            if (rulesOfSheet == null) {
                rulesOfSheet = evaluator.getFormatRulesForSheet(sheet);
            }
            EvaluationConditionalFormatRule rule = null;
            for (EvaluationConditionalFormatRule e : rulesOfSheet) {
                if (e.getFormattingIndex() == format && e.getRuleIndex() == index) {
                    rule = e;
                }
            }
            if (rule == null) {
                return null;
            }
            long area = 0;
            for (CellRangeAddress a : rule.getRegions()) {
                area += (long) a.getNumberOfCells();
            }
            evaluatedCells += area;
            if (evaluatedCells > MAX_EVALUATED_CELLS) {
                return null;
            }
            String sqref = cells(evaluator.getMatchingCells(rule));
            if (sqref == null) {
                return "";
            }
            int id = dxfs.computeIfAbsent(dxf, k -> dxfs.size());
            return "<conditionalFormatting sqref=\"" + sqref + "\"><cfRule type=\"expression\" dxfId=\"" + id
                    + "\" priority=\"" + ++priority + "\" stopIfTrue=\"1\"><formula>TRUE</formula></cfRule>"
                    + "</conditionalFormatting>";
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static String cells(List<Cell> cells) {
        TreeMap<Integer, TreeSet<Integer>> rows = new TreeMap<>();
        for (Cell c : cells) {
            rows.computeIfAbsent(c.getRowIndex(), k -> new TreeSet<>()).add(c.getColumnIndex());
        }
        StringBuilder b = new StringBuilder();
        int refs = 0;
        for (Map.Entry<Integer, TreeSet<Integer>> row : rows.entrySet()) {
            int start = -2;
            int end = -2;
            for (int col : row.getValue()) {
                if (col != end + 1) {
                    refs += range(b, row.getKey(), start, end);
                    start = col;
                }
                end = col;
            }
            refs += range(b, row.getKey(), start, end);
            if (refs > MAX_REFS) {
                break;
            }
        }
        return b.isEmpty() ? null : b.toString();
    }

    private static int range(StringBuilder b, int row, int start, int end) {
        if (start < 0) {
            return 0;
        }
        if (!b.isEmpty()) {
            b.append(' ');
        }
        b.append(new CellRangeAddress(row, row, start, end).formatAsString());
        return 1;
    }

    String styles() {
        if (dxfs.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("<dxfs count=\"").append(dxfs.size()).append("\">");
        dxfs.keySet().forEach(b::append);
        return b.append("</dxfs>").toString();
    }

    private static String sqref(CellRangeAddress[] ranges) {
        StringBuilder b = new StringBuilder();
        for (CellRangeAddress r : ranges == null ? new CellRangeAddress[0] : ranges) {
            if (r == null || r.getFirstRow() < 0 || r.getFirstColumn() < 0) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(r.formatAsString());
        }
        return b.isEmpty() ? null : b.toString();
    }

    private String rule(CFRuleBase r) {
        if (r.getConditionType() != CFRuleBase.CONDITION_TYPE_CELL_VALUE_IS) {
            return null;
        }
        int op = r.getComparisonOperation();
        if (op <= 0 || op >= OPERATORS.length) {
            return null;
        }
        String a = constant(r.getParsedExpression1());
        String b = op <= 2 ? constant(r.getParsedExpression2()) : "";
        if (a == null || b == null) {
            return null;
        }
        String dxf = dxf(r);
        if (dxf == null) {
            return null;
        }
        int id = dxfs.computeIfAbsent(dxf, k -> dxfs.size());
        StringBuilder x = new StringBuilder("<cfRule type=\"cellIs\" dxfId=\"").append(id).append("\" priority=\"")
                .append(++priority).append("\" stopIfTrue=\"1\" operator=\"").append(OPERATORS[op])
                .append("\"><formula>").append(Xml.text(a)).append("</formula>");
        if (op <= 2) {
            x.append("<formula>").append(Xml.text(b)).append("</formula>");
        }
        return x.append("</cfRule>").toString();
    }

    static String constant(Ptg[] ptgs) {
        if (ptgs == null) {
            return null;
        }
        Ptg value = null;
        boolean negative = false;
        for (Ptg p : ptgs) {
            if (p instanceof UnaryMinusPtg) {
                negative = !negative;
            } else if (p instanceof UnaryPlusPtg || p instanceof ParenthesisPtg) {
                continue;
            } else if (value == null) {
                value = p;
            } else {
                return null;
            }
        }
        String text = switch (value) {
            case IntPtg i -> Integer.toString(i.getValue());
            case NumberPtg n -> Double.isFinite(n.getValue()) ? SheetPart.number(n.getValue()) : null;
            case BoolPtg bool -> negative ? null : bool.getValue() ? "1" : "0";
            case StringPtg s -> negative ? null : "\"" + s.getValue().replace("\"", "\"\"") + "\"";
            case null, default -> null;
        };
        return text == null || !negative ? text : text.startsWith("-") ? text.substring(1) : "-" + text;
    }

    private static String dxf(CFRuleBase r) {
        StringBuilder b = new StringBuilder("<dxf>");
        if (r.containsFontFormattingBlock()) {
            b.append(font(r.getFontFormatting()));
        }
        if (r.containsPatternFormattingBlock()) {
            b.append(fill(r, r.getPatternFormatting()));
        }
        if (r.containsBorderFormattingBlock()) {
            b.append(border(r, r.getBorderFormatting()));
        }
        return b.length() == 5 ? null : b.append("</dxf>").toString();
    }

    private static String font(FontFormatting f) {
        StringBuilder b = new StringBuilder();
        if (f.isFontWeightModified() && f.isBold()) {
            b.append("<b/>");
        }
        if (f.isFontStyleModified() && f.isItalic()) {
            b.append("<i/>");
        }
        if (f.isFontCancellationModified() && f.isStruckout()) {
            b.append("<strike/>");
        }
        int color = f.getFontColorIndex();
        if (color >= 0 && color < 64) {
            b.append("<color indexed=\"").append(color).append("\"/>");
        }
        String u = f.isUnderlineTypeModified() ? StylesPart.underline((byte) f.getUnderlineType()) : null;
        if (u != null) {
            b.append("<u val=\"").append(u).append("\"/>");
        }
        return b.isEmpty() ? "" : "<font>" + b + "</font>";
    }

    private static String fill(CFRuleBase r, PatternFormatting p) {
        int pattern = r.isPatternStyleModified() ? p.getFillPattern() : 1;
        if (pattern <= 0 || pattern >= PATTERNS.length) {
            return pattern == 0 && r.isPatternStyleModified()
                    ? "<fill><patternFill patternType=\"none\"/></fill>" : "";
        }
        String fore = r.isPatternColorModified() ? indexed("fgColor", p.getFillForegroundColor()) : "";
        String back = r.isPatternBackgroundColorModified() ? indexed("bgColor", p.getFillBackgroundColor()) : "";
        if (fore.isEmpty() && back.isEmpty()) {
            return "";
        }
        return "<fill><patternFill patternType=\"" + PATTERNS[pattern] + "\">" + fore + back + "</patternFill></fill>";
    }

    private static String border(CFRuleBase r, BorderFormatting f) {
        String left = r.isLeftBorderModified() ? side("left", f.getBorderLeft(), f.getLeftBorderColor()) : "";
        String right = r.isRightBorderModified() ? side("right", f.getBorderRight(), f.getRightBorderColor()) : "";
        String top = r.isTopBorderModified() ? side("top", f.getBorderTop(), f.getTopBorderColor()) : "";
        String bottom = r.isBottomBorderModified() ? side("bottom", f.getBorderBottom(), f.getBottomBorderColor())
                : "";
        if (left.isEmpty() && right.isEmpty() && top.isEmpty() && bottom.isEmpty()) {
            return "";
        }
        return "<border>" + left + right + top + bottom + "</border>";
    }

    private static String side(String tag, int style, int color) {
        if (style <= 0 || style >= BORDERS.length) {
            return "";
        }
        return "<" + tag + " style=\"" + BORDERS[style] + "\">" + indexed("color", color) + "</" + tag + ">";
    }

    private static String indexed(String tag, int index) {
        return index >= 0 && index < 64 ? "<" + tag + " indexed=\"" + index + "\"/>" : "";
    }
}
