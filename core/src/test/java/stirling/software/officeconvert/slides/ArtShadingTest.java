package stirling.software.officeconvert.slides;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.model.Picture;

class ArtShadingTest {

    @Test
    void aRadialShadingAloneIsDrawnAtScreenResolution() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage shaded = new PDPage(new PDRectangle(720, 405));
            PDPage drawn = new PDPage(new PDRectangle(720, 405));
            doc.addPage(shaded);
            doc.addPage(drawn);
            try (PDPageContentStream cs = new PDPageContentStream(doc, shaded)) {
                cs.addRect(0, 0, 360, 405);
                cs.clip();
                cs.shadingFill(radial());
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, drawn)) {
                cs.setStrokingColor(0.2f, 0.4f, 0.8f);
                cs.setLineWidth(6);
                cs.moveTo(0, 50);
                cs.curveTo(100, 300, 260, 300, 360, 50);
                cs.stroke();
            }
            assertEquals(96f, dpi(doc, 0), 1f);
            assertEquals(150f, dpi(doc, 1), 1f);
        }
    }

    private static float dpi(PDDocument doc, int index) {
        AffineTransform toDisplay = new AffineTransform(1, 0, 0, -1, 0, 405);
        PaintOrder paint = PaintOrder.read(doc.getPage(index), toDisplay, 720, 405);
        Store store = new Store();
        List<Art.Piece> pieces = new Art(doc, store, 150f, false).pieces(paint, Set.of(), index, toDisplay, 720, 405);
        assertEquals(1, pieces.size());
        return store.made.get(0).pixelWidth() * 72f / pieces.get(0).picture().frame().width();
    }

    private static PDShading radial() {
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        fn.setItem(COSName.DOMAIN, floats(0, 1));
        fn.setItem(COSName.C0, floats(1f, 0.9f, 0.4f));
        fn.setItem(COSName.C1, floats(0.6f, 0.1f, 0.3f));
        fn.setInt(COSName.N, 1);
        PDShadingType3 shading = new PDShadingType3(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE3);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setFunction(new PDFunctionType2(fn));
        shading.setCoords(floats(180, 200, 0, 180, 200, 250));
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        shading.setExtend(extend);
        return shading;
    }

    private static COSArray floats(float... v) {
        COSArray a = new COSArray();
        for (float f : v) {
            a.add(f == (int) f ? COSInteger.get((int) f) : new COSFloat(f));
        }
        return a;
    }

    private static final class Store implements PlacedContent.MediaStore {
        final List<Picture.MediaRef> made = new ArrayList<>();

        @Override
        public Picture.MediaRef media(Object key) {
            return null;
        }

        @Override
        public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) {
            Picture.MediaRef ref = new Picture.MediaRef("m" + made.size(), ext, pixelWidth, pixelHeight);
            made.add(ref);
            return ref;
        }
    }
}
