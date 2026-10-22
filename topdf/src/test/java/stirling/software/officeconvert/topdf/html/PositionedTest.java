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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Path out = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults()
                .timeout(Duration.ofMinutes(2)));
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
        assertEquals(72 + 200, stamp.x(), 4);
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
        Pdf p = html("turn.html", "<html><body><p>Before</p>"
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
        Pdf zipped = convert(z);
        float y = zipped.first("Boxed").y() + 40;
        assertTrue(near(at(zipped.pages().get(0), 300, y), new Color(20, 160, 60)), "drawn from the package");
        Files.createDirectories(dir.resolve("art"));
        Files.write(dir.resolve("art/green.png"), green);
        Pdf loose = html("loose.html", page);
        assertFalse(near(at(loose.pages().get(0), 300, loose.first("Boxed").y() + 40), new Color(20, 160, 60)),
                "a loose page reads nothing beside it");
        assertTrue(loose.warnings().contains(HtmlImages.NOT_FETCHED), loose.warnings().toString());
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
}
