package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import java.util.TreeSet;

import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.pdf.PageSize;

class LayoutPartsTest {

    @Test
    void pagesBreakAtTheLastWholeRowAndAtManualBreaks() throws Exception {
        List<Paginator.Span> spans = Paginator.spans(0, 9, i -> 10, -1, -1, 35, null, Integer.MAX_VALUE);
        assertEquals(List.of(new Paginator.Span(0, 2), new Paginator.Span(3, 5), new Paginator.Span(6, 8),
                new Paginator.Span(9, 9)), spans);
        TreeSet<Integer> breaks = new TreeSet<>(List.of(2));
        assertEquals(new Paginator.Span(0, 1), Paginator.spans(0, 9, i -> 10, -1, -1, 100, breaks, 9).get(0));
        List<Paginator.Span> titled = Paginator.spans(0, 9, i -> 10, 0, 0, 35, null, Integer.MAX_VALUE);
        assertEquals(new Paginator.Span(3, 4), titled.get(1));
        assertEquals(1, Paginator.spans(0, 0, i -> 500, -1, -1, 35, null, Integer.MAX_VALUE).size());
    }

    @Test
    void paperCodesMapToPageSizes() {
        assertEquals(PageSize.A3, PageSetup.byCode(8));
        assertEquals(PageSize.LEGAL, PageSetup.byCode(5));
        assertEquals(PageSetup.DEFAULT_PAPER, PageSetup.byCode(1));
        assertEquals(PageSetup.DEFAULT_PAPER, PageSetup.byCode(12345));
    }

    @Test
    void printAreasParseAllReferenceForms() {
        assertEquals(CellRangeAddress.valueOf("B2:D9"), PrintRanges.parse("'My sheet'!$B$2:$D$9", 5, 5).get(0));
        assertEquals(new CellRangeAddress(0, 20, 0, 3), PrintRanges.parse("S!$A:$D", 20, 9).get(0));
        assertEquals(new CellRangeAddress(4, 6, 0, 9), PrintRanges.parse("S!$5:$7", 20, 9).get(0));
        assertEquals(2, PrintRanges.parse("S!$A$1:$B$2,'S,2'!$C$3", 9, 9).size());
        assertTrue(PrintRanges.parse("#REF!", 9, 9).isEmpty());
    }

    @Test
    void headerCodesExpandFieldsFontsAndSections() {
        FontSpec base = new FontSpec("Calibri", 11, false, false, null, false, Color.BLACK, null);
        HeaderFooterText.Context ctx = new HeaderFooterText.Context(3, 9, "Sales", "book.xlsx", "1/2/2026", "9:05 AM");
        HeaderFooterText.Sections s = HeaderFooterText.parse(
                "&L&\"Arial,Bold\"&14&A&CPage &P of &N&R&F &&&P+1&KFF0000!", base, ctx, null);
        TextRun left = s.left().get(0).get(0);
        assertEquals("Sales", left.text());
        assertEquals("Arial", left.font().family());
        assertTrue(left.font().bold());
        assertEquals(14, left.font().size());
        assertEquals("Page 3 of 9", s.center().get(0).get(0).text());
        StringBuilder right = new StringBuilder();
        for (TextRun r : s.right().get(0)) {
            right.append(r.text());
        }
        assertEquals("book.xlsx &4!", right.toString());
        assertEquals(Color.RED, s.right().get(0).get(s.right().get(0).size() - 1).font().color());
        HeaderFooterText.Sections lines = HeaderFooterText.parse("one\ntwo", base, ctx, null);
        assertEquals(2, lines.center().size());
    }

    @Test
    void tintsLightenAndDarkenInHsl() {
        Color base = new Color(0x4472C4);
        Color light = ExcelColors.tint(base, 0.8);
        Color dark = ExcelColors.tint(base, -0.5);
        assertTrue(light.getRed() > 200 && light.getBlue() > 230, light.toString());
        assertTrue(dark.getBlue() < base.getBlue() && dark.getRed() < base.getRed(), dark.toString());
        assertEquals(Color.WHITE, ExcelColors.tint(Color.BLACK, 1.0));
    }

    @Test
    void borderConflictsKeepTheHeavierLine() {
        BorderLine thin = new BorderLine(org.apache.poi.ss.usermodel.BorderStyle.THIN, Color.BLACK);
        BorderLine thick = new BorderLine(org.apache.poi.ss.usermodel.BorderStyle.THICK, Color.RED);
        assertEquals(thick, BorderLine.stronger(thin, thick));
        assertEquals(thin, BorderLine.stronger(thin, BorderLine.NONE));
        assertEquals(thin, BorderLine.stronger(null, thin));
    }
}
