package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableStyle;
import org.apache.poi.xslf.usermodel.XSLFTableStyles;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTStyleMatrixReference;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTable;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTablePartStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableStyleCellStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableStyleTextStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.STOnOffStyleType;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class TableStyles {

    enum Edge {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT
    }

    static final class CellStyle {

        Color fill;

        final Stroke[] borders = new Stroke[4];

        Color textColor;

        Boolean bold;

        Boolean italic;

        String font;

        TextStyles.Defaults defaults() {
            return new TextStyles.Defaults(textColor, bold, italic, font);
        }
    }

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private final RenderJob job;

    private Map<String, CTTableStyle> styles;

    TableStyles(RenderJob job) {
        this.job = job;
    }

    private CTTableStyle style(XMLSlideShow ppt, String id) {
        if (styles == null) {
            styles = new HashMap<>();
            try {
                XSLFTableStyles all = ppt.getTableStyles();
                if (all != null) {
                    for (XSLFTableStyle s : all.getStyles()) {
                        if (s.getStyleId() != null) {
                            styles.put(s.getStyleId().strip().toUpperCase(java.util.Locale.ROOT), s.getXmlObject());
                        }
                    }
                }
            } catch (RuntimeException e) {
                job.warn("The table styles could not be read: " + e.getMessage());
            }
        }
        if (id == null) {
            return null;
        }
        CTTableStyle own = styles.get(id.strip().toUpperCase(java.util.Locale.ROOT));
        return own != null ? own : BuiltinTableStyles.get(id);
    }

    CellStyle[][] resolve(XMLSlideShow ppt, XSLFTable table, int rows, int cols) {
        CellStyle[][] out = new CellStyle[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out[r][c] = new CellStyle();
            }
        }
        CTTable ct = table.getCTTable();
        CTTableProperties pr = ct.getTblPr();
        if (pr == null) {
            return out;
        }
        CTTableStyle style = pr.isSetTableStyle() ? pr.getTableStyle() : style(ppt, pr.getTableStyleId());
        if (style == null) {
            return out;
        }
        boolean firstRow = pr.isSetFirstRow() && pr.getFirstRow();
        boolean lastRow = pr.isSetLastRow() && pr.getLastRow();
        boolean firstCol = pr.isSetFirstCol() && pr.getFirstCol();
        boolean lastCol = pr.isSetLastCol() && pr.getLastCol();
        boolean bandRow = pr.isSetBandRow() && pr.getBandRow();
        boolean bandCol = pr.isSetBandCol() && pr.getBandCol();
        XSLFSheet sheet = table.getSheet();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                List<Region> parts = new ArrayList<>();
                parts.add(new Region(style.getWholeTbl(), 0, 0, rows - 1, cols - 1));
                if (bandCol) {
                    int bc = c - (firstCol ? 1 : 0);
                    boolean inBody = bc >= 0 && !(lastCol && c == cols - 1);
                    if (inBody) {
                        parts.add(new Region(bc % 2 == 0 ? style.getBand1V() : style.getBand2V(), 0, c, rows - 1, c));
                    }
                }
                if (bandRow) {
                    int br = r - (firstRow ? 1 : 0);
                    boolean inBody = br >= 0 && !(lastRow && r == rows - 1);
                    if (inBody) {
                        parts.add(new Region(br % 2 == 0 ? style.getBand1H() : style.getBand2H(), r, 0, r, cols - 1));
                    }
                }
                if (lastCol && c == cols - 1) {
                    parts.add(new Region(style.getLastCol(), 0, c, rows - 1, c));
                }
                if (firstCol && c == 0) {
                    parts.add(new Region(style.getFirstCol(), 0, 0, rows - 1, 0));
                }
                if (lastRow && r == rows - 1) {
                    parts.add(new Region(style.getLastRow(), r, 0, r, cols - 1));
                }
                if (firstRow && r == 0) {
                    parts.add(new Region(style.getFirstRow(), 0, 0, 0, cols - 1));
                }
                if (lastRow && r == rows - 1 && lastCol && c == cols - 1) {
                    parts.add(new Region(style.getSeCell(), r, c, r, c));
                }
                if (lastRow && r == rows - 1 && firstCol && c == 0) {
                    parts.add(new Region(style.getSwCell(), r, c, r, c));
                }
                if (firstRow && r == 0 && lastCol && c == cols - 1) {
                    parts.add(new Region(style.getNeCell(), r, c, r, c));
                }
                if (firstRow && r == 0 && firstCol && c == 0) {
                    parts.add(new Region(style.getNwCell(), r, c, r, c));
                }
                CellStyle cs = out[r][c];
                for (Region p : parts) {
                    apply(cs, p, r, c, sheet);
                }
            }
        }
        return out;
    }

    private record Region(CTTablePartStyle style, int top, int left, int bottom, int right) {}

    private void apply(CellStyle cs, Region region, int r, int c, XSLFSheet sheet) {
        CTTablePartStyle ps = region.style();
        if (ps == null) {
            return;
        }
        if (ps.isSetTcTxStyle()) {
            CTTableStyleTextStyle tx = ps.getTcTxStyle();
            if (tx.isSetB()) {
                cs.bold = tx.getB() == STOnOffStyleType.ON;
            }
            if (tx.isSetI()) {
                cs.italic = tx.getI() == STOnOffStyleType.ON;
            }
            Color color = color(tx, sheet);
            if (color != null) {
                cs.textColor = color;
            }
            if (tx.isSetFontRef()) {
                String idx = tx.getFontRef().getIdx() == null ? "minor" : tx.getFontRef().getIdx().toString();
                try {
                    String f = "major".equals(idx) ? sheet.getTheme().getMajorFont() : sheet.getTheme().getMinorFont();
                    if (f != null && !f.isBlank()) {
                        cs.font = f;
                    }
                } catch (RuntimeException e) {
                    cs.font = null;
                }
            } else if (tx.isSetFont() && tx.getFont().getLatin() != null) {
                cs.font = tx.getFont().getLatin().getTypeface();
            }
        }
        if (!ps.isSetTcStyle()) {
            return;
        }
        CTTableStyleCellStyle tc = ps.getTcStyle();
        if (tc.isSetFill()) {
            CTFillProperties f = tc.getFill();
            if (f.isSetNoFill()) {
                cs.fill = null;
            } else if (f.isSetSolidFill()) {
                cs.fill = color(f.getSolidFill(), sheet);
            } else if (f.isSetGradFill() && f.getGradFill().getGsLst() != null
                    && f.getGradFill().getGsLst().sizeOfGsArray() > 0) {
                cs.fill = color(f.getGradFill().getGsLst().getGsArray(0), sheet);
            }
        } else if (tc.isSetFillRef()) {
            CTStyleMatrixReference ref = tc.getFillRef();
            cs.fill = ref.getIdx() == 0 ? null : color(ref, sheet);
        }
        edge(cs, Edge.TOP, tc, r == region.top() ? "top" : "insideH", sheet);
        edge(cs, Edge.BOTTOM, tc, r == region.bottom() ? "bottom" : "insideH", sheet);
        edge(cs, Edge.LEFT, tc, c == region.left() ? "left" : "insideV", sheet);
        edge(cs, Edge.RIGHT, tc, c == region.right() ? "right" : "insideV", sheet);
    }

    private void edge(CellStyle cs, Edge e, CTTableStyleCellStyle tc, String name, XSLFSheet sheet) {
        try {
            Element side = child(child(tc.getDomNode(), "tcBdr"), name);
            if (side == null) {
                return;
            }
            Element ln = child(side, "ln");
            if (ln != null) {
                cs.borders[e.ordinal()] = line(CTLineProperties.Factory.parse(ln, fragment()), sheet);
                return;
            }
            Element lnRef = child(side, "lnRef");
            if (lnRef != null) {
                CTStyleMatrixReference ref = CTStyleMatrixReference.Factory.parse(lnRef, fragment());
                Color color = color(ref, sheet);
                cs.borders[e.ordinal()] = color == null || ref.getIdx() == 0 ? null : Stroke.solid(1, color);
            }
        } catch (XmlException | RuntimeException ex) {
            job.warn("A table border style could not be read: " + ex.getMessage());
        }
    }

    private static XmlOptions fragment() {
        return new XmlOptions().setLoadReplaceDocumentElement(null);
    }

    private static Element child(Node parent, String local) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && A.equals(el.getNamespaceURI()) && local.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    static Stroke line(CTLineProperties ln, XSLFSheet sheet) {
        if (ln == null || ln.isSetNoFill()) {
            return null;
        }
        Color c = ln.isSetSolidFill() ? color(ln.getSolidFill(), sheet) : null;
        if (c == null) {
            return null;
        }
        float w = ln.isSetW() ? ln.getW() / 12_700f : 1;
        if (!(w > 0)) {
            return null;
        }
        Stroke s = Stroke.solid(Math.min(w, 1000), c);
        if (ln.isSetPrstDash() && ln.getPrstDash().getVal() != null) {
            String d = ln.getPrstDash().getVal().toString();
            float u = Math.max(w, 0.25f);
            s = switch (d) {
                case "dash" -> s.dash(0, 4 * u, 3 * u);
                case "sysDash" -> s.dash(0, 3 * u, u);
                case "dot", "sysDot" -> s.dash(0, u, u);
                case "lgDash" -> s.dash(0, 8 * u, 3 * u);
                case "dashDot", "sysDashDot" -> s.dash(0, 4 * u, 3 * u, u, 3 * u);
                default -> s;
            };
        }
        return s;
    }

    static Color color(XmlObject holder, XSLFSheet sheet) {
        if (holder == null) {
            return null;
        }
        try {
            return Paints.color(new XSLFColor(holder, sheet.getTheme(), null, sheet).getColorStyle());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
