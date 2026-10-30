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
import org.junit.jupiter.api.Timeout;
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
        assertTrue(System.nanoTime() - start < Duration.ofMinutes(3).toNanos());
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
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(10)));
        DocxDoc.Rendered r = new DocxDoc.Rendered(out, result);
        assertTrue(r.pages() > 100, "pages " + r.pages());
        String text = r.text().replaceAll("\\s", "");
        assertEquals(word.length() + 3, text.length());
    }

    @Test
    void contentNestedPastTheLimitIsLeftOutAndMakesThePdfPartial() throws IOException {
        for (int depth : new int[] {5, ContentReader.MAX_DEPTH, 60}) {
            String nested = DocxDoc.p("leaf");
            for (int i = 0; i < depth; i++) {
                nested = "<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc>" + nested
                        + "<w:p/></w:tc></w:tr></w:tbl>";
            }
            byte[] docx = new DocxDoc().styles(DEFAULTS).body(DocxDoc.p("before") + nested + DocxDoc.p("after")).bytes();
            DocxDoc.Rendered r = DocxDoc.render(dir, "nest" + depth, docx);
            String text = r.text();
            assertTrue(text.contains("before") && text.contains("after"), depth + ": " + text);
            boolean lost = depth > ContentReader.MAX_DEPTH;
            assertEquals(!lost, text.contains("leaf"), depth + ": " + text);
            assertEquals(lost, r.result().truncated(), depth + ": " + r.result().warnings());
            assertEquals(lost, r.result().warnings().stream().anyMatch(w -> w.contains("levels deep was left out")),
                    depth + ": " + r.result().warnings());
        }
    }

    private static String rotatedTables(int depth) {
        String inner = "";
        for (int i = 0; i < depth; i++) {
            inner = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc><w:tcPr>"
                    + "<w:textDirection w:val=\"btLr\"/></w:tcPr>" + DocxDoc.p("x") + inner + "<w:p/></w:tc></w:tr>"
                    + "</w:tbl>";
        }
        return inner + "<w:p/>";
    }

    @Test
    @Timeout(60)
    void nestedRotatedCellsAreLaidOutOnceEach() throws IOException {
        DocxDoc.Rendered r = DocxDoc.render(dir, "rotated", new DocxDoc().styles(DEFAULTS).body(rotatedTables(32))
                .bytes());
        assertEquals(1, r.result().pages());
    }

    @Test
    void everyNestedRotatedCellIsDrawn() throws IOException {
        DocxDoc.Rendered r = DocxDoc.render(dir, "rotated3", new DocxDoc().styles(DEFAULTS).body(rotatedTables(3))
                .bytes());
        assertEquals(3, r.text().chars().filter(c -> c == 'x').count(), r.text());
    }

    @Test
    @Timeout(60)
    void pageBreaksPastThePageLimitAreNotRead() throws IOException {
        String breaks = "<w:p><w:r><w:t>p</w:t><w:br w:type=\"page\"/></w:r></w:p>".repeat(200_000);
        Path in = Fixtures.write(dir, "breaks.docx", new DocxDoc().styles(DEFAULTS).body(breaks).bytes());
        FontLibrary fonts = FontLibrary.system();
        try (OfficeZip zip = OfficeZip.open(in); PdfOutput output = new PdfOutput(fonts)) {
            RenderJob job = new RenderJob(zip, OfficeToPdf.Format.DOCX, OfficeToPdf.Options.defaults().maxPages(10),
                    fonts, output);
            DocxPackage pkg = new DocxPackage(zip, job);
            pkg.load();
            int blocks = 0;
            for (Section s : pkg.sections) {
                blocks += s.blocks().size();
            }
            assertTrue(blocks <= 2 * 10 + 17, "blocks read " + blocks);
            assertTrue(job.truncated());
        }
        OfficeToPdf.Result result = OfficeToPdf.convert(in, dir.resolve("breaks.pdf"),
                OfficeToPdf.Options.defaults().maxPages(10));
        assertEquals(10, result.pages());
        assertTrue(result.warnings().contains("Stopped at the page limit of 10 pages"), result.warnings().toString());
    }

    @Test
    void pageBreaksAreCountedWhereTheyBreakPages() throws IOException {
        XEl p = XTree.parse(new java.io.ByteArrayInputStream(("<w:p " + DocxDoc.NS + "><w:pPr><w:pageBreakBefore/>"
                + "<w:sectPr><w:type w:val=\"continuous\"/></w:sectPr></w:pPr><w:r><w:br w:type=\"page\"/>"
                + "<w:br/><w:txbxContent><w:p><w:r><w:br w:type=\"page\"/></w:r></w:p></w:txbxContent></w:r></w:p>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(2, DocxPackage.pageBreaks(p));
    }

    @Test
    void pagesBeforeThePageLimitKeepTheSectionTheyBelongTo() throws IOException {
        String breaks = "<w:p><w:r><w:t>p</w:t><w:br w:type=\"page\"/></w:r></w:p>".repeat(60);
        String body = breaks + "<w:p><w:pPr><w:sectPr><w:pgSz w:w=\"6000\" w:h=\"8000\"/><w:pgMar w:top=\"720\""
                + " w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:pPr></w:p>" + DocxDoc.p("last");
        Path in = Fixtures.write(dir, "sect.docx", new DocxDoc().styles(DEFAULTS).body(body)
                .section("<w:sectPr><w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/></w:sectPr>")
                .bytes());
        OfficeToPdf.Result result = OfficeToPdf.convert(in, dir.resolve("sect.pdf"),
                OfficeToPdf.Options.defaults().maxPages(2));
        assertEquals(2, result.pages());
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(dir.resolve("sect.pdf")
                .toFile())) {
            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                assertEquals(300, page.getMediaBox().getWidth(), 0.5);
                assertEquals(400, page.getMediaBox().getHeight(), 0.5);
            }
        }
    }

    @Test
    void aBreakFollowedByABreakBeforeIsNotStoppedEarly() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            body.append("<w:p><w:r><w:t>end ").append(i).append("</w:t></w:r><w:r><w:br w:type=\"page\"/></w:r>")
                    .append("</w:p><w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>head</w:t></w:r></w:p>");
        }
        Path in = Fixtures.write(dir, "brpbb.docx", new DocxDoc().styles(DEFAULTS).body(body.toString()).bytes());
        OfficeToPdf.Result result = OfficeToPdf.convert(in, dir.resolve("brpbb.pdf"),
                OfficeToPdf.Options.defaults().maxPages(4));
        assertEquals(4, result.pages());
    }

    @Test
    void pageBreaksInRevisionsAndTableCellsAreNotCounted() throws IOException {
        XEl p = XTree.parse(new java.io.ByteArrayInputStream(("<w:body " + DocxDoc.NS + "><w:p><w:pPr><w:pPrChange>"
                + "<w:pPr><w:pageBreakBefore/></w:pPr></w:pPrChange></w:pPr><w:del><w:r><w:br w:type=\"page\"/></w:r>"
                + "</w:del></w:p><w:tbl><w:tr><w:tc><w:p><w:pPr><w:pageBreakBefore/></w:pPr></w:p></w:tc></w:tr>"
                + "</w:tbl></w:body>").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(0, DocxPackage.pageBreaks(p));
    }

    @Test
    void aRepeatedRotatedHeaderShowsEachPagesNumber() throws IOException {
        String cell = "<w:tc><w:p><w:r><w:t>row</w:t></w:r></w:p></w:tc>";
        String head = "<w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:textDirection w:val=\"btLr\"/></w:tcPr>"
                + "<w:p><w:fldSimple w:instr=\" PAGE \"><w:r><w:t>9</w:t></w:r></w:fldSimple></w:p></w:tc></w:tr>";
        String table = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" + head
                + ("<w:tr>" + cell + "</w:tr>").repeat(150) + "</w:tbl><w:p/>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "turnedpage", new DocxDoc().styles(DEFAULTS).body(table).bytes());
        assertTrue(r.result().pages() >= 3, "pages " + r.result().pages());
        for (int page = 1; page <= r.result().pages(); page++) {
            String text = r.text(page);
            assertTrue(text.contains(Integer.toString(page)), page + ": " + text);
        }
    }
}
