package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

public class GuardedRendererTest {

    private static final class Watched extends GuardedRenderer {

        private Drawer drawer;

        Watched(PDDocument doc) {
            super(doc, "a test");
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            drawer = new Drawer(parameters, "a test");
            return drawer;
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aFormFanOutStopsAtTheBudget() throws IOException {
        try (PDDocument doc = fanOut()) {
            Watched r = new Watched(doc);
            BufferedImage image = r.draw(0, 0.25f, ImageType.RGB);
            assertEquals(153, image.getWidth());
            assertTrue(r.drawer.leftOut());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aRecursiveType3FontDrawsWithoutOverflowing() throws IOException {
        try (PDDocument doc = recursiveType3()) {
            Watched r = new Watched(doc);
            r.draw(0, 0.25f, ImageType.RGB);
            assertTrue(r.drawer.leftOut());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anImageTooLargeToDecodeIsLeftOut() throws IOException {
        try (PDDocument doc = imageBomb()) {
            Watched r = new Watched(doc);
            r.draw(0, 0.25f, ImageType.RGB);
            assertTrue(r.drawer.leftOut());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void annotationsPastTheCapAreNotDrawn() throws IOException {
        try (PDDocument doc = annotated(GuardedRenderer.MAX_ANNOTATIONS + 1)) {
            Watched r = new Watched(doc);
            r.draw(0, 0.1f, ImageType.RGB);
            assertTrue(r.drawer.leftOut());
        }
        try (PDDocument doc = annotated(10)) {
            Watched r = new Watched(doc);
            r.draw(0, 0.1f, ImageType.RGB);
            assertTrue(!r.drawer.leftOut());
        }
    }

    public static PDDocument fanOut() throws IOException {
        PDDocument doc = new PDDocument();
        PDFormXObject a = form(doc);
        PDFormXObject b = form(doc);
        resources(a, b);
        resources(b, a);
        String body = "0 0 m 10 10 l S q 0.9 0 0 0.9 1 1 cm /O Do Q q 0.8 0 0 0.8 2 2 cm /O Do Q";
        write(a.getContentStream(), body);
        write(b.getContentStream(), body);
        PDPage page = page(doc, "/A Do");
        page.getResources().put(COSName.getPDFName("A"), a);
        return doc;
    }

    public static PDDocument recursiveType3() throws IOException {
        PDDocument doc = new PDDocument();
        COSStream proc = doc.getDocument().createCOSStream();
        write(proc, "1000 0 0 0 1000 1000 d1 BT /T 1000 Tf (aa) Tj ET 0 0 1000 1000 re f");
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE3);
        font.setItem(COSName.FONT_BBOX, numbers(0, 0, 1000, 1000));
        font.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
        COSDictionary procs = new COSDictionary();
        procs.setItem(COSName.A, proc);
        font.setItem(COSName.CHAR_PROCS, procs);
        COSDictionary encoding = new COSDictionary();
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(97));
        differences.add(COSName.A);
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setInt(COSName.FIRST_CHAR, 97);
        font.setInt(COSName.LAST_CHAR, 97);
        font.setItem(COSName.WIDTHS, numbers(1000));
        COSDictionary fonts = new COSDictionary();
        fonts.setItem(COSName.getPDFName("T"), font);
        COSDictionary own = new COSDictionary();
        own.setItem(COSName.FONT, fonts);
        font.setItem(COSName.RESOURCES, own);
        PDPage page = page(doc, "BT /T 12 Tf 72 700 Td (" + "a".repeat(200) + ") Tj ET");
        page.getResources().getCOSObject().setItem(COSName.FONT, fonts);
        return doc;
    }

    public static PDDocument imageBomb() throws IOException {
        PDDocument doc = new PDDocument();
        COSStream data = doc.getDocument().createCOSStream();
        write(data, "tiny");
        data.setItem(COSName.TYPE, COSName.XOBJECT);
        data.setItem(COSName.SUBTYPE, COSName.IMAGE);
        data.setInt(COSName.WIDTH, 20_000);
        data.setInt(COSName.HEIGHT, 20_000);
        data.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        data.setInt(COSName.BITS_PER_COMPONENT, 8);
        PDImageXObject image = new PDImageXObject(new PDStream(data), null);
        PDPage page = page(doc, "q 612 0 0 792 0 0 cm /Im Do Q");
        page.getResources().put(COSName.getPDFName("Im"), image);
        return doc;
    }

    public static PDDocument annotated(int count) throws IOException {
        PDDocument doc = new PDDocument();
        PDPage page = page(doc, "");
        PDAppearanceStream look = new PDAppearanceStream(doc);
        look.setBBox(new PDRectangle(10, 10));
        write(look.getCOSObject(), "0 0 10 10 re f");
        List<PDAnnotation> annotations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(i % 600, i % 780, 10, 10));
            PDAppearanceDictionary ap = new PDAppearanceDictionary();
            ap.setNormalAppearance(look);
            square.setAppearance(ap);
            annotations.add(square);
        }
        page.setAnnotations(annotations);
        return doc;
    }

    private static PDFormXObject form(PDDocument doc) {
        PDFormXObject f = new PDFormXObject(doc);
        f.setBBox(PDRectangle.LETTER);
        return f;
    }

    private static void resources(PDFormXObject owner, PDFormXObject drawn) {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("O"), drawn);
        owner.setResources(r);
    }

    private static PDPage page(PDDocument doc, String content) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        page.setResources(new PDResources());
        PDStream stream = new PDStream(doc);
        write(stream.getCOSObject(), content);
        page.setContents(stream);
        doc.addPage(page);
        return page;
    }

    private static COSArray numbers(float... values) {
        COSArray a = new COSArray();
        for (float v : values) {
            a.add(new COSFloat(v));
        }
        return a;
    }

    private static void write(PDStream stream, String text) throws IOException {
        write(stream.getCOSObject(), text);
    }

    private static void write(COSStream stream, String text) throws IOException {
        try (OutputStream out = stream.createOutputStream()) {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }
}
