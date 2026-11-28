package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class MarkdownToPdfTest {

    @TempDir
    Path dir;

    private static final OfficeToPdf.Options OPTIONS = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));

    private static final String DOC = """
            ---
            title: Widget guide
            ---
            # Widget

            See the [site](https://example.com/widget) or www.example.org, and [setup](#setup).

            ## Setup

            | Option | Meaning |
            |--------|---------|
            | `-v`   | verbose |

            - [x] installed
            - [ ] configured

            ```sh
            widget --serve --port 8080
            ```

            ### Deep dive

            Done.
            """;

    private Path write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private PDDocument convert(Path in, OfficeToPdf.Options options) throws IOException {
        Path out = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.convert(in, out, options);
        return Loader.loadPDF(out.toFile());
    }

    private static List<String> outline(PDDocument doc) {
        List<String> titles = new ArrayList<>();
        walk(doc.getDocumentCatalog().getDocumentOutline().getFirstChild(), 0, titles);
        return titles;
    }

    private static void walk(PDOutlineItem item, int depth, List<String> out) {
        for (PDOutlineItem i = item; i != null; i = i.getNextSibling()) {
            out.add(depth + ":" + i.getTitle());
            walk(i.getFirstChild(), depth + 1, out);
        }
    }

    @Test
    void markdownNamesHaveTheirOwnFormat() {
        assertEquals(Format.MARKDOWN, Format.of(Path.of("README.md")));
        assertEquals(Format.MARKDOWN, Format.of(Path.of("notes.markdown")));
        assertTrue(Format.recognises(Path.of("a.MD")));
    }

    @Test
    void aMarkdownStreamIsReadAsMarkdown() throws IOException {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        byte[] md = DOC.getBytes(StandardCharsets.UTF_8);
        OfficeToPdf.convert(new ByteArrayInputStream(md), Format.of(Path.of("guide.md")), pdf, OPTIONS);
        try (PDDocument doc = Loader.loadPDF(pdf.toByteArray())) {
            assertEquals(List.of("0:Widget", "1:Setup", "2:Deep dive"), outline(doc));
            assertFalse(new PDFTextStripper().getText(doc).contains("# Widget"));
        }
    }

    @Test
    void headingsBecomeBookmarksAndLinksStay() throws IOException {
        try (PDDocument doc = convert(write("guide.md", DOC), OPTIONS)) {
            assertEquals(List.of("0:Widget", "1:Setup", "2:Deep dive"), outline(doc));
            assertEquals("Widget guide", doc.getDocumentInformation().getTitle());
            List<String> uris = new ArrayList<>();
            int internal = 0;
            for (PDPage page : doc.getPages()) {
                for (PDAnnotation a : page.getAnnotations()) {
                    if (a instanceof PDAnnotationLink link) {
                        if (link.getAction() instanceof PDActionURI uri) {
                            uris.add(uri.getURI());
                        } else if (link.getDestination() != null || link.getAction() != null) {
                            internal++;
                        }
                    }
                }
            }
            assertTrue(uris.contains("https://example.com/widget"), uris.toString());
            assertTrue(uris.contains("http://www.example.org"), uris.toString());
            assertEquals(1, internal);
            String text = new PDFTextStripper().getText(doc);
            assertFalse(text.contains("title:"), text);
            assertTrue(text.contains("widget --serve --port 8080"), text);
            assertTrue(text.contains("verbose"), text);
        }
    }

    @Test
    void codeIsSetInAMonospacedFont() throws IOException {
        try (PDDocument doc = convert(write("code.md", "Text\n\n```\nfixed_width()\n```\n"), OPTIONS)) {
            boolean fixed = false;
            for (PDPage page : doc.getPages()) {
                for (var name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    fixed |= font.getFontDescriptor() != null && font.getFontDescriptor().isFixedPitch()
                            || font.getName().contains("Mono");
                }
            }
            assertTrue(fixed);
        }
    }

    @Test
    void wideCodeAndLongWordsStayOnThePage() throws IOException {
        String md = "```\n" + "x".repeat(400) + " " + "call(a, b, c) ".repeat(40) + "\n```\n\nhttps://example.com/"
                + "segment/".repeat(60) + "\n";
        try (PDDocument doc = convert(write("wide.md", md), OPTIONS)) {
            float width = doc.getPage(0).getMediaBox().getWidth();
            assertEquals(595.3f, width, 1f);
            List<Float> ends = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (var p : positions) {
                        ends.add(p.getXDirAdj() + p.getWidthDirAdj());
                    }
                }
            };
            stripper.getText(doc);
            assertFalse(ends.isEmpty());
            assertTrue(ends.stream().allMatch(x -> x <= width), "text past the right edge");
        }
    }

    @Test
    void picturesBesideThePageNeedTheFolderOptIn() throws IOException {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 40, 40);
        g.dispose();
        Path pics = Files.createDirectories(dir.resolve("site/pics"));
        ImageIO.write(img, "png", pics.resolve("red.png").toFile());
        ImageIO.write(img, "png", dir.resolve("outside.png").toFile());
        Path page = write("site/page.md", "![red](pics/red.png)\n\n![out](../outside.png)\n\n"
                + "![remote](https://example.com/r.png)\n");
        try (PDDocument doc = convert(page, OPTIONS)) {
            assertEquals(0, images(doc));
        }
        try (PDDocument doc = convert(page, OPTIONS.htmlAssets(HtmlAssets.folder(page.getParent(), "page.md")))) {
            assertEquals(1, images(doc));
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("[out]") && text.contains("[remote]"), text);
        }
    }

    @Test
    void missingPicturesKeepTheirLinksAndAltTextWidth() throws IOException {
        String md = "Sponsors <a href=\"https://one.example/\"><img src=\"https://one.example/a.png\"></a> "
                + "<a href=\"https://two.example/\"><img src=\"https://two.example/b.png\"></a> "
                + "[![](https://three.example/c.png)](https://three.example/)\n\n"
                + "| [<img src=\"https://x.example/e.png\" alt=\"Edge\" width=\"24px\">](https://b.example/)<br>Edge | b |\n"
                + "| --- | --- |\n| Edge | last 2 versions |\n";
        try (PDDocument doc = convert(write("sponsors.md", md), OPTIONS)) {
            List<String> uris = new ArrayList<>();
            for (PDAnnotation a : doc.getPage(0).getAnnotations()) {
                if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri) {
                    uris.add(uri.getURI());
                }
            }
            assertTrue(uris.containsAll(List.of("https://one.example/", "https://two.example/", "https://three.example/",
                    "https://b.example/")), uris.toString());
            assertTrue(new PDFTextStripper().getText(doc).contains("[Edge]"));
        }
    }

    @Test
    void closedDetailsAndNamedHeadingsKeepTheirLinks() throws IOException {
        String md = "<details>\n<summary>Contents</summary>\n\n- [Answer](#answer)\n</details>\n\n"
                + "<h3 name=\"answer\">The answer</h3>\n\nForty-two.\n";
        try (PDDocument doc = convert(write("faq.md", md), OPTIONS)) {
            List<PDPageXYZDestination> targets = new ArrayList<>();
            for (PDAnnotation a : doc.getPage(0).getAnnotations()) {
                if (a instanceof PDAnnotationLink link && !(link.getAction() instanceof PDActionURI)) {
                    PDDestination d = link.getDestination();
                    if (d == null && link.getAction() instanceof PDActionGoTo go) {
                        d = go.getDestination();
                    }
                    assertTrue(d instanceof PDPageXYZDestination, String.valueOf(d));
                    targets.add((PDPageXYZDestination) d);
                }
            }
            assertEquals(1, targets.size());
            PDPageXYZDestination target = targets.getFirst();
            assertEquals(0, target.retrievePageNumber());
            float heading = top(doc, "The answer");
            assertTrue(Math.abs(target.getTop() - heading) < 30, target.getTop() + " vs " + heading);
            assertTrue(new PDFTextStripper().getText(doc).contains("Answer"));
        }
    }

    private static float top(PDDocument doc, String text) throws IOException {
        float height = doc.getPage(0).getMediaBox().getHeight();
        float[] y = {-1};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String line, List<org.apache.pdfbox.text.TextPosition> positions) {
                if (y[0] < 0 && line.contains(text)) {
                    y[0] = height - positions.getFirst().getYDirAdj();
                }
            }
        };
        stripper.getText(doc);
        assertTrue(y[0] >= 0, text);
        return y[0];
    }

    @Test
    void aPageWiderThanThePaperIsShrunkToFit() throws IOException {
        StringBuilder wide = new StringBuilder("# Title\n\n|");
        StringBuilder rule = new StringBuilder("|");
        StringBuilder row = new StringBuilder("|");
        for (int c = 1; c <= 18; c++) {
            wide.append(" Column ").append(c).append(" |");
            rule.append("---|");
            row.append(" v1.").append(c).append(" |");
        }
        String table = wide + "\n" + rule + "\n" + row + "\n";
        assertTrue(titleHeight(write("wide.md", table)) < 0.8f * titleHeight(write("plain.md", "# Title\n\nText\n")));
    }

    private float titleHeight(Path md) throws IOException {
        try (PDDocument doc = convert(md, OPTIONS)) {
            float[] height = {0};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    if (height[0] == 0 && !positions.isEmpty()) {
                        height[0] = positions.getFirst().getHeightDir() * positions.getFirst().getYScale();
                    }
                }
            };
            stripper.getText(doc);
            return height[0];
        }
    }

    private static int images(PDDocument doc) throws IOException {
        int n = 0;
        for (PDPage page : doc.getPages()) {
            for (var name : page.getResources().getXObjectNames()) {
                if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void anOfficePackageNamedMarkdownIsNotReadAsText() throws IOException {
        Path file = dir.resolve("letter.md");
        Files.write(file, Fixtures.docx("Word body text"));
        IOException e = assertThrows(IOException.class, () -> convert(file, OPTIONS).close());
        assertTrue(e.getMessage().contains("ZIP file"), e.getMessage());
    }

    @Test
    void largeMarkdownIsRefused() throws IOException {
        Path file = dir.resolve("huge.md");
        Files.write(file, new byte[(int) HtmlInput.MAX_MARKDOWN_BYTES + 1]);
        IOException e = assertThrows(IOException.class, () -> convert(file, OPTIONS).close());
        assertTrue(e.getMessage().contains("Markdown file is larger than 16 MB"), e.getMessage());
    }
}
