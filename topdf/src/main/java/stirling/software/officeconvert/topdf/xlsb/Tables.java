package stirling.software.officeconvert.topdf.xlsb;

import java.io.IOException;
import java.io.InputStream;

import stirling.software.officeconvert.topdf.xls.Xml;

/** A table (ListObject) .bin as table XML: its range, header and totals rows, columns and style, which is what
 * drawing its banding needs. */
final class Tables {

    private static final int MAX_COLUMNS = 16_384;

    private Tables() {}

    static String xml(InputStream in) throws IOException {
        Records r = new Records(in);
        String ref = null;
        int id = 1;
        int header = 1;
        int totals = 0;
        String name = "Table1";
        String display = "Table1";
        StringBuilder columns = new StringBuilder();
        int count = 0;
        String style = "";
        while (r.next()) {
            Data d = r.data();
            switch (r.type()) {
                case Ids.TABLE -> {
                    ref = Refs.range(d);
                    d.i32();
                    id = Math.max(1, d.i32());
                    header = d.i32();
                    totals = d.i32();
                    d.skip(32);
                    name = d.string();
                    display = d.string();
                }
                case Ids.TABLECOLUMN -> {
                    int field = d.i32();
                    d.skip(24);
                    String col = d.string();
                    if (count < MAX_COLUMNS) {
                        count++;
                        columns.append("<tableColumn id=\"").append(Math.max(1, field)).append("\" name=\"")
                                .append(Xml.attr(col.isEmpty() ? "Column" + count : col)).append("\"/>");
                    }
                }
                case Ids.TABLESTYLEINFO -> {
                    int f = d.u16();
                    String s = d.string();
                    style = "<tableStyleInfo" + (s.isEmpty() ? "" : " name=\"" + Xml.attr(s) + "\"")
                            + " showFirstColumn=\"" + bit(f, 0x01) + "\" showLastColumn=\"" + bit(f, 0x02)
                            + "\" showRowStripes=\"" + bit(f, 0x04) + "\" showColumnStripes=\"" + bit(f, 0x08) + "\"/>";
                }
                default -> {
                }
            }
        }
        if (ref == null) {
            return null;
        }
        return Xml.HEAD + "<table xmlns=\"" + Xml.MAIN + "\" id=\"" + id + "\" name=\"" + Xml.attr(name)
                + "\" displayName=\"" + Xml.attr(display.isEmpty() ? name : display) + "\" ref=\"" + ref
                + "\" headerRowCount=\"" + Math.max(0, Math.min(1, header)) + "\" totalsRowCount=\""
                + Math.max(0, Math.min(1, totals)) + "\"><tableColumns count=\"" + count + "\">" + columns
                + "</tableColumns>" + style + "</table>";
    }

    private static int bit(int flags, int bit) {
        return (flags & bit) != 0 ? 1 : 0;
    }

    static String chartSheet(InputStream in) throws IOException {
        Records r = new Records(in);
        String margins = "";
        String setup = "";
        String headerFooter = "";
        String drawing = "";
        while (r.next()) {
            Data d = r.data();
            switch (r.type()) {
                case Ids.PAGEMARGINS -> margins = PageXml.margins(d);
                case Ids.CHARTPAGESETUP -> setup = PageXml.chartPageSetup(d);
                case Ids.HEADERFOOTER -> headerFooter = PageXml.headerFooter(d);
                case Ids.DRAWING -> drawing = "<drawing r:id=\"" + Xml.attr(d.string()) + "\"/>";
                default -> {
                }
            }
        }
        return Xml.HEAD + "<chartsheet xmlns=\"" + Xml.MAIN + "\" xmlns:r=\"" + Xml.REL + "\"><sheetViews><sheetView"
                + " workbookViewId=\"0\"/></sheetViews>" + margins + setup + headerFooter + drawing + "</chartsheet>";
    }
}
