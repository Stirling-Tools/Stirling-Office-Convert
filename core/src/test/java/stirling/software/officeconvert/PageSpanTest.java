package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageSpanTest {

    private static final String EMBEDDED = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final float SIZE = 11;
    private static final float LEFT = 72;
    private static final float WIDTH = 451;
    private static final int PAGE_LINES = 48;
    private static final String[] WORDS = {"the", "committee", "reviewed", "every", "budget", "line", "with", "care", "and",
        "noted", "where", "spending", "ran", "ahead", "of", "plan", "across", "each", "quarter"};

    @TempDir Path dir;

    @Test
    void cutParagraphKeepsItsPagesInWordsOwnFont() throws Exception {
        List<String> lines = new ArrayList<>();
        Path pdf = makePdf(true, lines);
        String lastOnPage = lastWord(lines.get(PAGE_LINES - 1));
        String firstNext = lines.get(PAGE_LINES).split(" ")[0];

        String xml = entry(convert(pdf, "span.docx"), "word/document.xml");
        int cut = xml.indexOf("<w:br w:type=\"page\"/>");
        assertTrue(cut > 0, "the page end is a break inside the paragraph");
        String before = xml.substring(xml.lastIndexOf("<w:p>", cut), cut).replaceAll("<[^>]+>", "").strip();
        String after = xml.substring(cut, xml.indexOf("</w:p>", cut)).replaceAll("<[^>]+>", "").strip();
        assertTrue(before.endsWith(lastOnPage), "the page's last word comes before the break: " + tail(before));
        assertTrue(after.startsWith(firstNext + " "), "the next page's words follow it in the same paragraph");
        assertFalse(before.contains("."), "the paragraph starts on the first page");

        Path odt = dir.resolve("span.odt");
        PdfToOdt.convert(pdf, odt, PdfToDocx.Options.defaults());
        String content = entry(odt, "content.xml");
        int broken = content.indexOf("fo:break-before=\"page\"");
        assertTrue(broken > 0, "OpenDocument opens the rest on a new page");
        String style = content.substring(content.lastIndexOf("style:name=\"", broken) + 12);
        style = style.substring(0, style.indexOf('"'));
        int rest = content.indexOf("<text:p text:style-name=\"" + style + "\">");
        assertTrue(rest > 0 && content.substring(rest).replaceAll("<[^>]+>", "").startsWith(firstNext + " "),
                "the paragraph with the page break holds the next page's words");
    }

    @Test
    void cutParagraphKeepsItsPagesInAStandInFont() throws Exception {
        List<String> lines = new ArrayList<>();
        Path pdf = makePdf(false, lines);
        String xml = entry(convert(pdf, "stand-in.docx"), "word/document.xml");
        assertTrue(xml.contains("<w:br w:type=\"page\"/>"), "the page end is a break inside the paragraph");
        String joint = lines.get(PAGE_LINES - 1);
        boolean together = false;
        for (String para : xml.split("</w:p>")) {
            String text = para.replaceAll("<[^>]+>", "");
            together |= text.contains(joint) && text.contains(lines.get(PAGE_LINES));
        }
        assertTrue(together, "the two pages' lines are one paragraph");
    }

    private Path convert(Path pdf, String name) throws IOException {
        Path docx = dir.resolve(name);
        PdfToDocx.convert(pdf, docx, PdfToDocx.Options.defaults());
        return docx;
    }

    private static List<String> wrap(PDFont font, int count) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int w = 0;
        while (out.size() < count) {
            String word = WORDS[w++ % WORDS.length];
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(next) / 1000 * SIZE > WIDTH) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(next);
            }
        }
        out.set(count - 1, out.get(count - 1) + ".");
        return out;
    }

    private static String lastWord(String line) {
        String[] words = line.split(" ");
        return words[words.length - 1];
    }

    private static String tail(String s) {
        return s.substring(Math.max(0, s.length() - 40));
    }

    private Path makePdf(boolean embedded, List<String> lines) throws IOException {
        Path pdf = dir.resolve(embedded ? "span.pdf" : "flow.pdf");
        try (PDDocument doc = new PDDocument(); InputStream ttf = PageSpanTest.class.getResourceAsStream(EMBEDDED)) {
            PDFont font = embedded ? PDType0Font.load(doc, ttf) : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            lines.addAll(wrap(font, 80));
            for (int page = 0; page * PAGE_LINES < lines.size(); page++) {
                PDPage p = new PDPage(PDRectangle.A4);
                doc.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    List<String> own = lines.subList(page * PAGE_LINES, Math.min(lines.size(), (page + 1) * PAGE_LINES));
                    for (int i = 0; i < own.size(); i++) {
                        cs.beginText();
                        cs.setFont(font, SIZE);
                        cs.newLineAtOffset(LEFT, 770 - i * 14);
                        cs.showText(own.get(i));
                        cs.endText();
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static String entry(Path zip, String name) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return new String(z.getInputStream(z.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
