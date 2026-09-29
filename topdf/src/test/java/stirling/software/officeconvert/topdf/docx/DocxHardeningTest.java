package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxHardeningTest {

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Liberation Sans\""
            + " w:hAnsi=\"Liberation Sans\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String TABLE = "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr><w:tblGrid>"
            + "<w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr>"
            + "</w:tbl><w:p/>";

    @TempDir
    Path dir;

    // Lays the document out as the renderer does and hands the package and pages to the check
    private interface Check {
        void accept(DocxPackage pkg, Ctx ctx, PageFlow flow) throws IOException;
    }

    private void layout(String name, byte[] docx, Check check) throws IOException {
        Path in = Fixtures.write(dir, name + ".docx", docx);
        FontLibrary fonts = FontLibrary.system();
        try (OfficeZip zip = OfficeZip.open(in); PdfOutput output = new PdfOutput(fonts)) {
            RenderJob job = new RenderJob(zip, OfficeToPdf.Format.DOCX, OfficeToPdf.Options.defaults(), fonts, output);
            DocxPackage pkg = new DocxPackage(zip, job);
            pkg.load();
            Ctx ctx = new Ctx(pkg);
            check.accept(pkg, ctx, PageFlow.layout(ctx));
        }
    }

    private static String paragraphs(String text, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p(text));
        }
        return b.toString();
    }

    private static String headerSection() {
        return "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdheader1xml\"/><w:pgSz w:w=\"12240\""
                + " w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
    }

    @Test
    void aStyledTableConvertsWithoutAnyStylesPart() throws IOException {
        DocxDoc.Rendered bare = DocxDoc.render(dir, "nostyles", new DocxDoc().body(TABLE).bytes());
        assertTrue(bare.text().contains("cell"), bare.text());
        String onlyTable = "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>"
                + "<w:rPr><w:sz w:val=\"28\"/></w:rPr></w:style>";
        DocxDoc.Rendered styled = DocxDoc.render(dir, "tablestyle",
                new DocxDoc().styles(onlyTable).body(TABLE).bytes());
        assertTrue(styled.text().contains("cell"), styled.text());
    }

    @Test
    void aHeaderWithoutPageFieldsIsLaidOutOnceForItsSection() throws IOException {
        byte[] docx = new DocxDoc().styles(DEFAULTS).header("header1.xml", paragraphs("header line", 400))
                .section(headerSection()).body(paragraphs("b", 200)).bytes();
        layout("sharedheader", docx, (pkg, ctx, flow) -> {
            assertTrue(flow.pages.size() > 20, "pages " + flow.pages.size());
            List<Op> first = ((Op.Group) flow.pages.get(0).header.get(0)).ops();
            for (PageBox p : flow.pages) {
                assertSame(first, ((Op.Group) p.header.get(0)).ops());
            }
            assertTrue(first.size() < 100, "header lines past the page are not laid out: " + first.size());
        });
    }

    @Test
    void aTallHeaderWithAPageFieldIsCutAtThePageBottom() throws IOException {
        String field = "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText>"
                + "</w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar"
                + " w:fldCharType=\"end\"/></w:r></w:p>";
        byte[] docx = new DocxDoc().styles(DEFAULTS).header("header1.xml", field + paragraphs("header line", 2000))
                .section(headerSection()).body(paragraphs("b", 150)).bytes();
        layout("pagedheader", docx, (pkg, ctx, flow) -> {
            assertTrue(flow.pages.size() > 20, "pages " + flow.pages.size());
            for (PageBox p : flow.pages) {
                int lines = ((Op.Group) p.header.get(0)).ops().size();
                assertTrue(lines < 100, "header lines on a page: " + lines);
            }
        });
    }

    @Test
    void aChartPartIsReadAndDrawnOnceHoweverOftenItIsShown() throws IOException {
        StringBuilder runs = new StringBuilder("<w:p>");
        for (int i = 0; i < 12; i++) {
            runs.append(DocxDoc.chartRun("rIdC"));
        }
        runs.append("</w:p>");
        byte[] docx = new DocxDoc().styles(DEFAULTS).chart("chart1.xml", "rIdC",
                DocxDoc.chartXml("Sales", "2026", "North", "South")).body(runs.toString()).bytes();
        layout("charts", docx, (pkg, ctx, flow) -> {
            List<Chart> charts = new ArrayList<>();
            for (Section s : pkg.sections) {
                for (Block b : s.blocks()) {
                    if (b instanceof Para p) {
                        for (Inline in : p.items) {
                            if (in instanceof Inline.Obj o && o.drawing().graphic instanceof Drawing.ChartGraphic c) {
                                charts.add(c.chart());
                            }
                        }
                    }
                }
            }
            assertEquals(12, charts.size());
            for (Chart c : charts) {
                assertSame(charts.get(0), c);
            }
            assertEquals(1, pkg.charts.size());
            assertSame(ctx.chart(charts.get(0), 400, 240), ctx.chart(charts.get(0), 400, 240));
        });
    }

    @Test
    void anEquationTooLargeToLayOutFallsBackToItsText() throws IOException {
        String math = "<w:p><m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><m:r><m:t>"
                + "ab+".repeat(40_000) + "</m:t></m:r></m:oMath></w:p>";
        long start = System.nanoTime();
        DocxDoc.Rendered r = DocxDoc.render(dir, "hugemath", new DocxDoc().styles(DEFAULTS).body(math).bytes());
        assertTrue(r.pages() > 1, "the linear text wraps and pages: " + r.pages());
        assertTrue(r.text(1).contains("ab+ab+"), r.text(1));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(60).toNanos());
        String fraction = "<w:p><m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"><m:f>"
                + "<m:num><m:r><m:t>x</m:t></m:r></m:num><m:den><m:r><m:t>7</m:t></m:r></m:den></m:f></m:oMath></w:p>";
        DocxDoc.Rendered laid = DocxDoc.render(dir, "fraction", new DocxDoc().styles(DEFAULTS).body(fraction).bytes());
        assertTrue(laid.word("7").y() - laid.word("x").y() > 5, "a small equation is still set as math");
    }

    @Test
    void oneLongUnbrokenWordBreaksInLinearTime() throws IOException {
        String word = "a".repeat(1_000_000);
        byte[] docx = new DocxDoc().styles(DEFAULTS).body(DocxDoc.p(word) + DocxDoc.p("End")).bytes();
        Path in = Fixtures.write(dir, "longword.docx", docx);
        Path out = dir.resolve("longword.pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofSeconds(40)));
        DocxDoc.Rendered r = new DocxDoc.Rendered(out, result);
        assertTrue(r.pages() > 100, "pages " + r.pages());
        String text = r.text().replaceAll("\\s", "");
        assertEquals(word.length() + 3, text.length());
    }
}
