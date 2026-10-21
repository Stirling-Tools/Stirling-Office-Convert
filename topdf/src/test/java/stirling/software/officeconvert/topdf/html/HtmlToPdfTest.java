package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlToPdfTest {

    @TempDir
    Path dir;

    record Pdf(int pages, String text, int images, float width, OfficeToPdf.Result result) {}

    Pdf convert(String name, String html) throws IOException {
        return convert(name, html.getBytes(StandardCharsets.UTF_8));
    }

    Pdf convert(String name, byte[] content) throws IOException {
        Path in = Files.write(dir.resolve(name), content);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int images = 0;
            for (PDPage page : doc.getPages()) {
                for (var name2 : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name2) instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            return new Pdf(doc.getNumberOfPages(), new PDFTextStripper().getText(doc), images,
                    doc.getPage(0).getMediaBox().getWidth(), r);
        }
    }

    static String png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, x * 255 / w << 16 | y * 255 / h << 8 | 0x80);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void headingsParagraphsListsAndTablesAreLaidOut() throws IOException {
        Pdf p = convert("page.html", """
                <!DOCTYPE html><html><head><title>Report</title>
                <style>.lead{color:#336699;font-size:14pt} td{border:1px solid black}</style></head>
                <body><h1>Quarterly report</h1><p class="lead">Revenue grew in <b>every</b> region.</p>
                <ol><li>First point</li><li>Second point<ul><li>Nested bullet</li></ul></li></ol>
                <table><tr><th>Region</th><th>Sales</th></tr><tr><td>North</td><td>1,200</td></tr>
                <tr><td colspan="2">Total across regions</td></tr></table>
                <pre>keep   these
                lines</pre></body></html>""");
        String t = p.text();
        for (String s : new String[] {"Quarterly report", "Revenue grew in every region.", "First point", "Nested bullet",
                "Region", "1,200", "Total across regions"}) {
            assertTrue(t.contains(s), s + " missing from " + t);
        }
        assertTrue(t.contains("1.") && t.contains("2."), "numbered list markers: " + t);
        assertTrue(t.lines().anyMatch(l -> l.strip().startsWith("keep") && l.contains("these"))
                && t.lines().anyMatch(l -> l.strip().equals("lines")), "pre keeps its lines: " + t);
        assertEquals(1, p.pages());
        assertEquals(595.3f, p.width(), 1f);
    }

    @Test
    void contentWiderThanThePageIsShrunkToFitAsBrowsersPrint() throws IOException {
        String lines = ("<pre>" + ("x".repeat(110) + "\n").repeat(240) + "</pre>");
        Pdf shrunk = convert("wide.html", "<html><body>" + lines + "</body></html>");
        Pdf asked = convert("paper.html", "<html><head><style>@page{size:A4;margin:1in}</style></head><body>" + lines
                + "</body></html>");
        assertEquals(595.3f, shrunk.width(), 1f);
        assertEquals(595.3f, asked.width(), 1f);
        assertTrue(shrunk.pages() < asked.pages(), shrunk.pages() + " vs " + asked.pages());
        assertTrue(shrunk.text().contains("x".repeat(110)), "a shrunk line keeps its characters on one line");
    }

    @Test
    void linksAndBookmarksOnAShrunkPageStillLandOnThePaper() throws IOException {
        String html = "<html><body><p><a href=\"#end\">jump</a> <a href=\"https://example.com\">out</a></p><pre>"
                + ("y".repeat(110) + "\n").repeat(150) + "</pre><h1 id=end>End heading</h1></body></html>";
        Path in = Files.writeString(dir.resolve("links.html"), html);
        Path out = dir.resolve("links.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int links = 0;
            for (PDPage page : doc.getPages()) {
                PDRectangle paper = page.getMediaBox();
                assertEquals(595.3f, paper.getWidth(), 1f);
                for (var a : page.getAnnotations()) {
                    assertTrue(a.getRectangle().getUpperRightX() <= paper.getWidth() + 1, "link area on the page");
                    links++;
                    if (a instanceof PDAnnotationLink link
                            && link.getDestination() instanceof PDPageXYZDestination xyz) {
                        assertTrue(xyz.getTop() <= paper.getHeight() + 1, "jump target on the page");
                    }
                }
            }
            assertTrue(links > 0, "the link kept its area");
            var outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                for (var item : outline.children()) {
                    if (item.getDestination() instanceof PDPageXYZDestination xyz) {
                        assertTrue(xyz.getTop() <= 842 + 1, "bookmark on the page");
                    }
                }
            }
        }
    }

    @Test
    void boxesThatClipShowOnlyWhatFits() throws IOException {
        String paras = "<p>Alpha line</p><p>Bravo line</p><p>Charlie line</p><p>Delta line</p>";
        Pdf p = convert("clip.html", "<div style=\"max-height:40px;overflow:hidden\">" + paras + "</div>"
                + "<div style=\"-webkit-line-clamp:1;overflow:hidden\"><p>Echo line</p><p>Foxtrot line</p></div>"
                + "<div style=\"display:flex;height:20px;overflow:hidden\"><p>Golf one</p><p>Hotel two</p></div>"
                + "<p>After</p>");
        assertTrue(p.text().contains("Alpha") && p.text().contains("Echo") && p.text().contains("After"), p.text());
        assertTrue(p.text().contains("Golf") && p.text().contains("Hotel"), p.text());
        assertFalse(p.text().contains("Delta") || p.text().contains("Foxtrot"), p.text());
    }

    @Test
    void scriptsAndHiddenContentAreNotDrawn() throws IOException {
        Pdf p = convert("hidden.html", "<html><head><script>document.write('SCRIPTED')</script>"
                + "<style>.gone{display:none}</style></head><body><p>Shown</p><p class=gone>CLASSHIDDEN</p>"
                + "<p hidden>ATTRHIDDEN</p><p style=\"display:none\">STYLEHIDDEN</p><template>TEMPLATE</template>"
                + "<noscript>Fallback text</noscript></body></html>");
        assertTrue(p.text().contains("Shown") && p.text().contains("Fallback text"), p.text());
        for (String s : new String[] {"SCRIPTED", "CLASSHIDDEN", "ATTRHIDDEN", "STYLEHIDDEN", "TEMPLATE"}) {
            assertFalse(p.text().contains(s), s);
        }
    }

    @Test
    void inlinePicturesAreDrawnAndLinkedOnesLeftOutWithAWarning() throws IOException {
        Pdf p = convert("pictures.html", "<p>Picture:</p><img src=\"" + png(40, 20) + "\" width=\"200\">"
                + "<img src=\"https://example.invalid/x.png\" alt=\"remote\"><img src=\"file:///etc/passwd\">");
        assertEquals(1, p.images());
        assertTrue(p.text().contains("[remote]"), p.text());
        assertTrue(p.result().warnings().stream().anyMatch(w -> w.contains("not") || w.contains("left out")),
                p.result().warnings().toString());
    }

    @Test
    void svgInThePageAndInDataUrisIsDrawn() throws IOException {
        Pdf p = convert("svg.html", "<p>Logo</p><svg width=\"40\" height=\"20\" viewBox=\"0 0 4 2\">"
                + "<rect width=\"4\" height=\"2\" fill=\"#c00\"/></svg>"
                + "<img src=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1 1'%3E"
                + "%3Ccircle cx='.5' cy='.5' r='.5'/%3E%3C/svg%3E\" width=\"30\">");
        assertEquals(2, p.images());
        assertTrue(p.text().contains("Logo"));
    }

    @Test
    void floatedPicturesSitBesideTheTextThatWrapsThem() throws IOException {
        Path in = Files.writeString(dir.resolve("float.html"), "<p><img src=\"" + png(40, 40) + "\" width=\"120\""
                + " style=\"float:right\">Text that flows beside the picture.</p><div style=\"float:left\"><img src=\""
                + png(20, 20) + "\" width=\"60\"></div><p>More text.</p>", StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream json = new java.io.ByteArrayOutputStream();
        try (stirling.software.officeconvert.json.JsonWriter w = new stirling.software.officeconvert.json.JsonWriter(json,
                stirling.software.officeconvert.Pictures.COMPACT)) {
            HtmlDocument.write(in, w);
        }
        String model = json.toString(StandardCharsets.UTF_8);
        assertTrue(model.contains("\"wrap\":\"square\""), model);
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("\"wrap\":\"square\",\"x\":([0-9.]+)").matcher(model);
        assertTrue(x.find());
        float right = Float.parseFloat(x.group(1));
        assertTrue(x.find());
        float left = Float.parseFloat(x.group(1));
        assertTrue(right > 300 && left < 100, right + " " + left);
        assertEquals(1, convert("float.html", Files.readString(in)).pages());
    }

    @Test
    void pageBreaksAndPageSizeFollowTheCss() throws IOException {
        Pdf p = convert("pages.htm", "<style>@page{size:letter landscape;margin:1in}</style><p>One</p>"
                + "<p style=\"page-break-before:always\">Two</p><div style=\"break-before:page\">Three</div>");
        assertEquals(3, p.pages());
        assertEquals(792f, p.width(), 1f);
    }

    @Test
    void namedPagesStartSectionsOfTheirOwnSize() throws IOException {
        Path in = Files.writeString(dir.resolve("named.html"), "<style>@page{size:A4;margin:1in}"
                + "@page wide{size:A4 landscape;margin:0.5in}.w{page:wide}.t{page:tall}@page tall{size:A5}</style>"
                + "<p>Portrait</p><div class=w><p>Landscape one</p><p>Landscape two</p></div>"
                + "<div class=t><p>Small</p></div><div class=nothing style=\"page:missing\"><p>Still small</p></div>",
                StandardCharsets.UTF_8);
        Path out = dir.resolve("named.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(3, doc.getNumberOfPages());
            assertEquals(595.3f, doc.getPage(0).getMediaBox().getWidth(), 1f);
            assertEquals(841.9f, doc.getPage(1).getMediaBox().getWidth(), 1f);
            assertEquals(419.5f, doc.getPage(2).getMediaBox().getWidth(), 1f);
            assertTrue(new PDFTextStripper().getText(doc).contains("Still small"));
        }
    }

    @Test
    void anHtmlFileWithAnOfficeNameStillConverts() throws IOException {
        Pdf p = convert("saved-from-word.doc", "<html><body><p>Saved as a web page</p></body></html>");
        assertTrue(p.text().contains("Saved as a web page"), p.text());
    }

    @Test
    void deepNestingAndLargeTablesStayBounded() throws IOException {
        StringBuilder deep = new StringBuilder();
        deep.append("<div>".repeat(5_000)).append("bottom").append("</div>".repeat(5_000));
        assertTrue(convert("deep.html", deep.toString()).text().contains("bottom"));
        String tables = "<table><tr><td>".repeat(4_000) + "inner" + "</td></tr></table>".repeat(4_000);
        assertTrue(convert("tables.html", tables).text().contains("inner"), "one-column tables nested deep");
        String cells = "<table><tr><td>a</td><td>".repeat(3_000) + "deep" + "</td></tr></table>".repeat(3_000);
        assertTrue(convert("cells.html", cells).text().contains("deep"), "tables nested in cells");
        String flex = "<div style=\"display:flex\"><div>side</div><div>".repeat(3_000) + "end"
                + "</div></div>".repeat(3_000);
        assertTrue(convert("flex.html", flex).text().contains("end"), "flex rows nested deep");
        String tabs = "<pre>" + ("\t".repeat(5_000) + "x\n").repeat(50) + "</pre>";
        assertTrue(convert("tabs.html", tabs).text().contains("x"), "long runs of tabs are cut short");
        StringBuilder wide = new StringBuilder("<table>");
        for (int r = 0; r < 3; r++) {
            wide.append("<tr>").append("<td>c</td>".repeat(200)).append("</tr>");
        }
        Pdf p = convert("wide.html", wide.append("</table>").toString());
        assertTrue(p.result().warnings().stream().anyMatch(w -> w.contains("columns")), p.result().warnings().toString());
        assertTrue(convert("spans.html", "<table><tr><td rowspan=0 colspan=9999>x</td><td>z</td></tr><tr><td>y</td></tr></table>")
                .text().contains("x"));
    }

    @Test
    void notHtmlIsRefused() {
        assertThrows(IOException.class, () -> convert("binary.html", new byte[] {'P', 'K', 3, 4, 0, 0}));
    }
}
