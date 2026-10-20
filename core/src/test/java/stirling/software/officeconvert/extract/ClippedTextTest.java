package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class ClippedTextTest {

    @Test
    void textOutsideTheClipIsNotRead() throws IOException {
        assertEquals("Shown", text(true));
    }

    @Test
    void textIsReadWithoutAClip() throws IOException {
        assertEquals("HiddenShown", text(false));
    }

    private static String text(boolean clipped) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                if (clipped) {
                    content.addRect(50, 380, 300, 60);
                    content.clip();
                }
                write(content, 72, 600, "Hidden");
                write(content, 72, 400, "Shown");
                content.restoreGraphicsState();
            }
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            return pages.getFirst().glyphs().stream()
                    .sorted((a, b) -> Float.compare(a.baseline, b.baseline))
                    .map(g -> g.text)
                    .collect(Collectors.joining());
        }
    }

    private static void write(PDPageContentStream content, float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
