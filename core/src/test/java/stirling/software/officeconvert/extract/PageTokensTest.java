package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.junit.jupiter.api.Test;

class PageTokensTest {

    private static final class Drawing extends PDFRenderer {

        private final PageTokens tokens;

        Drawing(PDDocument doc, PageTokens tokens) {
            super(doc);
            this.tokens = tokens;
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new GuardedRenderer.Drawer(parameters, "a test", tokens);
        }
    }

    @Test
    void pathsClipsTextAndAnAnnotationAfterAnOpenTransformDrawTheSame() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "q 1 0 0 RG 2 w 10 10 m 200 150 l S Q 0 0 1 rg 20 20 100 50 re f"
                    + " q 30 30 60 60 re W n 0 1 0 rg 0 0 300 300 re f Q"
                    + " BT /F1 18 Tf 40 220 Td (Replayed text) Tj ET 2 0 0 2 0 0 cm 5 5 10 10 re f");
            page.getResources().put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(150, 150, 80, 40));
            PDAppearanceStream look = new PDAppearanceStream(doc);
            look.setBBox(new PDRectangle(80, 40));
            write(look.getContentStream(), "1 0 0 rg 0 0 80 40 re f");
            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.setNormalAppearance(look);
            square.setAppearance(appearance);
            page.getAnnotations().add(square);
            assertSameDrawing(doc);
        }
    }

    @Test
    void formsSoftMasksAndPatternsDrawTheSame() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "q /GS1 gs 0 0 1 rg 40 40 200 200 re f Q /Fm1 Do"
                    + " /Pattern cs /P1 scn 20 250 120 40 re f /P2 scn 160 250 120 40 re f /Sh1 sh");
            PDResources res = page.getResources();
            PDFormXObject form = form(doc, "0 0.5 0 rg 0 0 30 30 re f", new PDRectangle(30, 30));
            res.put(COSName.getPDFName("Fm1"), form);
            PDFormXObject mask = form(doc, "0.5 g 0 0 300 300 re f", new PDRectangle(300, 300));
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setItem(COSName.CS, COSName.DEVICERGB);
            mask.getCOSObject().setItem(COSName.GROUP, group);
            COSDictionary smask = new COSDictionary();
            smask.setItem(COSName.S, COSName.LUMINOSITY);
            smask.setItem(COSName.G, mask);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.getCOSObject().setItem(COSName.SMASK, smask);
            gs.setNonStrokingAlphaConstant(0.6f);
            res.put(COSName.getPDFName("GS1"), gs);
            PDShadingPattern shaded = new PDShadingPattern();
            shaded.setShading(axial());
            res.put(COSName.getPDFName("P1"), shaded);
            PDTilingPattern tiles = new PDTilingPattern();
            tiles.setBBox(new PDRectangle(10, 10));
            tiles.setXStep(10);
            tiles.setYStep(10);
            tiles.setPaintType(PDTilingPattern.PAINT_COLORED);
            tiles.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
            tiles.setResources(new PDResources());
            write(tiles.getContentStream(), "1 0 0 rg 0 0 5 5 re f");
            res.put(COSName.getPDFName("P2"), tiles);
            res.put(COSName.getPDFName("Sh1"), axial());
            assertSameDrawing(doc);
        }
    }

    @Test
    void inlineImagesAreDrawnTheSameButNotKept() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, "q 100 0 0 100 50 50 cm BI /W 2 /H 2 /CS /G /BPC 8 ID \u0000ÿÿ\u0000 EI Q");
            assertSameDrawing(doc);
            PageTokens tokens = new PageTokens();
            assertNotSame(tokens.of(doc.getPage(0)), tokens.of(doc.getPage(0)));
        }
    }

    @Test
    void brokenAndUnbalancedStreamsDrawTheSame() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc, "Q Q 1 0 0 rg 10 10 50 50 re f q q 0 0 1 rg 70 70 50 50 re f 20 20 m 100 (unterminated");
            page(doc, "q 0 1 0 rg 10 10 80 80 re f ] >> 0 0 1 rg 100 100 50 50 re f 12 } 3 w 1 1 m 200 200 l S");
            assertSameDrawing(doc);
        }
    }

    @Test
    void type3GlyphsAndFormFanOutsDrawTheSame() throws IOException {
        try (PDDocument doc = GuardedRendererTest.recursiveType3()) {
            assertSameDrawing(doc);
        }
        try (PDDocument doc = GuardedRendererTest.fanOut()) {
            assertSameDrawing(doc);
        }
        try (PDDocument doc = GreyGroupPages.darkGreyInAnIsolatedGroup(COSName.DEVICEGRAY)) {
            assertSameDrawing(doc);
        }
    }

    @Test
    void aPageIsParsedOnceAndAnEditedPageAgain() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "0 0 1 rg 10 10 50 50 re f");
            PageTokens tokens = new PageTokens();
            Object[] first = tokens.of(page);
            assertSame(first, tokens.of(page));
            PDStream edited = new PDStream(doc);
            write(edited, "1 0 0 rg 10 10 50 50 re f");
            page.setContents(edited);
            assertNotSame(first, tokens.of(page));
        }
    }

    @Test
    void aPageTooLargeToKeepIsDrawnByTheStockEngine() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "1 0 0 rg " + "10 10 m 20 20 l ".repeat(PageTokens.MAX_TOKENS / 6 + 1) + "S");
            PageTokens tokens = new PageTokens();
            assertNull(tokens.of(page));
            assertNull(tokens.of(page));
            int[] stock = pixels(new Drawing(doc, null).renderImage(0, 0.5f, ImageType.ARGB));
            assertArrayEquals(stock, pixels(new Drawing(doc, tokens).renderImage(0, 0.5f, ImageType.ARGB)));
        }
    }

    private static void assertSameDrawing(PDDocument doc) throws IOException {
        Drawing replaying = new Drawing(doc, new PageTokens());
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            int[] stock = pixels(new Drawing(doc, null).renderImage(i, 0.5f, ImageType.ARGB));
            assertTrue(Arrays.stream(stock).distinct().count() > 1, "page " + i + " draws something");
            assertNotNull(new PageTokens().of(doc.getPage(i)), "page " + i + " is replayed, not drawn stock");
            assertArrayEquals(stock, pixels(replaying.renderImage(i, 0.5f, ImageType.ARGB)), "recorded page " + i);
            assertArrayEquals(stock, pixels(replaying.renderImage(i, 0.5f, ImageType.ARGB)), "replayed page " + i);
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static PDPage page(PDDocument doc, String content) throws IOException {
        PDPage page = new PDPage(new PDRectangle(300, 300));
        page.setResources(new PDResources());
        PDStream stream = new PDStream(doc);
        write(stream, content);
        page.setContents(stream);
        doc.addPage(page);
        return page;
    }

    private static PDFormXObject form(PDDocument doc, String content, PDRectangle box) throws IOException {
        PDFormXObject form = new PDFormXObject(doc);
        form.setBBox(box);
        form.setResources(new PDResources());
        write(form.getContentStream(), content);
        return form;
    }

    private static PDShading axial() {
        PDShadingType2 shading = new PDShadingType2(new COSDictionary());
        shading.setShadingType(PDShading.SHADING_TYPE2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setCoords(floats(0, 0, 300, 0));
        COSDictionary fn = new COSDictionary();
        fn.setInt(COSName.FUNCTION_TYPE, 2);
        fn.setItem(COSName.DOMAIN, floats(0, 1));
        fn.setItem(COSName.C0, floats(1, 0, 0));
        fn.setItem(COSName.C1, floats(0, 0, 1));
        fn.setInt(COSName.N, 1);
        shading.getCOSObject().setItem(COSName.FUNCTION, fn);
        COSArray extend = new COSArray();
        extend.add(COSBoolean.TRUE);
        extend.add(COSBoolean.TRUE);
        shading.getCOSObject().setItem(COSName.EXTEND, extend);
        return shading;
    }

    private static COSArray floats(float... values) {
        COSArray a = new COSArray();
        for (float v : values) {
            a.add(new COSFloat(v));
        }
        return a;
    }

    private static void write(PDStream stream, String text) throws IOException {
        try (OutputStream out = stream.createOutputStream()) {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
