package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaginationTest {

    @TempDir
    Path dir;

    private static String rows(int n) {
        StringBuilder b = new StringBuilder("<sheetData>");
        for (int r = 1; r <= n; r++) {
            b.append("<row r=\"").append(r).append("\"><c r=\"A").append(r).append("\" t=\"inlineStr\"><is><t>row")
                    .append(r).append("</t></is></c></row>");
        }
        return b.append("</sheetData>").toString();
    }

    private static String fitted(String fit) {
        return "<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>" + rows(6) + "<pageSetup paperSize=\"9\" " + fit
                + "/><rowBreaks count=\"1\" manualBreakCount=\"1\"><brk id=\"3\" max=\"16383\" man=\"1\"/></rowBreaks>";
    }

    @Test
    void fitToWidthOnlyKeepsTheManualRowBreaks() throws Exception {
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "auto.xlsx",
                new RawXlsx().sheet("S", fitted("fitToHeight=\"0\"")).bytes());
        assertEquals(2, c.pages().size());
        assertTrue(c.pages().get(0).contains("row3") && !c.pages().get(0).contains("row4"), c.pages().get(0));
    }

    @Test
    void fitToAWholePageIgnoresTheManualRowBreaks() throws Exception {
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "whole.xlsx",
                new RawXlsx().sheet("S", fitted("fitToHeight=\"1\"")).bytes());
        assertEquals(1, c.pages().size());
    }
}
