package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableStyle;

import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PptxTableTest {

    private static final String MEDIUM_2_ACCENT_6 = "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}";

    @TempDir
    Path dir;

    private static String cell(String text, String rPr) {
        return "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>" + Decks.run(text, rPr)
                + "</a:p></a:txBody><a:tcPr/></a:tc>";
    }

    private static String table(String tblPr, String rPr) {
        return "<p:graphicFrame " + Decks.NS + "><p:nvGraphicFramePr><p:cNvPr id=\"5\" name=\"Table 4\"/>"
                + "<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>"
                + "</p:nvGraphicFramePr><p:xfrm><a:off x=\"914400\" y=\"914400\"/><a:ext cx=\"5486400\" cy=\"1463040\"/>"
                + "</p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">"
                + "<a:tbl>" + tblPr + "<a:tblGrid><a:gridCol w=\"2743200\"/><a:gridCol w=\"2743200\"/></a:tblGrid>"
                + "<a:tr h=\"731520\">" + cell("Head", rPr) + cell("Er", rPr) + "</a:tr>"
                + "<a:tr h=\"731520\">" + cell("Body", rPr) + cell("Text", rPr) + "</a:tr>"
                + "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>";
    }

    @Test
    void builtInStylesAreKnownByTheirIds() {
        CTTableStyle s = BuiltinTableStyles.get(MEDIUM_2_ACCENT_6.toLowerCase());
        assertNotNull(s);
        assertEquals("accent6", s.getFirstRow().getTcStyle().getFill().getSolidFill().getSchemeClr().getVal()
                .toString());
        assertEquals("accent6", s.getWholeTbl().getTcStyle().getFill().getSolidFill().getSchemeClr().getVal()
                .toString());
        CTTableStyle plain = BuiltinTableStyles.get("{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}");
        assertEquals("dk1", plain.getFirstRow().getTcStyle().getFill().getSolidFill().getSchemeClr().getVal()
                .toString());
        CTTableStyle pair = BuiltinTableStyles.get("{0660B408-B3CF-4A94-85FC-2B1E0A45F4A2}");
        assertNotNull(pair);
        assertTrue(pair.toString().contains("accent2"));
        assertNull(BuiltinTableStyles.get("{00000000-0000-0000-0000-000000000000}"));
    }

    @Test
    void aTableUsingABuiltInStyleItsFileLacksIsStyled() throws IOException {
        String tblPr = "<a:tblPr firstRow=\"1\" bandRow=\"1\"><a:tableStyleId>" + MEDIUM_2_ACCENT_6
                + "</a:tableStyleId></a:tblPr>";
        byte[] pptx = Decks.slideXml(table(tblPr, "sz=\"1400\""));
        pptx = Fixtures.edit(pptx).put("ppt/tableStyles.xml", "<a:tblStyleLst xmlns:a=\"" + Decks.A
                + "\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>").bytes();
        BufferedImage img = Decks.convert(dir, "builtin.pptx", pptx).render(0, 72);
        int head = img.getRGB(250, 100) & 0xFFFFFF;
        int body = img.getRGB(250, 170) & 0xFFFFFF;
        assertTrue(head != 0xFFFFFF, Integer.toHexString(head));
        assertTrue(body != 0xFFFFFF && body != head, Integer.toHexString(body));
    }

    @Test
    void tableTextWithoutASizeIgnoresThePresentationDefaultStyle() throws IOException {
        byte[] pptx = Decks.slideXml(table("<a:tblPr/>", ""));
        Fixtures.Zip z = Fixtures.edit(pptx);
        String pres = z.text("ppt/presentation.xml");
        String style = "<p:defaultTextStyle><a:lvl1pPr><a:defRPr sz=\"4000\"/></a:lvl1pPr></p:defaultTextStyle>";
        pres = pres.contains("<p:defaultTextStyle")
                ? pres.replaceAll("(?s)<p:defaultTextStyle>.*</p:defaultTextStyle>", style)
                : pres.replace("</p:presentation>", style + "</p:presentation>");
        z.put("ppt/presentation.xml", pres);
        List<TextPosition> pos = Decks.convert(dir, "size.pptx", z.bytes()).positions(0);
        assertTrue(!pos.isEmpty());
        for (TextPosition t : pos) {
            assertTrue(t.getFontSizeInPt() < 30, t.getUnicode() + " " + t.getFontSizeInPt());
        }
    }

    @Test
    void tableTextIgnoresThePresentationDefaultFont() throws IOException {
        byte[] pptx = Decks.slideXml(table("<a:tblPr/>", "sz=\"1400\""));
        Fixtures.Zip z = Fixtures.edit(pptx);
        String pres = z.text("ppt/presentation.xml");
        String style = "<p:defaultTextStyle><a:lvl1pPr><a:defRPr><a:latin typeface=\"Times New Roman\"/></a:defRPr>"
                + "</a:lvl1pPr></p:defaultTextStyle>";
        pres = pres.contains("<p:defaultTextStyle")
                ? pres.replaceAll("(?s)<p:defaultTextStyle>.*</p:defaultTextStyle>", style)
                : pres.replace("</p:presentation>", style + "</p:presentation>");
        z.put("ppt/presentation.xml", pres);
        List<TextPosition> pos = Decks.convert(dir, "font.pptx", z.bytes()).positions(0);
        assertTrue(!pos.isEmpty());
        for (TextPosition t : pos) {
            String name = t.getFont().getName();
            assertTrue(!name.contains("Times") && !name.contains("Serif"), t.getUnicode() + " " + name);
        }
    }

    @Test
    void lightFacesWithoutABoldAreEmboldenedNotSwapped() {
        FontLibrary fonts = FontLibrary.of(List.of(dir));
        Standins s = new Standins(new CloudFonts(fonts));
        FontFace bold = s.emulate("Calibri Light", true, false).face();
        FontFace regular = s.emulate("Calibri Light", false, false).face();
        assertTrue(bold.syntheticBold());
        assertTrue(bold.sameProgram(regular));
        assertTrue(!s.emulate("Georgia", true, false).face().syntheticBold()
                || !fonts.find("Georgia", true, false).bold());
    }
}
