package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

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

    private static PDPage page(PDDocument doc, String content) throws IOException {
        PDPage page = page(doc);
        page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, content));
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
    void aHugeContentStreamIsReadOnlyUpToTheScanLimit() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream content = spaces(doc, HUGE);
            assertEquals(4097, StreamGuard.head(content, 4096).readAllBytes().length);
            page(doc).getCOSObject().setItem(COSName.CONTENTS, content);
            assertTrue(extra(doc) > 0);
        }
    }

    private static final long HUGE = 160L << 20;

    private static final long SCAN_ALLOWANCE = 2 * PdfFootprint.MAX_SCANNED_CONTENT;

    private static COSStream spaces(PDDocument doc, long size) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        byte[] chunk = new byte[1 << 20];
        Arrays.fill(chunk, (byte) ' ');
        s.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        try (OutputStream out = new DeflaterOutputStream(s.createRawOutputStream(), new Deflater(1))) {
            for (long written = 0; written < size; written += chunk.length) {
                out.write(chunk);
            }
        }
        return s;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private static long scannedWithin(PDDocument doc) {
        long before = allocated();
        long estimate = PdfFootprint.estimate(doc, 0, 0, 150f);
        long used = allocated() - before;
        assertTrue(used < SCAN_ALLOWANCE, "the scan should stop at its limit, allocated " + used);
        return estimate;
    }

    @Test
    void aHugeFormIsReadOnlyUpToTheScanLimit() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream form = spaces(doc, HUGE);
            form.setItem(COSName.TYPE, COSName.XOBJECT);
            form.setItem(COSName.SUBTYPE, COSName.FORM);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Fm0"), form);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.XOBJECT, xobjects);
            page(doc, "/Fm0 Do\n").getCOSObject().setItem(COSName.RESOURCES, resources);
            assertTrue(scannedWithin(doc) - baseline() >= PdfFootprint.UNSCANNED_PICTURE);
        }
    }

    @Test
    void aHugeAppearanceIsReadOnlyUpToTheScanLimit() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary ap = new COSDictionary();
            ap.setItem(COSName.N, spaces(doc, HUGE));
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
            annot.setItem(COSName.AP, ap);
            COSArray annots = new COSArray();
            annots.add(annot);
            page(doc).getCOSObject().setItem(COSName.ANNOTS, annots);
            assertTrue(scannedWithin(doc) - baseline() >= PdfFootprint.UNSCANNED_PICTURE);
        }
    }

    @Test
    void anIntermediateFilterStageIsCutAtTheScanLimit() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream content = doc.getDocument().createCOSStream();
            COSArray filters = new COSArray();
            filters.add(COSName.FLATE_DECODE);
            filters.add(COSName.ASCII_HEX_DECODE);
            content.setItem(COSName.FILTER, filters);
            byte[] hex = new byte[1 << 20];
            for (int i = 0; i < hex.length; i++) {
                hex[i] = (byte) (i % 2 == 0 ? '2' : '0');
            }
            try (OutputStream out = new DeflaterOutputStream(content.createRawOutputStream(), new Deflater(1))) {
                for (long written = 0; written < HUGE; written += hex.length) {
                    out.write(hex);
                }
            }
            long before = allocated();
            assertThrows(IOException.class, () -> StreamGuard.head(content, 4096));
            assertTrue(allocated() - before < (16L << 20));
            page(doc).getCOSObject().setItem(COSName.CONTENTS, content);
            assertTrue(scannedWithin(doc) - baseline() >= PdfFootprint.UNSCANNED_PICTURE);
        }
    }

    @Test
    void aPageImageIsCountedAsItsRgbRaster() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "/Im0 Do\n");
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
            page(doc, "/Pattern cs /P0 scn 0 0 10 10 re f\n").getCOSObject().setItem(COSName.RESOURCES, resources);
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
            page(doc, "BT /F0 12 Tf (A) Tj ET\n").getCOSObject().setItem(COSName.RESOURCES, resources);
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
            page(doc, "/Fm5 Do\n").getCOSObject().setItem(COSName.RESOURCES, resources);
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
            page(doc, "/Fm0 Do\n").getCOSObject().setItem(COSName.RESOURCES, resources);
            assertTrue(extra(doc) >= PdfFootprint.UNSCANNED_PICTURE);
        }
    }

    private static String text(int bytes) {
        StringBuilder b = new StringBuilder("BT /F1 4 Tf ");
        while (b.length() < bytes - 3) {
            b.append("(ab) Tj ");
        }
        return b.append("ET").toString();
    }

    private static long charged(long bytes) {
        return Math.max(0, bytes - PdfFootprint.PAGE_CONTENT_ALLOWANCE);
    }

    @Test
    void aDensePageIsChargedForItsContentPastTheAllowance() throws IOException {
        int bytes = 256 << 10;
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, text(bytes)));
            long excess = charged(text(bytes).length());
            assertEquals(excess * (PdfFootprint.CONTENT_BYTE_COST + PdfFootprint.KEPT_BYTE_COST), extra(doc));
        }
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, text(4 << 20)));
            long kept = charged(text(4 << 20).length()) * PdfFootprint.KEPT_BYTE_COST;
            assertEquals(PdfFootprint.MAX_CONTENT_CHARGE + kept, extra(doc), "one page is laid out only so far");
        }
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, text(4 << 10)));
            assertEquals(0, extra(doc), "an ordinary page costs only the fixed amount");
        }
    }

    @Test
    void everyDensePageIsChargedForWhatIsKeptAndTheBusiestForItsLayout() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, text(256 << 10)));
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, text(64 << 10)));
            long big = charged(text(256 << 10).length());
            long small = charged(text(64 << 10).length());
            long content = big * PdfFootprint.CONTENT_BYTE_COST + (big + small) * PdfFootprint.KEPT_BYTE_COST;
            long pages = PdfFootprint.estimate(doc, 0, 0, 150f);
            assertEquals(content, pages - baseline() - PdfFootprint.SAMPLED_PAGE_BYTES - PdfFootprint.PAGE_BYTES);
        }
    }

    @Test
    void aSharedFormIsChargedOnEveryPageThatDrawsIt() throws IOException {
        int bytes = 128 << 10;
        String draw = "/Fm0 Do\n";
        long each = charged(text(bytes).length() + draw.length());
        long fixed = 2 * (PdfFootprint.SAMPLED_PAGE_BYTES + PdfFootprint.PAGE_BYTES);
        long content = each * PdfFootprint.CONTENT_BYTE_COST + 3 * each * PdfFootprint.KEPT_BYTE_COST;
        for (boolean sharedResources : new boolean[] {true, false}) {
            try (PDDocument doc = new PDDocument()) {
                COSStream form = stream(doc, text(bytes));
                form.setItem(COSName.TYPE, COSName.XOBJECT);
                form.setItem(COSName.SUBTYPE, COSName.FORM);
                COSDictionary shared = null;
                for (int i = 0; i < 3; i++) {
                    COSDictionary xobjects = new COSDictionary();
                    xobjects.setItem(COSName.getPDFName("Fm0"), form);
                    COSDictionary resources = new COSDictionary();
                    resources.setItem(COSName.XOBJECT, xobjects);
                    shared = sharedResources && shared != null ? shared : resources;
                    PDPage page = page(doc);
                    page.getCOSObject().setItem(COSName.RESOURCES, shared);
                    page.getCOSObject().setItem(COSName.CONTENTS, stream(doc, draw));
                }
                long pages = PdfFootprint.estimate(doc, 0, 0, 150f);
                assertEquals(content, pages - baseline() - fixed, "shared resources: " + sharedResources);
            }
        }
    }
    @Test
    void aRetainedTailPageReceivesItsDenseContentCharge() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 60; i++) {
                page(doc);
            }
            String content = text(32 << 10);
            page(doc).getCOSObject().setItem(COSName.CONTENTS, stream(doc, content));
            long fixed = 60 * PdfFootprint.SAMPLED_PAGE_BYTES + 61 * PdfFootprint.PAGE_BYTES;
            long expected = charged(content.length()) * (PdfFootprint.CONTENT_BYTE_COST + PdfFootprint.KEPT_BYTE_COST);
            assertEquals(expected, PdfFootprint.estimate(doc, 0, 0, 150f) - fixed);
        }
    }

    @Test
    void aBlankPageDoesNotChargeAnUnusedForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            COSStream form = stream(doc, text(32 << 10));
            form.setItem(COSName.TYPE, COSName.XOBJECT);
            form.setItem(COSName.SUBTYPE, COSName.FORM);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Fm0"), form);
            COSDictionary resources = new COSDictionary();
            resources.setItem(COSName.XOBJECT, xobjects);
            page(doc).getCOSObject().setItem(COSName.RESOURCES, resources);
            assertEquals(0, extra(doc));
        }
    }

}
