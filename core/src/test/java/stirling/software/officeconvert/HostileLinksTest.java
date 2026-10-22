package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.zip.ZipFile;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostileLinksTest {

    @TempDir
    Path dir;

    @Test
    void aSharedArrayOfManyLinksConvertsQuicklyAndKeepsTheRealLink() throws Exception {
        Path pdf = dir.resolve("links.pdf");
        try (PDDocument d = new PDDocument()) {
            COSArray annots = new COSArray();
            annots.add(link(36, 774, 100, 10, "https://real.example/"));
            for (int i = 0; i < 50_000; i++) {
                annots.add(link(i % 600, 0, 1, 1, "https://e.x/" + i));
            }
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.COURIER);
            Random random = new Random(3);
            for (int p = 0; p < 20; p++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                d.addPage(page);
                page.getCOSObject().setItem(COSName.ANNOTS, annots);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText();
                    cs.setFont(font, 3);
                    cs.setLeading(3.8f);
                    cs.newLineAtOffset(36, 776);
                    for (int l = 0; l < 200; l++) {
                        cs.showText(words(random));
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            d.save(pdf.toFile());
        }
        Path docx = dir.resolve("links.docx");
        assertTimeoutPreemptively(Duration.ofSeconds(40), () -> OfficeConvert.convert(pdf, docx,
                OfficeConvert.Settings.defaults().timeout(Duration.ofSeconds(35))));
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String rels = new String(zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(rels.contains("https://real.example/"), rels.length() > 2000 ? rels.substring(0, 2000) : rels);
        }
    }

    private static String words(Random random) {
        StringBuilder b = new StringBuilder();
        while (b.length() < 100) {
            for (int i = 1 + random.nextInt(9); i > 0; i--) {
                b.append((char) ('a' + random.nextInt(26)));
            }
            b.append(' ');
        }
        return b.toString();
    }

    private static COSDictionary link(float x, float y, float w, float h, String uri) {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, w, h));
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        link.setAction(action);
        return link.getCOSObject();
    }
}
