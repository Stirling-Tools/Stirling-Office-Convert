package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PageIndexTest {

    @Test
    void findsTheSamePagesAsThePageTreeThroughNestedNodes() throws IOException {
        try (PDDocument document = new PDDocument()) {
            COSDictionary root = document.getDocumentCatalog().getPages().getCOSObject();
            COSArray rootKids = new COSArray();
            int pages = 0;
            for (int n = 0; n < 4; n++) {
                COSDictionary node = new COSDictionary();
                node.setItem(COSName.TYPE, COSName.PAGES);
                node.setItem(COSName.PARENT, root);
                node.setItem(COSName.CROP_BOX, new PDRectangle(0, 0, 100 + n, 200).getCOSArray());
                COSArray kids = new COSArray();
                for (int p = 0; p <= n; p++) {
                    PDPage page = new PDPage(new PDRectangle(0, 0, 300 + pages, 400));
                    page.getCOSObject().setItem(COSName.PARENT, node);
                    if (p % 2 == 1) {
                        page.getCOSObject().removeItem(COSName.TYPE);
                        page.setCropBox(new PDRectangle(10, 10, 50 + pages, 60));
                    }
                    kids.add(page.getCOSObject());
                    pages++;
                }
                node.setItem(COSName.KIDS, kids);
                node.setInt(COSName.COUNT, n + 1);
                rootKids.add(node);
            }
            root.setItem(COSName.KIDS, rootKids);
            root.setInt(COSName.COUNT, pages);

            PageIndex index = new PageIndex(document);
            int past = pages;
            for (int i = pages - 1; i >= 0; i--) {
                String found = index.cropBox(i).toString();
                assertEquals(document.getPage(i).getCropBox().toString(), found);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> index.cropBox(past));
            assertThrows(IndexOutOfBoundsException.class, () -> index.cropBox(-1));
        }
    }
}
