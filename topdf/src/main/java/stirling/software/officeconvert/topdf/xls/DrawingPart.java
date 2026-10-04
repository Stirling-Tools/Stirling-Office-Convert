package stirling.software.officeconvert.topdf.xls;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.hssf.usermodel.HSSFAnchor;
import org.apache.poi.hssf.usermodel.HSSFChildAnchor;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFCombobox;
import org.apache.poi.hssf.usermodel.HSSFComment;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFPalette;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFPictureData;
import org.apache.poi.hssf.usermodel.HSSFRichTextString;
import org.apache.poi.hssf.usermodel.HSSFShape;
import org.apache.poi.hssf.usermodel.HSSFShapeGroup;
import org.apache.poi.hssf.usermodel.HSSFSimpleShape;
import org.apache.poi.hssf.usermodel.HSSFTextbox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Font;

// Pictures, text boxes, plain shapes and chart frames of a sheet's drawing as a DrawingML part; controls are left out
final class DrawingPart {

    static final int MAX_SHAPES = 2000;

    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    private static final int HOST_CONTROL = 201;

    private final HSSFWorkbook wb;

    private final SheetPart geometry;

    private final Map<Integer, String> media;

    private final List<String> rels = new ArrayList<>();

    private final StringBuilder xml = new StringBuilder();

    private final HSSFPalette palette;

    private int id = 1;

    int skipped;

    int pict;

    private int shapes;

    DrawingPart(HSSFWorkbook wb, SheetPart geometry, Map<Integer, String> media) {
        this.wb = wb;
        this.geometry = geometry;
        this.media = media;
        this.palette = wb.getCustomPalette();
    }

    interface Media {
        String add(int index, HSSFPictureData data);
    }

    boolean read(HSSFPatriarch patriarch, Media store) {
        for (HSSFShape s : patriarch.getChildren()) {
            if (shapes >= MAX_SHAPES) {
                break;
            }
            if (!(s.getAnchor() instanceof HSSFClientAnchor anchor)) {
                continue;
            }
            String body = guarded(s, null, store);
            if (body != null) {
                xml.append("<xdr:twoCellAnchor editAs=\"oneCell\">").append(anchor(anchor)).append(body)
                        .append("<xdr:clientData/></xdr:twoCellAnchor>");
            }
        }
        return xml.length() > 0;
    }

    void chart(int[] a, String rel) {
        HSSFClientAnchor anchor = new HSSFClientAnchor(fit(a[1], 1023), fit(a[3], 255), fit(a[5], 1023),
                fit(a[7], 255), (short) fit(a[0], 255), fit(a[2], 65535), (short) fit(a[4], 255), fit(a[6], 65535));
        xml.append("<xdr:twoCellAnchor editAs=\"oneCell\">").append(anchor(anchor)).append(frame(id++, rel))
                .append("<xdr:clientData/></xdr:twoCellAnchor>");
    }

    private static int fit(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    static String absoluteChart(String rel) {
        return Xml.HEAD + "<xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\" xmlns:r=\"" + Xml.REL
                + "\"><xdr:absoluteAnchor><xdr:pos x=\"0\" y=\"0\"/><xdr:ext cx=\"9293679\" cy=\"6068786\"/>"
                + frame(1, rel) + "<xdr:clientData/></xdr:absoluteAnchor></xdr:wsDr>";
    }

    private static String frame(int id, String rel) {
        return "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"" + id + "\" name=\"Chart\"/>"
                + "<xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\""
                + " cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"" + CHART + "\"><c:chart xmlns:c=\"" + CHART
                + "\" r:id=\"" + rel + "\"/></a:graphicData></a:graphic></xdr:graphicFrame>";
    }

    List<String> relationships() {
        return rels;
    }

    String xml() {
        return Xml.HEAD + "<xdr:wsDr xmlns:xdr=\"" + XDR + "\" xmlns:a=\"" + A + "\" xmlns:r=\"" + Xml.REL + "\">" + xml
                + "</xdr:wsDr>";
    }

    private String anchor(HSSFClientAnchor a) {
        int c1 = Math.min(a.getCol1(), a.getCol2());
        int c2 = Math.max(a.getCol1(), a.getCol2());
        int r1 = Math.min(a.getRow1(), a.getRow2());
        int r2 = Math.max(a.getRow1(), a.getRow2());
        int dx1 = a.getCol1() <= a.getCol2() ? a.getDx1() : a.getDx2();
        int dx2 = a.getCol1() <= a.getCol2() ? a.getDx2() : a.getDx1();
        int dy1 = a.getRow1() <= a.getRow2() ? a.getDy1() : a.getDy2();
        int dy2 = a.getRow1() <= a.getRow2() ? a.getDy2() : a.getDy1();
        return marker("from", c1, dx1, r1, dy1) + marker("to", c2, dx2, r2, dy2);
    }

    // BIFF anchors give offsets in 1/1024 of the column and 1/256 of the row
    private String marker(String tag, int col, int dx, int row, int dy) {
        col = Math.max(0, Math.min(255, col));
        row = Math.max(0, Math.min(65535, row));
        long colOff = Math.round(Math.max(0, Math.min(1024, dx)) / 1024.0 * geometry.columnPx(col) * 9525);
        long rowOff = Math.round(Math.max(0, Math.min(256, dy)) / 256.0 * geometry.rowPt(row) * 12700);
        return "<xdr:" + tag + "><xdr:col>" + col + "</xdr:col><xdr:colOff>" + colOff + "</xdr:colOff><xdr:row>"
                + row + "</xdr:row><xdr:rowOff>" + rowOff + "</xdr:rowOff></xdr:" + tag + ">";
    }

    private String shape(HSSFShape s, HSSFShapeGroup parent, Media store) {
        if (++shapes > MAX_SHAPES) {
            return null;
        }
        String xfrm = xfrm(s, parent);
        if (s instanceof HSSFShapeGroup g) {
            StringBuilder b = new StringBuilder();
            for (HSSFShape c : g.getChildren()) {
                String child = guarded(c, g, store);
                if (child != null) {
                    b.append(child);
                }
            }
            if (b.isEmpty()) {
                return null;
            }
            long w = Math.max(1, (long) g.getX2() - g.getX1());
            long h = Math.max(1, (long) g.getY2() - g.getY1());
            String box = "<a:off x=\"" + g.getX1() + "\" y=\"" + g.getY1() + "\"/><a:ext cx=\"" + w + "\" cy=\"" + h
                    + "\"/>";
            String place = parent == null ? box : xfrm.substring(xfrm.indexOf("<a:off"), xfrm.indexOf("</a:xfrm>"));
            return "<xdr:grpSp><xdr:nvGrpSpPr><xdr:cNvPr id=\"" + id++ + "\" name=\"Group\"/><xdr:cNvGrpSpPr/>"
                    + "</xdr:nvGrpSpPr><xdr:grpSpPr><a:xfrm>" + place + box.replace("a:off", "a:chOff")
                            .replace("a:ext", "a:chExt") + "</a:xfrm></xdr:grpSpPr>" + b + "</xdr:grpSp>";
        }
        if (s instanceof HSSFPicture pic) {
            return picture(pic, xfrm, store);
        }
        if (s instanceof HSSFComment || s instanceof HSSFCombobox) {
            return null;
        }
        if (s instanceof HSSFSimpleShape simple) {
            return simple(simple, xfrm);
        }
        skipped++;
        return null;
    }

    // One shape POI cannot read is left out, not the whole drawing
    private String guarded(HSSFShape s, HSSFShapeGroup parent, Media store) {
        try {
            return shape(s, parent, store);
        } catch (DrawingPart.Stop e) {
            throw e;
        } catch (RuntimeException e) {
            skipped++;
            return null;
        }
    }

    static final class Stop extends RuntimeException {
        Stop(Throwable cause) {
            super(cause);
        }
    }

    private String xfrm(HSSFShape s, HSSFShapeGroup parent) {
        StringBuilder b = new StringBuilder("<a:xfrm");
        if (s.getOptRecord() != null) {
            int rot = s.getRotationDegree();
            if (rot != 0) {
                b.append(" rot=\"").append((long) rot * 60000).append('"');
            }
            if (s.isFlipHorizontal()) {
                b.append(" flipH=\"1\"");
            }
            if (s.isFlipVertical()) {
                b.append(" flipV=\"1\"");
            }
        }
        b.append('>');
        HSSFAnchor a = s.getAnchor();
        if (parent != null && a instanceof HSSFChildAnchor c) {
            long w = Math.abs((long) c.getDx2() - c.getDx1());
            long h = Math.abs((long) c.getDy2() - c.getDy1());
            long x = Math.min(c.getDx1(), c.getDx2());
            long y = Math.min(c.getDy1(), c.getDy2());
            if (s.getOptRecord() != null && sideways(s.getRotationDegree())) {
                x += (w - h) / 2;
                y += (h - w) / 2;
                long t = w;
                w = h;
                h = t;
            }
            b.append("<a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(w)
                    .append("\" cy=\"").append(h).append("\"/>");
        } else {
            b.append("<a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>");
        }
        return b.append("</a:xfrm>").toString();
    }

    private static boolean sideways(int degrees) {
        int d = Math.floorMod(degrees, 180);
        return d >= 45 && d < 135;
    }

    // An OLE object is drawn from its stored preview picture only; the object itself is never opened
    private String picture(HSSFPicture pic, String xfrm, Media store) {
        String target;
        try {
            int index = pic.getPictureIndex();
            HSSFPictureData data = index <= 0 ? null : pic.getPictureData();
            if (data == null) {
                return null;
            }
            if ("pict".equals(data.suggestFileExtension())) {
                pict++;
                return null;
            }
            target = media.containsKey(index) ? media.get(index) : store.add(index, data);
        } catch (RuntimeException e) {
            skipped++;
            return null;
        }
        if (target == null) {
            return null;
        }
        rels.add(target);
        String rel = "rId" + rels.size();
        return "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"" + id++ + "\" name=\"Picture\"/><xdr:cNvPicPr/></xdr:nvPicPr>"
                + "<xdr:blipFill><a:blip r:embed=\"" + rel + "\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>"
                + "<xdr:spPr>" + xfrm + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>";
    }

    private String simple(HSSFSimpleShape s, String xfrm) {
        int type = s.getShapeType();
        if (type == HOST_CONTROL) {
            return null;
        }
        String prst = preset(type);
        HSSFRichTextString text = text(s);
        boolean hasText = text != null && !text.getString().isEmpty();
        if (prst == null) {
            if (!hasText) {
                skipped++;
                return null;
            }
            prst = "rect";
        }
        EscherOptRecord opt = s.getOptRecord();
        boolean line = prst.equals("line");
        StringBuilder sp = new StringBuilder("<xdr:spPr>").append(xfrm).append("<a:prstGeom prst=\"").append(prst)
                .append("\"><a:avLst/></a:prstGeom>");
        if (line || !flag(opt, 0x1BF, 0x10, 0x100000, true)) {
            sp.append("<a:noFill/>");
        } else {
            String fill = fill(s, opt);
            sp.append(fill == null ? "<a:noFill/>" : "<a:solidFill><a:srgbClr val=\"" + fill + "\"/></a:solidFill>");
        }
        boolean dashed = !line || opt != null && opt.lookup(0x1CE) != null;
        if (!flag(opt, 0x1FF, 0x8, 0x80000, true) || dashed && s.getLineStyle() == HSSFShape.LINESTYLE_NONE) {
            sp.append("<a:ln><a:noFill/></a:ln>");
        } else {
            sp.append("<a:ln w=\"").append(Math.max(0, s.getLineWidth())).append("\"><a:solidFill><a:srgbClr val=\"")
                    .append(rgb(s.getLineStyleColor(), "000000")).append("\"/></a:solidFill>")
                    .append(arrow(opt, 0x1D0, "headEnd")).append(arrow(opt, 0x1D1, "tailEnd")).append("</a:ln>");
        }
        sp.append("</xdr:spPr>");
        if (line) {
            return "<xdr:cxnSp><xdr:nvCxnSpPr><xdr:cNvPr id=\"" + id++ + "\" name=\"Line\"/><xdr:cNvCxnSpPr/>"
                    + "</xdr:nvCxnSpPr>" + sp + "</xdr:cxnSp>";
        }
        return "<xdr:sp><xdr:nvSpPr><xdr:cNvPr id=\"" + id++ + "\" name=\"Shape\"/><xdr:cNvSpPr/></xdr:nvSpPr>" + sp
                + (hasText ? body(s, text) : "") + "</xdr:sp>";
    }

    private String fill(HSSFSimpleShape s, EscherOptRecord opt) {
        String fore = rgb(s.getFillColor(), "FFFFFF");
        int kind = opt != null && opt.lookup(0x180) instanceof EscherSimpleProperty p ? p.getPropertyValue() : 0;
        if (kind == 2 || kind == 3) {
            return null;
        }
        if (kind != 1) {
            return fore;
        }
        String back = opt.lookup(0x183) instanceof EscherSimpleProperty p ? rgb(p.getPropertyValue(), "FFFFFF")
                : "FFFFFF";
        int a = Integer.parseInt(fore, 16);
        int b = Integer.parseInt(back, 16);
        int mixed = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            mixed |= ((a >> shift & 0xFF) + (b >> shift & 0xFF)) / 2 << shift;
        }
        return String.format("%06X", mixed);
    }

    private static HSSFRichTextString text(HSSFSimpleShape s) {
        try {
            return s.getString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String preset(int type) {
        return switch (type) {
            case 1, 202 -> "rect";
            case 2 -> "roundRect";
            case 3 -> "ellipse";
            case 4 -> "diamond";
            case 5 -> "triangle";
            case 6 -> "rtTriangle";
            case 7 -> "parallelogram";
            case 9 -> "hexagon";
            case 10 -> "octagon";
            case 11 -> "plus";
            case 13 -> "rightArrow";
            case 20, 32 -> "line";
            case 22 -> "can";
            case 23 -> "donut";
            default -> null;
        };
    }

    private String body(HSSFSimpleShape s, HSSFRichTextString text) {
        StringBuilder b = new StringBuilder("<xdr:txBody><a:bodyPr wrap=\"square\"");
        String align = null;
        if (s instanceof HSSFTextbox t) {
            b.append(" lIns=\"").append(Math.max(0, t.getMarginLeft())).append("\" tIns=\"")
                    .append(Math.max(0, t.getMarginTop())).append("\" rIns=\"").append(Math.max(0, t.getMarginRight()))
                    .append("\" bIns=\"").append(Math.max(0, t.getMarginBottom())).append('"');
            b.append(switch (t.getVerticalAlignment()) {
                case 2 -> " anchor=\"ctr\"";
                case 3 -> " anchor=\"b\"";
                default -> " anchor=\"t\"";
            });
            align = switch (t.getHorizontalAlignment()) {
                case 2 -> "ctr";
                case 3 -> "r";
                case 4, 7 -> "just";
                default -> null;
            };
        }
        b.append("/><a:lstStyle/>");
        String all = text.getString();
        int runs = text.numFormattingRuns();
        StringBuilder p = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= all.length(); i++) {
            if (i < all.length() && all.charAt(i) != '\n' && all.charAt(i) != '\r') {
                continue;
            }
            p.append("<a:p>").append(align == null ? "" : "<a:pPr algn=\"" + align + "\"/>");
            int k = start;
            while (k < i) {
                int font = fontAt(text, runs, k);
                int end = i;
                for (int r = 0; r < runs; r++) {
                    int at = text.getIndexOfFormattingRun(r);
                    if (at > k && at < end) {
                        end = at;
                    }
                }
                p.append("<a:r>").append(props(font)).append("<a:t>").append(Xml.attr(all.substring(k, end)))
                        .append("</a:t></a:r>");
                k = end;
            }
            p.append("</a:p>");
            if (i + 1 < all.length() && all.charAt(i) == '\r' && all.charAt(i + 1) == '\n') {
                i++;
            }
            start = i + 1;
        }
        return b.append(p).append("</xdr:txBody>").toString();
    }

    private static int fontAt(HSSFRichTextString text, int runs, int at) {
        int font = 0;
        for (int r = 0; r < runs; r++) {
            if (text.getIndexOfFormattingRun(r) <= at) {
                font = text.getFontOfFormattingRun(r);
            }
        }
        return font;
    }

    private String props(int index) {
        HSSFFont f = StylesPart.fontAt(wb, index);
        if (f == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("<a:rPr lang=\"en-US\" sz=\"")
                .append(Math.round(Math.max(1, Math.min(4000, f.getFontHeight() / 20.0)) * 100)).append('"');
        if (f.getBold()) {
            b.append(" b=\"1\"");
        }
        if (f.getItalic()) {
            b.append(" i=\"1\"");
        }
        byte u = f.getUnderline();
        if (u == Font.U_DOUBLE || u == Font.U_DOUBLE_ACCOUNTING) {
            b.append(" u=\"dbl\"");
        } else if (u != Font.U_NONE) {
            b.append(" u=\"sng\"");
        }
        if (f.getStrikeout()) {
            b.append(" strike=\"sngStrike\"");
        }
        b.append('>');
        int c = f.getColor() & 0xFFFF;
        if (c < 64) {
            b.append("<a:solidFill><a:srgbClr val=\"").append(String.format("%06X", paletteRgb(c, 0)))
                    .append("\"/></a:solidFill>");
        }
        b.append("<a:latin typeface=\"").append(Xml.attr(f.getFontName())).append("\"/></a:rPr>");
        return b.toString();
    }

    private static boolean flag(EscherOptRecord opt, int prop, int bit, int use, boolean fallback) {
        EscherProperty p = opt == null ? null : opt.lookup(prop);
        if (!(p instanceof EscherSimpleProperty s) || (s.getPropertyValue() & use) == 0) {
            return fallback;
        }
        return (s.getPropertyValue() & bit) != 0;
    }

    private static String arrow(EscherOptRecord opt, int prop, String tag) {
        EscherProperty p = opt == null ? null : opt.lookup(prop);
        if (!(p instanceof EscherSimpleProperty s) || s.getPropertyValue() == 0) {
            return "";
        }
        String type = switch (s.getPropertyValue()) {
            case 2 -> "stealth";
            case 3 -> "diamond";
            case 4 -> "oval";
            case 5 -> "arrow";
            default -> "triangle";
        };
        return "<a:" + tag + " type=\"" + type + "\"/>";
    }

    // Escher colours are BGR, or a palette index when the 0x08 flag byte is set
    private String rgb(int v, String fallback) {
        int flags = v >>> 24;
        if (flags == 0) {
            return String.format("%02X%02X%02X", v & 0xFF, v >> 8 & 0xFF, v >> 16 & 0xFF);
        }
        if ((flags & 0x08) != 0 && (v & 0xFFFF) < 64) {
            return String.format("%06X", paletteRgb(v & 0xFFFF, 0));
        }
        return fallback;
    }

    private int paletteRgb(int index, int fallback) {
        int[] basic = {0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF};
        if (index < basic.length) {
            return basic[index];
        }
        return StylesPart.rgb(palette.getColor((short) index), fallback);
    }
}
