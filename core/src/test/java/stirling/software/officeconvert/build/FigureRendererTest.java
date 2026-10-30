package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.ImageBudget;

import stirling.software.officeconvert.extract.GreyGroupPages;

class FigureRendererTest {

    @Test
    void aDarkGreyInAnIsolatedGreyGroupKeepsItsTone() throws Exception {
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICEGRAY)) {
            int rgb = new FigureRenderer(doc, true).renderImage(0).getRGB(100, 100);
            assertEquals(GreyGroupPages.GREY, rgb & 0xFF, 1, Integer.toHexString(rgb));
        }
    }

    private static final int SIDE = 10_000;

    private static PDDocument bilevelPage(boolean stencil, String paint) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(new PDRectangle(200, 200));
        doc.addPage(page);
        COSStream image = doc.getDocument().createCOSStream();
        image.setItem(COSName.TYPE, COSName.XOBJECT);
        image.setItem(COSName.SUBTYPE, COSName.IMAGE);
        image.setInt(COSName.WIDTH, SIDE);
        image.setInt(COSName.HEIGHT, SIDE);
        image.setInt(COSName.BITS_PER_COMPONENT, 1);
        if (stencil) {
            image.setBoolean(COSName.IMAGE_MASK, true);
        } else {
            image.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        }
        try (OutputStream out = image.createOutputStream(COSName.FLATE_DECODE)) {
            byte[] row = new byte[SIDE / 8];
            for (int y = 0; y < SIDE; y++) {
                out.write(row);
            }
        }
        COSDictionary xobjects = new COSDictionary();
        xobjects.setItem(COSName.getPDFName("Im0"), image);
        COSDictionary resources = new COSDictionary();
        resources.setItem(COSName.XOBJECT, xobjects);
        page.getCOSObject().setItem(COSName.RESOURCES, resources);
        COSStream content = doc.getDocument().createCOSStream();
        try (OutputStream out = content.createOutputStream()) {
            out.write((paint + " q 200 0 0 200 0 0 cm /Im0 Do Q").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, content);
        return doc;
    }

    @Test
    void aBilevelScanTooLargeToDecodeWholeIsDrawnSubsampled() throws Exception {
        try (PDDocument doc = bilevelPage(false, "")) {
            PDImageXObject image = (PDImageXObject) doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
            assertEquals(false, ImageBudget.affordable(image));
            int rgb = new FigureRenderer(doc, true).renderImage(0).getRGB(100, 100);
            assertEquals(0x000000, rgb & 0xFFFFFF, Integer.toHexString(rgb));
        }
    }

    @Test
    void aStencilTooLargeToDecodeWholeIsDrawnSubsampled() throws Exception {
        try (PDDocument doc = bilevelPage(true, "1 0 0 rg")) {
            int rgb = new FigureRenderer(doc, true).renderImage(0).getRGB(100, 100);
            assertEquals(0xFF0000, rgb & 0xFFFFFF, Integer.toHexString(rgb));
        }
    }
}
