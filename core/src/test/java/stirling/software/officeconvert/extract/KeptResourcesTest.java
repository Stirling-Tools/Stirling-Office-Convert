package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class KeptResourcesTest {

    @Test
    void fontsStayLoadedFromOnePageToTheNext() throws IOException {
        try (PDDocument document = Loader.loadPDF(twoPages())) {
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 1, true, pages::add);
            assertEquals(2, pages.size());
            for (PDPage page : document.getPages()) {
                var fonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
                for (COSName name : fonts.keySet()) {
                    assertNotNull(document.getResourceCache().getFont((COSObject) fonts.getItem(name)));
                }
            }
        }
    }

    private static byte[] twoPages() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText("Page " + i);
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
