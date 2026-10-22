package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class ClipBudgetTest {

    private static final int CLIPS = 100;

    private static final int SEGMENTS = 4900;

    private static final int LINES = 325;

    private static final String ROW = "abcdefghij".repeat(50);

    @Test
    void manyDetailedClipsOverDenseTextStayCheap() throws IOException {
        try (PDDocument document = denseTextUnderClips()) {
            List<PageData> pages = new ArrayList<>();
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> new PageReader(document).read(0, 0, false, pages::add));
            assertEquals(LINES * ROW.length(), pages.getFirst().glyphs().size());
        }
    }

    private static PDDocument denseTextUnderClips() throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        document.addPage(page);
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < CLIPS; c++) {
            for (int i = 0; i < SEGMENTS; i++) {
                double t = 2 * Math.PI * i / SEGMENTS;
                double radius = 700 + (i % 2) * 5 + c * 0.01;
                sb.append((float) (306 + radius * Math.cos(t))).append(' ').append((float) (396 + radius * Math.sin(t)))
                        .append(i == 0 ? " m\n" : " l\n");
            }
            sb.append("h W n\n");
        }
        sb.append("BT /F1 2 Tf 2 788 Td 2.4 TL\n");
        for (int l = 0; l < LINES; l++) {
            sb.append('(').append(ROW).append(") '\n");
        }
        sb.append("ET\n");
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(stream);
        return document;
    }
}
