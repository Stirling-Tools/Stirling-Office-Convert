package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.HtmlAssets;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class PositionedTest {

    @TempDir
    Path dir;

    record Word(String text, int page, float x, float y, float dir) {}

    record Pdf(List<Word> words, List<BufferedImage> pages, List<String> warnings) {

        Word first(String text) {
            return words.stream().filter(w -> w.text().equals(text)).findFirst().orElse(null);
        }

        List<Word> all(String text) {
            return words.stream().filter(w -> w.text().equals(text)).toList();
        }
    }

    private Pdf convert(Path in) throws IOException {
        return convert(in, OfficeToPdf.Options.defaults());
    }

    private Pdf convert(Path in, OfficeToPdf.Options options) throws IOException {
        Path out = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, options.timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<Word> words = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    int at = 0;
                    for (String word : text.split("[ \u00a0]")) {
                        String clean = word.strip();
                        if (!clean.isEmpty() && at < positions.size()) {
                            TextPosition p = positions.get(Math.min(at + word.indexOf(clean.charAt(0)),
                                    positions.size() - 1));
                            words.add(new Word(clean, getCurrentPageNo(), p.getXDirAdj(), p.getYDirAdj(), p.getDir()));
                        }
                        at += word.length() + 1;
                    }
                }
            };
            stripper.setSortByPosition(false);
            stripper.getText(doc);
            List<BufferedImage> pages = new ArrayList<>();
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                pages.add(renderer.renderImage(i, 1f));
            }
            return new Pdf(words, pages, r.warnings());
        }
    }

    private Pdf html(String name, String html) throws IOException {
        return convert(Files.writeString(dir.resolve(name), html));
    }

    private static String para(int words) {
        StringBuilder sb = new StringBuilder("<p>");
        for (int i = 0; i < words; i++) {
            sb.append(i % 7 == 0 ? "budget " : "regions ");
        }
        return sb.append("</p>").toString();
    }

    @Test
    void automaticHeightAbsoluteTextContinuesOnLaterPages() throws IOException {
        String lines = "<p>FirstLine</p>" + "<p>MiddleLine</p>".repeat(80) + "<p>LastLine</p>";
        Pdf pdf = html("fragmented.html", "<style>@page{size:300pt 400pt;margin:30pt}"
                + "p{margin:0;font-size:12pt}</style><div style='position:absolute;left:0;top:0;width:200pt'>"
                + lines + "</div>");
        assertTrue(pdf.pages().size() > 1);
        assertNotNull(pdf.first("LastLine"));
        assertTrue(pdf.first("LastLine").page() > 1);
        assertEquals(80, pdf.all("MiddleLine").size());
        assertTrue(pdf.words().stream().allMatch(word -> word.y() >= 30 && word.y() < 370), pdf.words().toString());
    }

    @Test
    void nestedAutomaticHeightAbsoluteTextContinuesWithoutLosingLines() throws IOException {
        String lines = "<p>NestedFirst</p>" + "<p>NestedMiddle</p>".repeat(80) + "<p>NestedLast</p>";
        Pdf pdf = html("nested-fragmented.html", "<style>@page{size:300pt 400pt;margin:30pt}"
                + "p{margin:0;font-size:12pt}</style><div style='position:absolute;left:0;top:0;width:200pt'>"
                + "<span style='position:absolute'><span style='position:absolute'>" + lines + "</span></span></div>");
        assertTrue(pdf.pages().size() > 1);
        assertNotNull(pdf.first("NestedLast"));
        assertTrue(pdf.first("NestedLast").page() > 1);
        assertEquals(80, pdf.all("NestedMiddle").size());
        assertTrue(pdf.words().stream().allMatch(word -> word.y() >= 30 && word.y() < 370), pdf.words().toString());
    }

    @Test
    void fixedAndExplicitHeightBoxesDoNotAddContinuationPages() throws IOException {
        String lines = "<p>MiddleLine</p>".repeat(80) + "<p>LastLine</p>";
        for (String style : List.of("position:fixed", "position:absolute;height:100pt")) {
            Pdf pdf = html("unfragmented-" + style.hashCode() + ".html",
                    "<style>@page{size:300pt 400pt;margin:30pt}p{margin:0;font-size:12pt}</style><div style='"
                            + style + ";left:0;top:0;width:200pt'>" + lines + "</div>");
            assertEquals(1, pdf.pages().size());
        }
    }

    @Test
    void continuationOntoAnExistingBodyPageDoesNotDuplicateTheBox() throws IOException {
        Pdf pdf = html("existing-pages.html", "<style>@page{size:300pt 400pt;margin:30pt}"
                + "p{margin:0;font-size:12pt}</style><div style='position:absolute;left:0;top:0;width:200pt'>"
                + "<p>AbsoluteMiddle</p>".repeat(30) + "</div>" + "<p>BodyMiddle</p>".repeat(30));
        assertEquals(30, pdf.all("AbsoluteMiddle").size());
        assertEquals(30, pdf.all("BodyMiddle").size());
        assertTrue(pdf.pages().size() >= 2);
    }

    @Test
    void fixedLayerOverflowKeepsItsDrawOnceFallback() throws IOException {
        Pdf pdf = html("fixed-overflow.html", "<style>@page{size:300pt 400pt;margin:30pt}"
                + "p{margin:0;font-size:12pt}</style>" + "<span style='position:fixed'></span>".repeat(64)
                + "<div style='position:fixed;left:0;top:0;width:200pt'>"
                + "<p>FixedMiddle</p>".repeat(80) + "</div>");
        assertEquals(1, pdf.pages().size());
        assertTrue(pdf.warnings().contains(HtmlDocument.FIXED_LAYER_LIMIT_WARNING));
    }

    @Test
    void absoluteContinuationRespectsThePageLimit() throws IOException {
        Path in = Files.writeString(dir.resolve("limited.html"), "<style>@page{size:300pt 400pt;margin:30pt}"
                + "p{margin:0;font-size:12pt}</style><div style='position:absolute;left:0;top:0;width:200pt'>"
                + "<p>LimitedMiddle</p>".repeat(80) + "</div>");
        Path out = dir.resolve("limited.pdf");
        OfficeToPdf.Result result = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().maxPages(2));
        assertEquals(2, result.pages());
        assertTrue(result.truncated());
        assertTrue(result.pageLimitReached());
        try (PDDocument pdf = Loader.loadPDF(out.toFile())) {
            assertEquals(2, pdf.getNumberOfPages());
        }
    }

    private static byte[] png(int w, int h, Color a, Color b) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (x < w / 2 ? a : b).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String dataUri(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private static Color at(BufferedImage page, float x, float y) {
        return new Color(page.getRGB(Math.round(x), Math.round(y)));
    }

    private static boolean near(Color c, Color want) {
        return Math.abs(c.getRed() - want.getRed()) < 40 && Math.abs(c.getGreen() - want.getGreen()) < 40
                && Math.abs(c.getBlue() - want.getBlue()) < 40;
    }

    private static int verticalLines(BufferedImage page, int minLength) {
        int lines = 0;
        for (int x = 0; x < page.getWidth(); x++) {
            int run = 0;
            int best = 0;
            for (int y = 0; y < page.getHeight(); y++) {
                Color c = new Color(page.getRGB(x, y));
                run = c.getRed() < 120 && c.getGreen() < 120 && c.getBlue() < 120 ? run + 1 : 0;
                best = Math.max(best, run);
            }
            lines += best >= minLength ? 1 : 0;
        }
        return lines;
    }

    @Test
    void aCardInsideAColumnFramesItsColumnAndInlineMarginsSpaceLinks() throws IOException {
        String card = "<div class=col><div class=card><b>Plan</b><p>" + "words ".repeat(20) + "</p></div></div>";
        Pdf pdf = html("cards.html", "<html><head><style>.row{display:flex}.col{flex:1;padding:0 6px}"
                + ".card{border:2px solid #000;padding:8px}nav a{margin-left:24px}</style></head><body>"
                + "<nav><a href=#a>Home</a><a href=#b>Pricing</a></nav><div class=row>" + card + card + card
                + "</div></body></html>");
        assertTrue(verticalLines(pdf.pages().get(0), 40) >= 6, "card frames apart");
        Word home = pdf.first("Home");
        Word pricing = pdf.first("Pricing");
        assertNotNull(home, pdf.words().toString());
        assertNotNull(pricing, pdf.words().toString());
        assertTrue(pricing.x() - home.x() > 40, home + " " + pricing);
    }

    @Test
    void wrappingRowsPlaceItemsOfDifferentWidthsSideBySide() throws IOException {
        Pdf pdf = html("wrap.html", "<html><head><style>.row{display:flex;flex-wrap:wrap}.a{width:66%}.b{width:33%}"
                + "</style></head><body><div class=row><div class=a><table><tr><td>Storage</td><td>10 GB</td></tr>"
                + "</table></div><div class=b><p>Secure</p></div></div></body></html>");
        Word storage = pdf.first("Storage");
        Word secure = pdf.first("Secure");
        assertNotNull(storage, pdf.words().toString());
        assertNotNull(secure, pdf.words().toString());
        assertEquals(storage.y(), secure.y(), 12);
        assertTrue(secure.x() > storage.x() + 200, storage + " " + secure);
    }

    @Test
    void aCentredButtonTableInsideACellShrinksToItsContent() throws IOException {
        Pdf pdf = html("button.html", "<html><body><table width=600><tr><td><p>Intro text</p><table align=center>"
                + "<tr><td style=\"background:#e4572e;padding:12px 24px\"><a href=\"https://e.com\" "
                + "style=\"color:#fff\">Shop now</a></td></tr></table></td></tr></table></body></html>");
        BufferedImage page = pdf.pages().get(0);
        int widest = 0;
        for (int y = 0; y < page.getHeight(); y++) {
            int run = 0;
            for (int x = 0; x < page.getWidth(); x++) {
                run = near(new Color(page.getRGB(x, y)), new Color(0xe4572e)) ? run + 1 : 0;
                widest = Math.max(widest, run);
            }
        }
        assertTrue(widest > 40 && widest < 160, "button width " + widest);
    }

    @Test
    void anUnstyledPageHasChromesPrintMarginsAndTheBodysSideMargins() throws IOException {
        Pdf plain = html("plain.html", "<html><body><p>Leftmost</p></body></html>");
        assertEquals(27.75 + 6, plain.first("Leftmost").x(), 1);
        Pdf flush = html("flush.html", "<html><head><style>body{margin:0}</style></head><body><p>Leftmost</p></body>"
                + "</html>");
        assertEquals(27.75, flush.first("Leftmost").x(), 1);
        Pdf padded = html("padded.html", "<html><head><style>@page{margin:36pt}body{margin:0;padding:0 24pt}</style>"
                + "</head><body><p>Leftmost</p></body></html>");
        assertEquals(36 + 24, padded.first("Leftmost").x(), 1);
    }

    @Test
    void aPageBreakBeforeATableOrABoxedRowStartsANewPage() throws IOException {
        for (String after : new String[] {"<table border=1><tr><td>Second</td><td>cell</td></tr></table>",
                "<div style=\"display:flex;gap:20px\"><div style=\"flex:1;border:1px solid\">Second</div>"
                        + "<div style=\"flex:1;border:1px solid\">box</div></div>"}) {
            Pdf before = html("after.html", "<html><body><p style=\"page-break-after:always\">First</p>" + after
                    + "</body></html>");
            assertEquals(2, before.pages().size(), after);
            assertEquals(2, before.first("Second").page(), after);
            String own = after.replace("<table ", "<table style=\"page-break-before:always\" ")
                    .replace("style=\"display:flex", "style=\"page-break-before:always;display:flex");
            Pdf ownBreak = html("own.html", "<html><body><p>First</p>" + own + "</body></html>");
            assertEquals(2, ownBreak.pages().size(), own);
        }
    }

    @Test
    void anAbsoluteBoxSitsAtItsOffsetsAndLeavesTheFlowAlone() throws IOException {
        Pdf with = html("abs.html", "<html><head><style>@page{size:A4;margin:72pt}"
                + ".wrap{position:relative}.stamp{position:absolute;top:120pt;left:200pt}</style></head><body>"
                + "<div class=wrap><p>Opening line</p><div class=stamp>STAMPED</div><p>Closing line</p></div>"
                + "</body></html>");
        Pdf without = html("flow.html", "<html><head><style>@page{size:A4;margin:72pt}</style></head><body>"
                + "<div><p>Opening line</p><p>Closing line</p></div></body></html>");
        Word stamp = with.first("STAMPED");
        Word opening = with.first("Opening");
        assertNotNull(opening, with.words().toString());
        assertNotNull(stamp, with.words().toString());
        assertEquals(72 + 6 + 200, stamp.x(), 4);
        assertEquals(opening.y() + 120, stamp.y(), 16);
        assertEquals(without.first("Closing").y(), with.first("Closing").y(), 3);
    }

    @Test
    void aBoxPlacedFromTheBottomRightHangsUnderTheLastLineOfItsBlock() throws IOException {
        Pdf p = html("bottom.html", "<html><head><style>@page{size:A4;margin:72pt}"
                + ".card{position:relative;border:1px solid black;padding:10pt 10pt 30pt}"
                + ".foot{position:absolute;bottom:4pt;right:6pt}</style></head><body>"
                + "<div class=card>" + para(40) + "<div class=foot>Updated</div></div><p>After</p></body></html>");
        Word foot = p.first("Updated");
        float lastLine = p.words().stream().filter(w -> w.text().equals("regions")).map(Word::y)
                .max(Float::compare).orElseThrow();
        assertTrue(foot.y() > lastLine, foot + " below " + lastLine);
        assertTrue(foot.y() < p.first("After").y(), foot + " above the next block");
        assertTrue(foot.x() > 595 - 72 - 80, "right aligned: " + foot);
    }

    @Test
    void aFixedBoxRepeatsOnEveryPageAtTheSamePlace() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            body.append(para(60));
        }
        Pdf p = html("fixed.html", "<html><head><style>@page{size:A4;margin:100pt 72pt}"
                + ".head{position:fixed;top:-80pt;left:0;background:#0f766e;color:white}</style></head><body>"
                + "<div class=head>Letterhead</div>" + body + "</body></html>");
        List<Word> heads = p.all("Letterhead");
        assertTrue(p.pages().size() >= 3, "pages: " + p.pages().size());
        assertEquals(p.pages().size(), heads.size(), heads.toString());
        for (Word w : heads) {
            assertEquals(heads.get(0).y(), w.y(), 0.5f);
            assertTrue(w.y() < 100, "in the top margin: " + w);
        }
        assertTrue(p.words().stream().filter(w -> !w.text().equals("Letterhead")).allMatch(w -> w.y() > 95),
                "the body keeps its margin " + p.words().stream().filter(w -> w.y() <= 95).toList());
    }

    @Test
    void anAbsoluteBoxWithNoPositionedAncestorIsPlacedOnTheFirstPageArea() throws IOException {
        Pdf p = html("page.html", "<html><head><style>@page{size:A4;margin:50pt}"
                + ".tag{position:absolute;top:300pt;left:100pt}</style></head><body>"
                + "<p>Top</p><div class=tag>Pinned</div></body></html>");
        Word w = p.first("Pinned");
        assertEquals(150, w.x(), 4);
        assertEquals(350, w.y(), 16);
    }

    @Test
    void aRotatedBlockIsTurnedWhereItStandsAndKeepsItsRoom() throws IOException {
        Pdf p = html("turn.html", "<html><head><style>body{font-size:12pt;line-height:14pt}p{margin:0}</style>"
                + "</head><body><p>Before</p>"
                + "<div style=\"width:200pt;height:40pt;transform:rotate(90deg)\">Turned</div><p>After</p></body></html>");
        Word turned = p.first("Turned");
        assertNotNull(turned, p.words().toString());
        assertEquals(270, turned.dir(), 1);
        float gap = p.first("After").y() - p.first("Before").y();
        assertTrue(gap > 40 && gap < 80, "the block keeps its 40pt of room: " + gap);
    }

    @Test
    void aRelativeShiftMovesItsBlockButNotTheNext() throws IOException {
        Pdf moved = html("rel.html", "<html><body><p>Above</p><p style=\"position:relative;left:60pt\">Shifted</p>"
                + "<p>Below</p></body></html>");
        Pdf still = html("still.html", "<html><body><p>Above</p><p>Shifted</p><p>Below</p></body></html>");
        assertEquals(still.first("Shifted").x() + 60, moved.first("Shifted").x(), 2);
        assertEquals(still.first("Below").y(), moved.first("Below").y(), 2);
    }

    @Test
    void aBackgroundPictureFromADataUriCoversItsBox() throws IOException {
        String pic = dataUri(png(40, 40, new Color(200, 30, 30), new Color(200, 30, 30)));
        Pdf p = html("bg.html", "<html><head><style>@page{size:A4;margin:72pt}"
                + ".hero{height:150pt;background-image:url(" + pic + ");background-size:cover;color:white}"
                + "</style></head><body><div class=hero>Welcome</div></body></html>");
        Word w = p.first("Welcome");
        BufferedImage page = p.pages().get(0);
        assertTrue(near(at(page, 300, w.y() + 60), new Color(200, 30, 30)), "covered: " + at(page, 300, w.y() + 60));
        assertTrue(near(at(page, 120, w.y() + 120), new Color(200, 30, 30)), "to the bottom");
    }

    @Test
    void aRepeatingBackgroundIsTiledAcrossItsBox() throws IOException {
        String tile = dataUri(png(20, 20, new Color(250, 200, 0), new Color(0, 90, 200)));
        Pdf p = html("tile.html", "<html><head><style>@page{size:A4;margin:72pt}"
                + ".band{height:60pt;background:url(" + tile + ") repeat-x;background-size:15pt 15pt}"
                + "</style></head><body><div class=band>Band</div></body></html>");
        BufferedImage page = p.pages().get(0);
        float top = p.first("Band").y() - 10;
        int yellow = 0;
        int blue = 0;
        for (float x = 80; x < 500; x += 3.75f) {
            Color c = at(page, x, top + 7);
            yellow += near(c, new Color(250, 200, 0)) ? 1 : 0;
            blue += near(c, new Color(0, 90, 200)) ? 1 : 0;
        }
        assertTrue(yellow > 30 && blue > 30, yellow + " yellow, " + blue + " blue");
        assertFalse(near(at(page, 300, top + 40), new Color(0, 90, 200)), "repeat-x leaves the rows below empty");
    }

    @Test
    void aLinkedBackgroundIsReadOnlyFromThePackageItCameIn() throws IOException {
        byte[] green = png(30, 30, new Color(20, 160, 60), new Color(20, 160, 60));
        String page = "<html><head><style>@page{size:A4;margin:72pt}.box{height:100pt;"
                + "background:url(art/green.png);background-size:cover}</style></head><body>"
                + "<div class=box>Boxed</div></body></html>";
        Path z = dir.resolve("site.zip");
        try (OutputStream os = Files.newOutputStream(z); ZipOutputStream out = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : Map.of("index.html", page.getBytes(StandardCharsets.UTF_8),
                    "art/green.png", green).entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        Pdf zipped = convert(z, OfficeToPdf.Options.defaults().allowPackages(true));
        float y = zipped.first("Boxed").y() + 40;
        assertTrue(near(at(zipped.pages().get(0), 300, y), new Color(20, 160, 60)), "drawn from the package");
        Files.createDirectories(dir.resolve("art"));
        Files.write(dir.resolve("art/green.png"), green);
        Pdf loose = html("loose.html", page);
        assertFalse(near(at(loose.pages().get(0), 300, loose.first("Boxed").y() + 40), new Color(20, 160, 60)),
                "a loose page reads nothing beside it by default");
        assertTrue(loose.warnings().contains(HtmlImages.NOT_FETCHED), loose.warnings().toString());
        Pdf allowed = convert(dir.resolve("loose.html"),
                OfficeToPdf.Options.defaults().htmlAssets(HtmlAssets.folder(dir, "loose.html")));
        assertTrue(near(at(allowed.pages().get(0), 300, allowed.first("Boxed").y() + 40), new Color(20, 160, 60)),
                "drawn from beside the page once its folder is allowed");
    }

    @Test
    void transformsReduceToATurnAScaleAndAShift() {
        Transform r = Transform.of("rotate(-18deg)", 12, 100, 40);
        assertEquals(-18, r.rotation(), 0.01f);
        Transform m = Transform.of("matrix(0.866,0.5,-0.5,0.866,0,0)", 12, 100, 40);
        assertEquals(30, m.rotation(), 0.1f);
        assertEquals(1, m.scaleX(), 0.01f);
        Transform s = Transform.of("translate(10px, 50%) scale(1.5, 2)", 12, 100, 40);
        assertEquals(7.5f, s.dx(), 0.01f);
        assertEquals(20, s.dy(), 0.01f);
        assertEquals(1.5f, s.scaleX(), 0.01f);
        assertEquals(2, s.scaleY(), 0.01f);
        assertEquals(90, Transform.of("rotate(0.25turn)", 12, 1, 1).rotation(), 0.01f);
        assertTrue(Transform.of("skewX(10deg)", 12, 1, 1).none());
        assertTrue(Transform.of("rotate(nonsense)", 12, 1, 1).none());
    }

    @Test
    void theBackgroundShorthandKeepsItsPictureAndItsLayout() {
        Map<String, String> d = Css.declarations(
                "background: #f8fafc url('logo.png') no-repeat right 10px top 10px / 120px auto");
        assertEquals("logo.png", d.get("background-url"));
        assertEquals("no-repeat", d.get("background-repeat"));
        assertEquals("right 10px top 10px", d.get("background-position"));
        assertEquals("120px auto", d.get("background-size"));
        assertEquals("#f8fafc", d.get("background-color"));
        assertEquals("none", Css.declarations("background:#fff").get("background-url"));
    }

    @Test
    void aTransformTooLargeToMeasureIsLeftOut() throws IOException {
        assertTrue(Transform.of("matrix(1e200,1e200,1e200,1e200,0,0)", 12, 100, 40).none());
        Pdf p = html("huge.html", "<html><body><div style=\"transform:matrix(1e200,1e200,1e200,1e200,0,0)\">Kept</div>"
                + "<p>After</p></body></html>");
        assertNotNull(p.first("Kept"), p.words().toString());
    }

    @Test
    void deeplyNestedPositionedBoxesKeepTheRestOfThePage() throws IOException {
        for (String style : new String[] {"position:absolute;top:14pt;left:0", "transform:rotate(1deg)"}) {
            StringBuilder nested = new StringBuilder();
            for (int i = 0; i < 120; i++) {
                nested.append("<div style=\"").append(style).append("\">").append(String.format("L%03d ", i));
            }
            Pdf p = html("deep.html", "<html><body><p>Start</p>" + nested + "</div>".repeat(120)
                    + "<p>End</p></body></html>");
            assertNotNull(p.first("Start"), style + " " + p.warnings());
            assertNotNull(p.first("End"), style + " " + p.warnings());
            long levels = p.words().stream().filter(w -> w.text().matches("L\\d{3}")).count();
            assertTrue(levels >= 16, style + " " + levels);
        }
    }

    @Test
    void aRelativeShiftUpLeavesTheNextBlockWhereItWas() throws IOException {
        Pdf moved = html("up.html", "<html><body><div>Above</div><div style=\"position:relative;top:-10pt\">Shifted</div>"
                + "<div>Below</div></body></html>");
        Pdf still = html("level.html", "<html><body><div>Above</div><div>Shifted</div><div>Below</div></body></html>");
        assertEquals(still.first("Below").y(), moved.first("Below").y(), 2);
    }

    @Test
    void aBackgroundColourShowsThroughATransparentPicture() throws IOException {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 10; x++) {
                img.setRGB(x, y, 0xFFFAC800);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Pdf p = html("see.html", "<html><head><style>@page{size:A4;margin:72pt}"
                + ".box{height:100pt;background:#003366 url(" + dataUri(png.toByteArray()) + ");color:white}"
                + "</style></head><body><div class=box>Navy</div></body></html>");
        BufferedImage page = p.pages().get(0);
        float y = p.first("Navy").y() + 50;
        int navy = 0;
        int yellow = 0;
        for (float x = 80; x < 500; x += 3.75f) {
            Color c = at(page, x, y);
            navy += near(c, new Color(0, 51, 102)) ? 1 : 0;
            yellow += near(c, new Color(250, 200, 0)) ? 1 : 0;
        }
        assertTrue(navy > 30 && yellow > 30, navy + " navy, " + yellow + " yellow");
    }

    @Test
    void aLargeBackgroundSharedByBoxesIsDecodedOnce() throws IOException {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < 1600; y++) {
            for (int x = 0; x < 1600; x++) {
                img.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Pdf p = html("shared.html", "<html><head><style>.box{height:80pt;background:url(" + dataUri(png.toByteArray())
                + ") no-repeat}</style></head><body>" + "<div class=box>Box</div>".repeat(8) + "</body></html>");
        assertTrue(png.size() > 6 << 20, "size " + png.size());
        assertTrue(p.warnings().stream().noneMatch(w -> w.contains("96 MB")), p.warnings().toString());
    }

    @Test
    void onlyTheFirstFixedBoxesRepeatOnEveryPage() throws IOException {
        StringBuilder boxes = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            boxes.append("<div style=\"position:fixed;top:").append(i % 10 * 14).append("pt;left:").append(i / 10 * 45)
                    .append("pt\">").append(String.format("F%03d", i)).append("</div>");
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            body.append(para(60));
        }
        Pdf p = html("many-fixed.html", "<html><body>" + boxes + body + "</body></html>");
        assertTrue(p.pages().size() >= 3, "pages: " + p.pages().size());
        assertEquals(p.pages().size(), p.all("F000").size());
        assertEquals(p.pages().size(), p.all("F063").size());
        assertEquals(1, p.all("F064").size());
        assertEquals(1, p.all("F099").size());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("repeated on every page")), p.warnings().toString());
    }

    @Test
    void boxesWithTheSameRepeatingBackgroundShareOnePicture() throws IOException {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(3);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                img.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Path in = Files.writeString(dir.resolve("cards.html"), "<html><head><style>.card{height:300pt;background:url("
                + dataUri(png.toByteArray()) + ");background-size:20pt 20pt}</style></head><body>"
                + "<div class=card>Card</div>".repeat(40) + "</body></html>");
        Path out = dir.resolve("cards.pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults());
        Set<Object> pictures = Collections.newSetFromMap(new IdentityHashMap<>());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            for (PDPage page : doc.getPages()) {
                for (COSName name : page.getResources().getXObjectNames()) {
                    PDXObject x = page.getResources().getXObject(name);
                    pictures.add(x.getCOSObject());
                }
            }
            assertTrue(doc.getNumberOfPages() >= 10, "pages " + doc.getNumberOfPages());
        }
        assertEquals(1, pictures.size(), r.warnings().toString());
    }

    @Test
    void anAbsoluteBoxInANarrowBlockKeepsItsWordsWhole() throws IOException {
        Pdf p = html("narrow.html", "<html><body><div style=\"position:relative;width:40px\">"
                + "<div style=\"position:absolute;top:0;left:0\">Overflowing caption</div></div></body></html>");
        assertNotNull(p.first("Overflowing"), p.words().toString());
        assertNotNull(p.first("caption"), p.words().toString());
    }
}
