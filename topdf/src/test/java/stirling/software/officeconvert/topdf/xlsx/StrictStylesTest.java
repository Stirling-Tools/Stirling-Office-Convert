package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrictStylesTest {

    private static final String STRICT = "xmlns=\"http://purl.oclc.org/ooxml/spreadsheetml/main\"";

    @TempDir
    Path dir;

    @Test
    void aStrictStyleSheetAndThemeAreRead() throws IOException {
        String styles = "<styleSheet " + STRICT + "><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/>"
                + "</font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill"
                + " patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor theme=\"4\"/>"
                + "</patternFill></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/>"
                + "</cellStyleXfs><cellXfs count=\"2\"><xf/><xf fillId=\"2\" applyFill=\"1\"/></cellXfs></styleSheet>";
        String theme = "<a:theme xmlns:a=\"http://purl.oclc.org/ooxml/drawingml/main\" name=\"T\"><a:themeElements>"
                + "<a:clrScheme name=\"C\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/>"
                + "</a:lt1><a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2><a:accent1>"
                + "<a:srgbClr val=\"FF0000\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3>"
                + "<a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5>"
                + "<a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink>"
                + "<a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>"
                + "<a:fontScheme name=\"F\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs"
                + " typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs"
                + " typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"M\"/></a:themeElements></a:theme>";
        String sheet = "<worksheet " + STRICT + "><sheetData><row r=\"1\">" + RawXlsx.inline("A1", "First")
                + "</row><row r=\"120\"><c r=\"A120\" s=\"1\"/></row></sheetData></worksheet>";
        byte[] xlsx = new RawXlsx().sheet("S", sheet)
                .part("xl/styles.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml", styles)
                .rel("xl/workbook.xml", "rIdStyles", "styles", "styles.xml")
                .part("xl/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml", theme)
                .rel("xl/workbook.xml", "rIdTheme", "theme", "theme/theme1.xml").bytes();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "strict.xlsx", xlsx);
        assertTrue(c.result().warnings().stream().noneMatch(w -> w.contains("could not be read")),
                c.result().warnings().toString());
        assertEquals(3, c.pages().size());
    }

    @Test
    void aTransitionalPartIsStreamedAndAStrictOneIsBounded() throws IOException {
        byte[] head = "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                .getBytes(StandardCharsets.ISO_8859_1);
        try (InputStream in = WorkbookModel.transitional(endless(head))) {
            assertEquals('<', in.read());
        }
        byte[] strict = ("<styleSheet " + STRICT + ">").getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IOException.class, () -> WorkbookModel.transitional(endless(strict)));
    }

    private static InputStream endless(byte[] head) {
        return new InputStream() {
            private long at;

            @Override
            public int read() {
                return at < head.length ? head[(int) at++] : at++ < 1L << 40 ? ' ' : -1;
            }
        };
    }
}
