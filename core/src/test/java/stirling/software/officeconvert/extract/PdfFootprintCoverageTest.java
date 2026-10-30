package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfFootprintCoverageTest {

    private static final int SIDE = 2_000;

    private static final long PICTURE = (long) SIDE * SIDE * (3 + 4);

    private static COSStream stream(PDDocument doc, String content) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream out = s.createOutputStream()) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return s;
    }

    private static COSStream image(PDDocument doc) throws IOException {
        COSStream s = stream(doc, "");
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setInt(COSName.WIDTH, SIDE);
        s.setInt(COSName.HEIGHT, SIDE);
        s.setInt(COSName.BITS_PER_COMPONENT, 8);
        s.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        return s;
    }

    private static COSDictionary withImage(PDDocument doc) throws IOException {
        COSDictionary xobjects = new COSDictionary();
        xobjects.setItem(COSName.getPDFName("Im0"), image(doc));
        COSDictionary resources = new COSDictionary();
        resources.setItem(COSName.XOBJECT, xobjects);
        return resources;
    }

    private static COSStream form(PDDocument doc, COSDictionary resources) throws IOException {
        COSStream s = stream(doc, "");
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.FORM);
        s.setItem(COSName.RESOURCES, resources);
        return s;
    }

    private static PDPage page(PDDocument doc) {
        PDPage page = new PDPage();
        doc.addPage(page);
        return page;
    }

    private static long baseline() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc);
            return PdfFootprint.estimate(doc, 0, 0, 150f);
        }
    }

    private static long extra(PDDocument doc) throws IOException {
        return PdfFootprint.estimate(doc, 0, 0, 150f) - baseline();
    }

    @Test
    void aPageImageIsCountedAsItsRgbRaster() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc);
            page.getCOSObject().setItem(COSName.RESOURCES, withImage(doc));
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void anImageInATilingPatternIsCounted() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream pattern = form(doc, withImage(doc));
            pattern.setInt(COSName.PATTERN_TYPE, 1);
            COSDictionary patterns = new COSDictionary();
            patterns.setItem(COSName.getPDFName("P0"), pattern);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.PATTERN, patterns);
            page(doc).getCOSObject().setItem(COSName.RESOURCES, resources);
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void anImageInAType3GlyphIsCounted() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE3);
            font.setItem(COSName.RESOURCES, withImage(doc));
            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F0"), font);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.FONT, fonts);
            page(doc).getCOSObject().setItem(COSName.RESOURCES, resources);
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void anImageInAnAnnotationAppearanceIsCounted() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary ap = new COSDictionary();
            ap.setItem(COSName.N, form(doc, withImage(doc)));
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"));
            annot.setItem(COSName.AP, ap);
            COSArray annots = new COSArray();
            annots.add(annot);
            page(doc).getCOSObject().setItem(COSName.ANNOTS, annots);
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void anImageInADeeplyNestedFormIsCounted() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary resources = withImage(doc);
            for (int i = 0; i < 6; i++) {
                COSDictionary xobjects = new COSDictionary();
                xobjects.setItem(COSName.getPDFName("Fm" + i), form(doc, resources));
                resources = new COSDictionary();
                resources.setItem(COSName.XOBJECT, xobjects);
            }
            page(doc).getCOSObject().setItem(COSName.RESOURCES, resources);
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void anInlineImageIsCounted() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc,
                    "q (BI) Tj BI /W " + SIDE + " /H " + SIDE + " /BPC 8 /CS /RGB /DP << /K 0 >> ID \u0000\u0001 EI Q"));
            assertEquals(PICTURE, extra(doc));
        }
    }

    @Test
    void textThatReadsBiIsNotAnImage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, "BT (BI) Tj ET"));
            assertEquals(0, extra(doc));
        }
    }

    @Test
    void anUnreadableFormIsChargedHigh() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream form = form(doc, new COSDictionary());
            form.setItem(COSName.FILTER, COSName.getPDFName("NoSuchFilter"));
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Fm0"), form);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.XOBJECT, xobjects);
            page(doc).getCOSObject().setItem(COSName.RESOURCES, resources);
            assertTrue(extra(doc) >= PdfFootprint.UNSCANNED_PICTURE);
        }
    }
}
