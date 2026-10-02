package stirling.software.officeconvert.topdf.xlsb;

import stirling.software.officeconvert.topdf.testing.Fixtures;
import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class XlsbHostile {

    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    private XlsbHostile() {}

    public static byte[] build(NoNetwork net) {
        byte[] xlsb = new XlsbFixture().beginData().row(0).text(0, net.url("cell"), 0).endData().build();
        return Fixtures.edit(xlsb)
                .relationship("/xl/workbook.bin", "rIdExt", REL + "externalLinkPath", net.uncPath("book.xlsb"), true)
                .relationship("/xl/worksheets/sheet1.bin", "rIdLink", REL + "hyperlink", net.url("link"), true)
                .relationship("/xl/worksheets/sheet1.bin", "rIdImg", REL + "image", net.url("linked.png"), true)
                .bytes();
    }
}
