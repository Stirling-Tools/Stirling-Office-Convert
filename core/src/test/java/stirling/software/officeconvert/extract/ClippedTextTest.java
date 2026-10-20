package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;

class ClippedTextTest {

    @Test
    void textOutsideTheClipIsNotRead() throws IOException {
        assertEquals("Shown", text(true, false));
    }

    @Test
    void textIsReadWithoutAClip() throws IOException {
        assertEquals("HiddenShown", text(false, false));
    }

    @Test
    void invisibleTextIsReadOutsideTheClip() throws IOException {
        assertEquals("HiddenShown", text(true, true));
    }

    @Test
    void clipWithoutAPathKeepsTheText() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);
            document.addPage(page);
            String content = "BT /F1 18 Tf 72 600 Td (Before) Tj ET\nW n\nBT /F1 18 Tf 72 400 Td (After) Tj ET\n";
            PDStream stream = new PDStream(document);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            assertEquals("BeforeAfter", read(document));
        }
    }

    private static String text(boolean clipped, boolean invisible) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                if (clipped) {
                    content.addRect(50, 380, 300, 60);
                    content.clip();
                }
                write(content, 72, 600, "Hidden", invisible);
                write(content, 72, 400, "Shown", invisible);
                content.restoreGraphicsState();
            }
            return read(document);
        }
    }

    private static String read(PDDocument document) throws IOException {
        List<PageData> pages = new ArrayList<>();
        new PageReader(document).read(0, 0, false, pages::add);
        PageData page = pages.getFirst();
        return Stream.concat(page.glyphs().stream(), page.hidden().stream())
                .sorted((a, b) -> Float.compare(a.baseline, b.baseline))
                .map(g -> g.text)
                .collect(Collectors.joining());
    }

    private static void write(PDPageContentStream content, float x, float y, String text, boolean invisible)
            throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
        if (invisible) {
            content.setRenderingMode(RenderingMode.NEITHER);
        }
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
