package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;

final class FloatLayout {

    // cell is the outline of the table cell an object is laid out in, or null
    record Frame(float pageW, float pageH, float left, float right, float top, float bottom, float colX, float colW,
            Rectangle2D.Float cell) {

        Frame(float pageW, float pageH, float left, float right, float top, float bottom, float colX, float colW) {
            this(pageW, pageH, left, right, top, bottom, colX, colW, null);
        }
    }

    private FloatLayout() {}

    static Rectangle2D.Float position(Drawing d, Frame f, float paraX, float paraTop) {
        resize(d, f);
        float w = d.width;
        float h = d.height;
        float x0;
        float x1;
        Rectangle2D.Float cell = f.cell();
        // Word places an object laid out in a table cell from the cell where the page or margin is asked for: across
        // from the cell's text area, down from the top of the cell
        String hRel = cell != null && (d.hRel.equals("page") || d.hRel.equals("margin")) ? "column" : d.hRel;
        String vRel = cell != null && (d.vRel.equals("page") || d.vRel.equals("margin")) ? "cell" : d.vRel;
        switch (hRel) {
            case "page" -> {
                x0 = 0;
                x1 = f.pageW();
            }
            case "margin" -> {
                x0 = f.left();
                x1 = f.pageW() - f.right();
            }
            case "leftMargin", "insideMargin" -> {
                x0 = 0;
                x1 = f.left();
            }
            case "rightMargin", "outsideMargin" -> {
                x0 = f.pageW() - f.right();
                x1 = f.pageW();
            }
            case "character" -> {
                x0 = f.colX() + paraX;
                x1 = x0 + f.colW();
            }
            default -> {
                x0 = f.colX();
                x1 = f.colX() + f.colW();
            }
        }
        float x;
        if (d.hAlign != null) {
            x = switch (d.hAlign) {
                case "center" -> x0 + (x1 - x0 - w) / 2;
                case "right", "outside" -> x1 - w;
                default -> x0;
            };
        } else if (d.hPct != null) {
            x = x0 + d.hPct * (x1 - x0);
        } else {
            x = x0 + d.hOffset;
        }
        float y0;
        float y1;
        switch (vRel) {
            case "cell" -> {
                y0 = cell.y;
                y1 = cell.y + cell.height;
            }
            case "page" -> {
                y0 = 0;
                y1 = f.pageH();
            }
            case "margin" -> {
                y0 = f.top();
                y1 = f.pageH() - f.bottom();
            }
            case "topMargin", "insideMargin" -> {
                y0 = 0;
                y1 = f.top();
            }
            case "bottomMargin", "outsideMargin" -> {
                y0 = f.pageH() - f.bottom();
                y1 = f.pageH();
            }
            default -> {
                y0 = paraTop;
                y1 = paraTop + h;
            }
        }
        float y;
        if (d.vAlign != null) {
            y = switch (d.vAlign) {
                case "center" -> y0 + (y1 - y0 - h) / 2;
                case "bottom", "outside" -> y1 - h;
                default -> y0;
            };
        } else if (d.vPct != null) {
            y = y0 + d.vPct * (y1 - y0);
        } else {
            y = y0 + d.vOffset;
        }
        return new Rectangle2D.Float(x, y, w, h);
    }

    private static boolean grows(Drawing d) {
        return d.graphic instanceof Drawing.Shape s && s.text() != null && s.text().grow();
    }

    // Word sizes an object with a relative width or height from the page it lands on, not the saved extent
    static void resize(Drawing d, Frame f) {
        if (d.pctWidth > 0) {
            float ref = switch (d.pctWidthFrom == null ? "margin" : d.pctWidthFrom) {
                case "page" -> f.pageW();
                case "leftMargin", "insideMargin" -> f.left();
                case "rightMargin", "outsideMargin" -> f.right();
                default -> f.pageW() - f.left() - f.right();
            };
            d.width = Math.max(0, ref * d.pctWidth);
        }
        // A text box that resizes to fit its text keeps the height Word saved, whatever its relative height says
        if (d.pctHeight > 0 && !grows(d)) {
            float ref = switch (d.pctHeightFrom == null ? "margin" : d.pctHeightFrom) {
                case "page" -> f.pageH();
                case "topMargin", "insideMargin" -> f.top();
                case "bottomMargin", "outsideMargin" -> f.bottom();
                default -> f.pageH() - f.top() - f.bottom();
            };
            d.height = Math.max(0, ref * d.pctHeight);
        }
    }
}
