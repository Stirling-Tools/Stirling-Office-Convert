package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;

class MemoryAdmissionTest {

    @TempDir Path dir;

    private static PDDocument pages(int count) throws IOException {
        PDDocument doc = new PDDocument();
        for (int i = 0; i < count; i++) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Page " + (i + 1));
                cs.endText();
            }
        }
        return doc;
    }

    // An image dictionary with no samples: the estimate reads its size, never its data
    private static COSStream picture(PDDocument doc, int width, int height) {
        COSStream image = doc.getDocument().createCOSStream();
        image.setItem(COSName.TYPE, COSName.XOBJECT);
        image.setItem(COSName.SUBTYPE, COSName.IMAGE);
        image.setInt(COSName.WIDTH, width);
        image.setInt(COSName.HEIGHT, height);
        image.setInt(COSName.BITS_PER_COMPONENT, 8);
        image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        return image;
    }

    private static long estimate(PDDocument doc) {
        return OfficeConvert.memoryEstimate(doc, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
    }

    @Test
    void theEstimateGrowsWithPagesAndTheLargestPictureEvenInsideAForm() throws IOException {
        try (PDDocument one = pages(1); PDDocument many = pages(300); PDDocument pictured = pages(1)) {
            long small = estimate(one);
            long large = estimate(many);
            assertTrue(large > small + 30L * (1 << 20), small + " " + large);
            PDFormXObject form = new PDFormXObject(pictured);
            PDResources inner = new PDResources();
            inner.getCOSObject().setItem(COSName.XOBJECT, new org.apache.pdfbox.cos.COSDictionary());
            inner.getCOSObject().getCOSDictionary(COSName.XOBJECT).setItem("Im1", picture(pictured, 4000, 3000));
            form.setResources(inner);
            PDResources outer = pictured.getPage(0).getResources();
            outer.add(form);
            long withPicture = estimate(pictured);
            assertTrue(withPicture - small >= 7L * 4000 * 3000, small + " " + withPicture);
            assertEquals(small, OfficeConvert.memoryEstimate(one, OfficeConvert.Format.PPTX,
                    OfficeConvert.Settings.defaults()));
            assertTrue(OfficeConvert.memoryEstimate(one, OfficeConvert.Format.DOCX,
                    OfficeConvert.Settings.defaults().figureDpi(600)) > small + 20L * (1 << 20), "finer figures cost more");
            assertEquals(small, OfficeConvert.memoryEstimate(one, OfficeConvert.Format.XLSX,
                    OfficeConvert.Settings.defaults().figureDpi(600)), "a workbook draws no figures");
        }
    }

    @Test
    void aPictureTooLargeToDecodeCostsNothingAndARangeCountsOnlyItsPages() throws IOException {
        try (PDDocument doc = pages(200)) {
            long all = estimate(doc);
            long some = OfficeConvert.memoryEstimate(doc, OfficeConvert.Format.DOCX,
                    OfficeConvert.Settings.defaults().pages(1, 2));
            assertTrue(some < all / 3, some + " " + all);
            PDResources r = doc.getPage(0).getResources();
            r.getCOSObject().setItem(COSName.XOBJECT, new org.apache.pdfbox.cos.COSDictionary());
            r.getCOSObject().getCOSDictionary(COSName.XOBJECT).setItem("Huge", picture(doc, 100_000, 100_000));
            assertEquals(all, estimate(doc));
        }
    }

    @Test
    void aConversionWaitingForMemoryStillEndsAtItsTimeoutAndLeavesNothing() throws Exception {
        Path pdf = dir.resolve("in.pdf");
        try (PDDocument doc = pages(1)) {
            doc.save(pdf.toFile());
        }
        Path out = dir.resolve("out.docx");
        Admission.Ticket hog = Admission.jvm().enter(Long.MAX_VALUE);
        try {
            long start = System.nanoTime();
            assertThrows(OfficeConvert.TimedOut.class, () -> OfficeConvert.convert(pdf, out,
                    OfficeConvert.Settings.defaults().timeout(Duration.ofMillis(300))));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
            assertEquals(1, Admission.jvm().running(), "the waiting conversion never started");
        } finally {
            hog.close();
        }
        assertFalse(Files.exists(out));
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals(List.of(pdf), left.toList(), "no .part file is left behind");
        }
        OfficeConvert.convert(pdf, out);
        assertTrue(Files.size(out) > 0);
        assertEquals(0, Admission.jvm().running());
    }

    @Test
    void aConversionStoppedForMemoryFailsWithThePlainMessageAndFreesItsShare() throws Exception {
        OutputStream stopped = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new Admission.Stopped();
            }
        };
        for (OfficeConvert.Format f : List.of(OfficeConvert.Format.DOCX, OfficeConvert.Format.PPTX,
                OfficeConvert.Format.XLSX)) {
            for (Duration timeout : List.of(Duration.ZERO, Duration.ofMinutes(1))) {
                try (PDDocument doc = pages(2)) {
                    IOException e = assertThrows(IOException.class, () -> OfficeConvert.convert(doc, stopped, f,
                            OfficeConvert.Settings.defaults().timeout(timeout)));
                    assertEquals(Admission.NEEDS_MEMORY, e.getMessage(), f + " " + timeout);
                }
                assertEquals(0, Admission.jvm().running(), f + " " + timeout);
            }
        }
    }
}
