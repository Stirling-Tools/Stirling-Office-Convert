package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.extract.OperatorBudget;
import stirling.software.officeconvert.model.Picture;

class ArtBudgetTest {

    private static final int FILLER = 400_000;

    @Test
    void manySeparatePiecesOnALongPageStopAtTheOperatorBudget() throws Exception {
        try (PDDocument doc = manyPieces()) {
            PDPage page = doc.getPage(0);
            AffineTransform toDisplay = new AffineTransform(1, 0, 0, -1, 0, 792);
            PaintOrder paint = PaintOrder.read(page, toDisplay, 612, 792);
            Art art = new Art(doc, new Store(), 72f, false);
            art.pieces(paint, Set.of(), 0, toDisplay, 612, 792);
            long perRender = 3L * FILLER + 1000;
            assertTrue(art.operators() <= 4 * OperatorBudget.MAX_OPERATORS + perRender, () -> "" + art.operators());
        }
    }

    private static PDDocument manyPieces() throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage();
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        page.setResources(resources);
        doc.addPage(page);
        StringBuilder sb = new StringBuilder();
        sb.append("0 0 m 1 0 l n\n".repeat(FILLER));
        for (int i = 0; i < 150; i++) {
            int x = 20 + (i % 15) * 38;
            int y = 40 + (i / 15) * 70;
            sb.append(i % 7 / 7.0).append(" 0.5 0.5 rg ").append(x).append(' ').append(y).append(" 20 20 re f\n");
            sb.append("0 g BT /F1 8 Tf ").append(x).append(' ').append(y + 30).append(" Td (w) Tj ET\n");
        }
        PDStream stream = new PDStream(doc);
        try (OutputStream out = stream.createOutputStream()) {
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }
        page.setContents(stream);
        return doc;
    }

    private static final class Store implements PlacedContent.MediaStore {
        private int next;

        @Override
        public Picture.MediaRef media(Object key) {
            return null;
        }

        @Override
        public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) {
            return new Picture.MediaRef("m" + next++, ext, pixelWidth, pixelHeight);
        }
    }
}
