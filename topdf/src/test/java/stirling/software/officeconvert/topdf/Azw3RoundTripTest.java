package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.html.Azw3Writer;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;

class Azw3RoundTripTest {

    private static final RunStyle BODY = new RunStyle("Arial", 11, false, false, false, false, 0, -1, 0, false);

    @TempDir
    Path dir;

    private static Paragraph paragraph(String text) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, BODY, null, -1));
        return p;
    }

    private static Paragraph heading(String text) {
        Paragraph p = paragraph(text);
        p.style = "Heading1";
        return p;
    }

    private Path book(int bodyParagraphs) throws IOException {
        return book(bodyParagraphs, "Opening words with a note");
    }

    private Path book(int bodyParagraphs, String opening) throws IOException {
        Path out = dir.resolve("book" + bodyParagraphs + opening.length() + ".azw3");
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        try (OutputStream os = Files.newOutputStream(out); Azw3Writer w = new Azw3Writer(os, Pictures.COMPACT)) {
            StyleSheet styles = new StyleSheet(BODY);
            styles.use("Heading1", new RunStyle("Arial", 18, true, false, false, false, 0, -1, 0, false));
            w.begin(styles, null);
            Picture.MediaRef ref = w.media(png.toByteArray(), "png", 8, 6, "pic");
            w.block(heading("First Chapter"));
            Paragraph cited = paragraph(opening);
            cited.inlines.add(new Inline.FootnoteRef(1, "1", false, BODY));
            cited.inlines.add(new Inline.Image(new Picture(ref, 40, 30)));
            w.block(cited);
            for (int i = 0; i < bodyParagraphs; i++) {
                w.block(paragraph("Filler paragraph number " + i + " with café 中文 text to cross records."));
            }
            w.block(heading("Second Chapter"));
            w.block(paragraph("Closing words of the book."));
            w.footnote(1, List.of(paragraph("The footnote explains it.")));
            Section last = new Section();
            w.finish(last, null, null, styles, "Round Trip", "Kay Writer");
        }
        return out;
    }

    @Test
    void ourReaderReadsWhatTheWriterWrites() throws IOException {
        for (int filler : new int[] {0, 600}) {
            Ebook book = MobiBook.read(book(filler));
            String html = book.book().html();
            int one = html.indexOf("First Chapter");
            int words = html.indexOf("Opening words with a note");
            int two = html.indexOf("Second Chapter");
            int note = html.indexOf("The footnote explains it.");
            assertTrue(one >= 0 && words > one && two > words && note > two, html);
            assertEquals("Round Trip", book.book().title());
            assertEquals("Kay Writer", book.book().author());
            if (filler > 0) {
                assertTrue(html.contains("Filler paragraph number " + (filler - 1) + " with café 中文"));
            }
            Matcher link = Pattern.compile("href=\"#(epub-c\\d+-[^\"]+)\"").matcher(html);
            assertTrue(link.find(), html);
            int target = html.indexOf("id=\"" + link.group(1) + "\"");
            assertTrue(target > two && html.substring(target).contains("The footnote explains it."), link.group(1));
            Matcher img = Pattern.compile("<img[^>]*src=\"/([^\"]+)\"").matcher(html);
            assertTrue(img.find(), html);
            byte[] picture = book.assets().read(img.group(1));
            assertTrue(picture != null && picture[1] == 'P', img.group(1));
        }
    }

    @Test
    void linkPlaceholderTextInTheBookDoesNotMoveLinks() throws IOException {
        String literal = "kindle:pos:fid:####:off:##########";
        String html = MobiBook.read(book(0, "Opening " + literal + " words")).book().html();
        assertTrue(html.contains("Opening " + literal + " words"), html);
        Matcher link = Pattern.compile("href=\"#(epub-c\\d+-[^\"]+)\"").matcher(html);
        assertTrue(link.find(), html);
        int target = html.indexOf("id=\"" + link.group(1) + "\"");
        assertTrue(target >= 0 && html.substring(target).contains("The footnote explains it."), link.group(1));
    }

    @Test
    void bookConvertsToPdfWithItsText() throws IOException {
        Path in = book(300);
        Path pdf = dir.resolve("book.pdf");
        OfficeToPdf.convert(in, pdf);
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertTrue(text.contains("Closing words of the book."), text);
            assertTrue(text.contains("Filler paragraph number 299"), text);
        }
    }
}
