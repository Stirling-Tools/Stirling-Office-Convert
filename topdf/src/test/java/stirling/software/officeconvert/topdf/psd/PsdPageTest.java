package stirling.software.officeconvert.topdf.psd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class PsdPageTest {

    @TempDir
    Path dir;

    private OfficeToPdf.Result convert(PsdFixture f, String name) throws IOException {
        Path in = Files.write(dir.resolve(name), f.bytes());
        return OfficeToPdf.convert(in, dir.resolve(name + ".pdf"));
    }

    private PDDocument open(String name) throws IOException {
        return Loader.loadPDF(dir.resolve(name + ".pdf").toFile());
    }

    private static COSStream image(PDPage page) {
        COSStream s = (COSStream) page.getResources().getCOSObject().getCOSDictionary(COSName.XOBJECT)
                .getDictionaryObject(COSName.getPDFName("Im0"));
        assertNotNull(s);
        return s;
    }

    private static byte[] samples(COSStream s) throws IOException {
        try (InputStream in = s.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static int gray(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFF;
    }

    @Test
    void anRgbPictureKeepsItsSamplesAndItsResolutionSetsThePageSize() throws IOException {
        PsdFixture f = new PsdFixture(4, 2).dpi(144);
        f.plane(10, 20, 30, 40, 50, 60, 70, 80).plane(1, 2, 3, 4, 5, 6, 7, 8).plane(200, 201, 202, 203, 204, 205, 206,
                207);
        OfficeToPdf.Result r = convert(f, "rgb.psd");
        assertEquals(1, r.pages());
        assertTrue(r.warnings().isEmpty(), r.warnings().toString());
        try (PDDocument pdf = open("rgb.psd")) {
            PDPage page = pdf.getPage(0);
            assertEquals(2, page.getMediaBox().getWidth(), 0.01f);
            assertEquals(1, page.getMediaBox().getHeight(), 0.01f);
            COSStream img = image(page);
            assertEquals(COSName.DEVICERGB, img.getDictionaryObject(COSName.COLORSPACE));
            assertEquals(8, img.getInt(COSName.BITS_PER_COMPONENT));
            byte[] s = samples(img);
            assertEquals(24, s.length);
            assertEquals(10, s[0] & 0xFF);
            assertEquals(1, s[1] & 0xFF);
            assertEquals(200, s[2] & 0xFF);
            assertEquals(80, s[21] & 0xFF);
            assertEquals(207, s[23] & 0xFF);
        }
    }

    @Test
    void cmykStaysCmykWithInkAmountsAndItsProfileIsKept() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.mode = PsdDocument.CMYK;
        f.plane(0, 255).plane(255, 0).plane(128, 255).plane(255, 255);
        f.resources.put(0x040F, profile("CMYK"));
        convert(f, "cmyk.psd");
        try (PDDocument pdf = open("cmyk.psd")) {
            COSStream img = image(pdf.getPage(0));
            COSArray cs = (COSArray) img.getDictionaryObject(COSName.COLORSPACE);
            assertEquals(COSName.ICCBASED, cs.getObject(0));
            assertEquals(4, ((COSStream) cs.getObject(1)).getInt(COSName.N));
            assertArrayEquals(new byte[] {(byte) 255, 0, 127, 0, 0, (byte) 255, 0, 0}, samples(img));
        }
    }

    @Test
    void labIsDrawnInLabAndSixteenBitSamplesStaySixteenBit() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.mode = PsdDocument.LAB;
        f.depth = 16;
        f.plane(0x80, 0x00).plane(0x80, 0x00).plane(0x40, 0x00);
        convert(f, "lab.psd");
        try (PDDocument pdf = open("lab.psd")) {
            COSStream img = image(pdf.getPage(0));
            COSArray cs = (COSArray) img.getDictionaryObject(COSName.COLORSPACE);
            assertEquals(COSName.LAB, cs.getObject(0));
            assertEquals(16, img.getInt(COSName.BITS_PER_COMPONENT));
            assertArrayEquals(new byte[] {(byte) 0x80, 0, (byte) 0x80, 0, 0x40, 0}, samples(img));
        }
    }

    @Test
    void thirtyTwoBitLinearLightIsEncodedForDisplay() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.mode = PsdDocument.GRAY;
        f.depth = 32;
        byte[] p = new byte[8];
        put(p, 0, 0.214f);
        put(p, 4, 1f);
        f.raw(p);
        convert(f, "hdr.psd");
        try (PDDocument pdf = open("hdr.psd")) {
            COSStream img = image(pdf.getPage(0));
            assertEquals(16, img.getInt(COSName.BITS_PER_COMPONENT));
            byte[] s = samples(img);
            int first = (s[0] & 0xFF) << 8 | s[1] & 0xFF;
            assertEquals(0.5, first / 65535.0, 0.01);
            assertEquals(65535, (s[2] & 0xFF) << 8 | s[3] & 0xFF);
        }
    }

    @Test
    void aBitmapIsOneBitWithBlackWhereTheFileSetsABit() throws IOException {
        PsdFixture f = new PsdFixture(8, 1);
        f.mode = PsdDocument.BITMAP;
        f.depth = 1;
        f.plane(0b10100000);
        convert(f, "bits.psd");
        try (PDDocument pdf = open("bits.psd")) {
            COSStream img = image(pdf.getPage(0));
            assertEquals(1, img.getInt(COSName.BITS_PER_COMPONENT));
            assertArrayEquals(new byte[] {(byte) 0b01011111}, samples(img));
        }
    }

    @Test
    void anIndexedPictureKeepsItsColorTable() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.mode = PsdDocument.INDEXED;
        f.colorData = new byte[768];
        f.colorData[1] = (byte) 255;
        f.colorData[256 + 1] = (byte) 128;
        f.plane(0, 1);
        convert(f, "idx.psd");
        try (PDDocument pdf = open("idx.psd")) {
            COSArray cs = (COSArray) image(pdf.getPage(0)).getDictionaryObject(COSName.COLORSPACE);
            assertEquals(COSName.INDEXED, cs.getObject(0));
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 72 * 8);
            assertEquals(0x000000, img.getRGB(4, 4) & 0xFFFFFF);
            assertEquals(0xFF8000, img.getRGB(12, 4) & 0xFFFFFF);
        }
    }

    @Test
    void multichannelInksBecomeDeviceN() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.mode = PsdDocument.MULTICHANNEL;
        f.plane(0).plane(255).plane(255);
        convert(f, "multi.psd");
        try (PDDocument pdf = open("multi.psd")) {
            COSStream img = image(pdf.getPage(0));
            COSArray cs = (COSArray) img.getDictionaryObject(COSName.COLORSPACE);
            assertEquals(COSName.DEVICEN, cs.getObject(0));
            assertEquals(3, ((COSArray) cs.getObject(1)).size());
            assertArrayEquals(new byte[] {(byte) 255, 0, 0}, samples(img));
            BufferedImage r = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            assertEquals(0x00FFFF, r.getRGB(0, 0) & 0xFFFFFF, Integer.toHexString(r.getRGB(0, 0)));
        }
    }

    @Test
    void mergedTransparencyBecomesASoftMaskAndTheWhiteMatteIsTakenOut() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.plane(255, 191).plane(255, 128).plane(255, 128).plane(0, 128);
        f.mergedAlpha = true;
        PsdFixture.Layer l = new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 0).fill(1, 0).fill(2, 0).fill(-1, 0);
        f.layers.add(l);
        convert(f, "alpha.psd");
        try (PDDocument pdf = open("alpha.psd")) {
            COSStream img = image(pdf.getPage(0));
            COSStream mask = (COSStream) img.getDictionaryObject(COSName.SMASK);
            assertNotNull(mask);
            assertArrayEquals(new byte[] {0, (byte) 128}, samples(mask));
            byte[] s = samples(img);
            assertEquals(128, s[3] & 0xFF, 1);
            assertEquals(1, s[4] & 0xFF, 1);
        }
    }

    @Test
    void extraChannelsWithoutMergedTransparencyAreNotTransparency() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.plane(10).plane(20).plane(30).plane(0);
        convert(f, "selection.psd");
        try (PDDocument pdf = open("selection.psd")) {
            assertNull(image(pdf.getPage(0)).getDictionaryObject(COSName.SMASK));
        }
    }

    @Test
    void aLargeDocumentIsAPsbAndAPageOverTheLimitUsesAUserUnit() throws IOException {
        PsdFixture f = new PsdFixture(20_000, 2);
        f.version = 2;
        f.mode = PsdDocument.GRAY;
        f.flat(77);
        convert(f, "wide.psb");
        try (PDDocument pdf = open("wide.psb")) {
            PDPage page = pdf.getPage(0);
            float unit = page.getCOSObject().getFloat(COSName.getPDFName("UserUnit"), 1);
            assertEquals(2, unit);
            assertEquals(20_000, page.getMediaBox().getWidth() * unit, 0.5f);
        }
    }

    @Test
    void withoutAMergedPictureTheLayersAreComposited() throws IOException {
        PsdFixture f = new PsdFixture(4, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 4).fill(0, 200).fill(1, 100).fill(2, 50));
        PsdFixture.Layer top = new PsdFixture.Layer().rect(0, 1, 1, 3).fill(-1, 255).fill(0, 128).fill(1, 128)
                .fill(2, 128);
        top.blend = "mul ";
        f.layers.add(top);
        PsdFixture.Layer hidden = new PsdFixture.Layer().rect(0, 0, 1, 4).fill(-1, 255).fill(0, 0).fill(1, 0)
                .fill(2, 0);
        hidden.hidden = true;
        f.layers.add(hidden);
        PsdFixture.Layer half = new PsdFixture.Layer().rect(0, 3, 1, 4).fill(-1, 255).fill(0, 0).fill(1, 0).fill(2, 0);
        half.opacity = 128;
        f.layers.add(half);
        OfficeToPdf.Result r = convert(f, "layers.psd");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("composited")), r.warnings().toString());
        try (PDDocument pdf = open("layers.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {200, 100, 50}, ints(s, 0));
            assertArrayEquals(new int[] {100, 50, 25}, ints(s, 3));
            assertArrayEquals(new int[] {100, 50, 25}, ints(s, 6));
            assertArrayEquals(new int[] {100, 50, 25}, ints(s, 9));
        }
    }

    @Test
    void clippedLayersShowOnlyWhereTheirBaseIsAndMasksHide() throws IOException {
        PsdFixture f = new PsdFixture(4, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 4).fill(0, 255));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 200));
        PsdFixture.Layer clip = new PsdFixture.Layer().rect(0, 0, 1, 4).fill(-1, 255).fill(0, 0);
        clip.clipped = true;
        f.layers.add(clip);
        PsdFixture.Layer masked = new PsdFixture.Layer().rect(0, 2, 1, 4).fill(-1, 255).fill(0, 50);
        masked.mask = new int[] {0, 3, 1, 4};
        masked.maskFill = 255;
        masked.channels.put(-2, new byte[] {0});
        f.layers.add(masked);
        convert(f, "clip.psd");
        try (PDDocument pdf = open("clip.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new byte[] {0, 0, 50, (byte) 255}, s);
        }
    }

    @Test
    void groupsCompositeTheirChildrenAndAShapeLayerIsFilledInsideItsPath() throws IOException {
        PsdFixture f = new PsdFixture(4, 4);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 4, 4).fill(0, 255).fill(1, 255).fill(2, 255));
        f.layers.add(new PsdFixture.Layer().section(3));
        PsdFixture.Layer shape = new PsdFixture.Layer().rect(0, 0, 0, 0);
        shape.channels.put(-1, new byte[0]);
        shape.channels.put(0, new byte[0]);
        shape.channels.put(1, new byte[0]);
        shape.channels.put(2, new byte[0]);
        shape.block("SoCo", solid(0, 0, 255));
        shape.block("vmsk", square(0, 0, 0.5, 0.5));
        f.layers.add(shape);
        PsdFixture.Layer group = new PsdFixture.Layer().section(1);
        group.opacity = 255;
        f.layers.add(group);
        OfficeToPdf.Result r = convert(f, "group.psd");
        try (PDDocument pdf = open("group.psd")) {
            BufferedImage img = new PDFRenderer(pdf).renderImageWithDPI(0, 72 * 4);
            assertEquals(0x0000FF, img.getRGB(2, 2) & 0xFFFFFF, r.warnings().toString());
            assertEquals(0xFFFFFF, img.getRGB(13, 13) & 0xFFFFFF);
        }
    }

    @Test
    void effectsThatCannotBeDrawnAreReported() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        byte[] fx = "\0\0\0\0DrShObjc\0\0\0\0enabbool\1".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 9).fill(1, 9).fill(2, 9).block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "fx.psd");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("drop shadow")), r.warnings().toString());
    }

    @Test
    void damagedPictureDataIsReportedAndLeftBlank() throws IOException {
        PsdFixture f = new PsdFixture(64, 64);
        f.flat(1).flat(2).flat(3);
        byte[] whole = f.bytes();
        Path in = Files.write(dir.resolve("cut.psd"), Arrays.copyOf(whole, whole.length - 300));
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("cut.pdf"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("damaged")), r.warnings().toString());
    }

    @Test
    void damagedRowsOfALayerReachingBelowTheCanvasAreReportedAsLost() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        PsdFixture.Layer tall = new PsdFixture.Layer().rect(-1, 0, 2, 2).fill(0, 90);
        tall.encoded.put(0, new byte[] {0, 0, 7});
        f.layers.add(tall);
        OfficeToPdf.Result r = convert(f, "tall.psd");
        assertTrue(r.truncated());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("damaged")), r.warnings().toString());
    }

    @Test
    void unreadableLayersWithoutAMergedPictureAreReportedAsLost() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layerCount = 30000;
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 90));
        OfficeToPdf.Result r = convert(f, "unreadable.psd");
        assertTrue(r.truncated());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("could not be read")), r.warnings().toString());
    }

    @Test
    void zipCompressedPictureDataIsRead() throws IOException {
        PsdFixture f = new PsdFixture(3, 2);
        f.compression = Rows.ZIP;
        f.mode = PsdDocument.GRAY;
        f.plane(1, 2, 3, 4, 5, 6);
        convert(f, "zip.psd");
        try (PDDocument pdf = open("zip.psd")) {
            assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void aFileThatOnlyLooksLikeAPsdDocumentIsRefused() throws IOException {
        Path in = Files.write(dir.resolve("fake.psd"), "not a picture".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.convert(in, dir.resolve("fake.pdf")));
        byte[] bad = new PsdFixture(1, 1).flat(0).flat(0).flat(0).bytes();
        bad[13] = 99;
        Path wrong = Files.write(dir.resolve("channels.psd"), bad);
        assertThrows(IOException.class, () -> OfficeToPdf.convert(wrong, dir.resolve("channels.pdf")));
        assertFalse(Files.exists(dir.resolve("channels.pdf")));
    }

    @Test
    void theMemoryEstimateCoversTheRowsOfAWideDocument() throws IOException {
        PsdFixture f = new PsdFixture(30_000, 1);
        f.flat(0).flat(0).flat(0);
        Path in = Files.write(dir.resolve("est.psd"), f.bytes());
        assertTrue(OfficeToPdf.memoryEstimate(in) > 30_000L * 3);
    }

    @Test
    void anInvertAdjustmentLayerChangesWhatIsBelowIt() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 0x40));
        PsdFixture.Layer invert = new PsdFixture.Layer().rect(0, 0, 0, 0).block("nvrt", new byte[0]);
        invert.channels.put(-1, new byte[0]);
        invert.channels.put(0, new byte[0]);
        f.layers.add(invert);
        convert(f, "invert.psd");
        try (PDDocument pdf = open("invert.psd")) {
            assertArrayEquals(new byte[] {(byte) 0xBF, (byte) 0xBF}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void anAdjustmentClippedToAnAdjustmentLayerIsApplied() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 100));
        for (int i = 0; i < 2; i++) {
            PsdFixture.Layer invert = new PsdFixture.Layer().rect(0, 0, 0, 0).block("nvrt", new byte[0]);
            invert.channels.put(-1, new byte[0]);
            invert.channels.put(0, new byte[0]);
            invert.clipped = i == 1;
            f.layers.add(invert);
        }
        convert(f, "clipped-adjustment.psd");
        try (PDDocument pdf = open("clipped-adjustment.psd")) {
            assertArrayEquals(new byte[] {100}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void aLayerClippedToATranslucentBaseTakesItsColorButKeepsTheBaseAlpha() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 128).fill(0, 100).fill(1, 100).fill(2, 100));
        PsdFixture.Layer red = new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 255).fill(1, 0).fill(2, 0);
        red.clipped = true;
        f.layers.add(red);
        convert(f, "clipped-atop.psd");
        try (PDDocument pdf = open("clipped-atop.psd")) {
            COSStream img = image(pdf.getPage(0));
            assertArrayEquals(new int[] {255, 0, 0}, ints(samples(img), 0));
            COSStream mask = (COSStream) img.getDictionaryObject(COSName.SMASK);
            assertEquals(128, samples(mask)[0] & 0xFF);
        }
    }

    @Test
    void aLayerClippedToAPassThroughGroupKeepsTheGroupAlpha() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().section(3));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 64).fill(0, 100).fill(1, 100).fill(2, 100));
        PsdFixture.Layer group = new PsdFixture.Layer().block("lsct", new byte[] {0, 0, 0, 1, '8', 'B', 'I', 'M', 'p', 'a',
            's', 's'});
        f.layers.add(group);
        PsdFixture.Layer red = new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 255).fill(1, 0).fill(2, 0);
        red.clipped = true;
        f.layers.add(red);
        convert(f, "clipped-pass.psd");
        try (PDDocument pdf = open("clipped-pass.psd")) {
            COSStream img = image(pdf.getPage(0));
            assertArrayEquals(new int[] {255, 0, 0}, ints(samples(img), 0));
            COSStream mask = (COSStream) img.getDictionaryObject(COSName.SMASK);
            assertEquals(64, samples(mask)[0] & 0xFF);
        }
    }

    @Test
    void anAdjustmentClippedToATranslucentLayerChangesItsWholeColor() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 128).fill(0, 200));
        PsdFixture.Layer invert = new PsdFixture.Layer().rect(0, 0, 0, 0).block("nvrt", new byte[0]);
        invert.channels.put(-1, new byte[0]);
        invert.channels.put(0, new byte[0]);
        invert.clipped = true;
        f.layers.add(invert);
        convert(f, "clipped-translucent.psd");
        try (PDDocument pdf = open("clipped-translucent.psd")) {
            assertArrayEquals(new byte[] {55}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void sixteenBitColorThresholdComparesTheUnroundedLuminance() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.depth = 16;
        f.flat(0).flat(0).flat(0);
        PsdFixture.Layer picture = new PsdFixture.Layer().rect(0, 0, 1, 1);
        picture.channels.put(0, new byte[0]);
        picture.channels.put(1, new byte[0]);
        picture.channels.put(2, new byte[0]);
        picture.encoded.put(0, new byte[] {0, 0, 0x72, 0x72});
        picture.encoded.put(1, new byte[] {0, 0, 0x6E, 0x6E});
        picture.encoded.put(2, new byte[] {0, 0, (byte) 0x8E, (byte) 0x8D});
        f.layers.add(picture);
        PsdFixture.Layer threshold = new PsdFixture.Layer().rect(0, 0, 0, 0).block("thrs", new byte[] {0, 115, 0, 0});
        threshold.channels.put(-1, new byte[0]);
        threshold.channels.put(0, new byte[0]);
        threshold.channels.put(1, new byte[0]);
        threshold.channels.put(2, new byte[0]);
        f.layers.add(threshold);
        OfficeToPdf.Result r = convert(f, "threshold16.psd");
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("damaged")), r.warnings().toString());
        try (PDDocument pdf = open("threshold16.psd")) {
            assertArrayEquals(new byte[6], samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void sixteenBitPosterizeSplitsTheLevelsAtTheirExactEdges() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.depth = 16;
        f.flat(0);
        PsdFixture.Layer picture = new PsdFixture.Layer().rect(0, 0, 1, 2);
        picture.channels.put(0, new byte[0]);
        picture.encoded.put(0, new byte[] {0, 0, 0x55, 0x56, 0x55, 0x54});
        f.layers.add(picture);
        PsdFixture.Layer posterize = new PsdFixture.Layer().rect(0, 0, 0, 0).block("post", new byte[] {0, 3, 0, 0});
        posterize.channels.put(-1, new byte[0]);
        posterize.channels.put(0, new byte[0]);
        f.layers.add(posterize);
        OfficeToPdf.Result r = convert(f, "posterize16.psd");
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("damaged")), r.warnings().toString());
        try (PDDocument pdf = open("posterize16.psd")) {
            assertArrayEquals(new byte[] {(byte) 0x80, 0, 0, 0}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void aLabColorFillLayerKeepsItsLabColor() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.LAB;
        f.flat(255).flat(128).flat(128);
        PsdFixture.Layer fill = new PsdFixture.Layer().rect(0, 0, 0, 0);
        fill.channels.put(-1, new byte[0]);
        fill.channels.put(0, new byte[0]);
        fill.channels.put(1, new byte[0]);
        fill.channels.put(2, new byte[0]);
        Desc lab = new Desc("LbCl").dbl("Lmnc", 50).dbl("A   ", 20).dbl("B   ", -30);
        fill.block("SoCo", new Desc("null").obj("Clr ", lab).bytes(true));
        f.layers.add(fill);
        OfficeToPdf.Result r = convert(f, "lab-fill.psd");
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("left out")), r.warnings().toString());
        try (PDDocument pdf = open("lab-fill.psd")) {
            assertArrayEquals(new byte[] {(byte) 128, (byte) 148, 98}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void aDeepKnockoutLayerShowsTheBackgroundThroughTheLayersBelowIt() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 250).fill(1, 240).fill(2, 230));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 255).fill(1, 0).fill(2, 0));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 0).fill(1, 0).fill(2, 255)
                .block("knko", new byte[] {2, 0, 0, 0}).block("iOpa", new byte[] {0, 0, 0, 0}));
        convert(f, "knockout.psd");
        try (PDDocument pdf = open("knockout.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {250, 240, 230}, ints(s, 0));
            assertArrayEquals(new int[] {255, 0, 0}, ints(s, 3));
        }
    }

    @Test
    void currentBrightnessAndContrastFollowTheirCurves() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        PsdFixture.Layer base = new PsdFixture.Layer().rect(0, 0, 1, 2);
        base.channels.put(0, new byte[] {(byte) 240, (byte) 120});
        f.layers.add(base);
        java.nio.ByteBuffer brit = java.nio.ByteBuffer.allocate(8);
        brit.putShort((short) -95).putShort((short) -50).putShort((short) 127).put((byte) 0);
        Desc generator = new Desc("null").lng("Vrsn", 1).lng("Brgh", -95).lng("Cntr", -50).lng("means", 127)
                .bool("Lab ", false).bool("useLegacy", false).bool("Auto", false);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("brit", brit.array())
                .block("CgEd", generator.bytes(true)));
        convert(f, "current-brightness.psd");
        try (PDDocument pdf = open("current-brightness.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(170, s[0] & 0xFF, 4);
            assertEquals(78, s[1] & 0xFF, 4);
        }
    }

    @Test
    void legacyBrightnessAndContrastShiftAndStretchAroundTheMean() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 100));
        java.nio.ByteBuffer brit = java.nio.ByteBuffer.allocate(8);
        brit.putShort((short) 20).putShort((short) 50).putShort((short) 127).put((byte) 0);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("brit", brit.array()));
        convert(f, "brightness.psd");
        try (PDDocument pdf = open("brightness.psd")) {
            assertEquals(113, samples(image(pdf.getPage(0)))[0] & 0xFF);
        }
    }

    @Test
    void legacyNegativeContrastCompressesAroundTheMeanBeforeTheBrightnessShift() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 100));
        java.nio.ByteBuffer brit = java.nio.ByteBuffer.allocate(8);
        brit.putShort((short) -23).putShort((short) -26).putShort((short) 127).put((byte) 0);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("brit", brit.array()));
        convert(f, "compress.psd");
        try (PDDocument pdf = open("compress.psd")) {
            assertEquals(84, samples(image(pdf.getPage(0)))[0] & 0xFF);
        }
    }

    @Test
    void aPatternFillLayerRepeatsItsPatternAcrossTheCanvas() throws IOException {
        PsdFixture f = new PsdFixture(4, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.global("Patt", PsdFixture.pattern("p1", 2, 1, new byte[] {10, (byte) 200}, new byte[] {20, 100},
                new byte[] {30, 50}));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("PtFl", PsdFixture.patternFill("p1")));
        OfficeToPdf.Result r = convert(f, "pattern.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("left out")), r.warnings().toString());
        try (PDDocument pdf = open("pattern.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {10, 20, 30}, ints(s, 0));
            assertArrayEquals(new int[] {200, 100, 50}, ints(s, 3));
            assertArrayEquals(new int[] {10, 20, 30}, ints(s, 6));
        }
    }

    @Test
    void aLinkedPatternOverlayStartsAtTheLayerReferencePoint() throws IOException {
        PsdFixture f = new PsdFixture(4, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.global("Patt", PsdFixture.pattern("p1", 2, 1, new byte[] {10, (byte) 200}, new byte[] {20, 100},
                new byte[] {30, 50}));
        Desc overlay = new Desc("patternFill").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .unit("Opct", "#Prc", 100).obj("Ptrn", new Desc("Ptrn").text("Idnt", "p1")).unit("Scl ", "#Prc", 100)
                .bool("Algn", true).obj("phase", new Desc("Pnt ").dbl("Hrzn", 0).dbl("Vrtc", 0));
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("patternFill", overlay).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        java.nio.ByteBuffer point = java.nio.ByteBuffer.allocate(16).putDouble(1).putDouble(0);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 4).fill(-1, 255).fill(0, 0).fill(1, 0).fill(2, 0)
                .block("lfx2", fx).block("fxrp", point.array()));
        OfficeToPdf.Result r = convert(f, "overlay-origin.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("left out")), r.warnings().toString());
        try (PDDocument pdf = open("overlay-origin.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {200, 100, 50}, ints(s, 0));
            assertArrayEquals(new int[] {10, 20, 30}, ints(s, 3));
        }
    }

    @Test
    void anInnerBevelLightsTheEdgeFacingTheLightAndShadesTheOther() throws IOException {
        PsdFixture f = new PsdFixture(12, 12);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        Desc bevel = new Desc("ebbl").bool("enab", true).bool("present", true).en("hglM", "BlnM", "Scrn")
                .obj("hglC", rgb(255, 255, 255)).unit("hglO", "#Prc", 100).en("sdwM", "BlnM", "Mltp")
                .obj("sdwC", rgb(0, 0, 0)).unit("sdwO", "#Prc", 100).en("bvlT", "bvlT", "SfBL")
                .en("bvlS", "BESl", "InrB").bool("uglg", false).unit("lagl", "#Ang", 90).unit("Lald", "#Ang", 30)
                .unit("srgR", "#Prc", 100).unit("blur", "#Pxl", 3).en("bvlD", "BESs", "In  ");
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("ebbl", bevel).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(2, 2, 10, 10).fill(-1, 255).fill(0, 128).fill(1, 128).fill(2, 128)
                .block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "bevel.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("bevel")), r.warnings().toString());
        try (PDDocument pdf = open("bevel.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            int top = s[(2 * 12 + 6) * 3] & 0xFF;
            int middle = s[(6 * 12 + 6) * 3] & 0xFF;
            int bottom = s[(9 * 12 + 6) * 3] & 0xFF;
            assertTrue(top > middle && bottom < middle, top + " " + middle + " " + bottom);
        }
    }

    @Test
    void anOuterGlowShowsAroundTheLayerAndAnInnerShadowInsideItsLitEdge() throws IOException {
        PsdFixture f = new PsdFixture(16, 16);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        Desc glow = new Desc("OrGl").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .obj("Clr ", rgb(0, 0, 0)).unit("Opct", "#Prc", 100).unit("blur", "#Pxl", 3).unit("Ckmt", "#Prc", 50);
        Desc inner = new Desc("IrSh").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .obj("Clr ", rgb(0, 0, 0)).unit("Opct", "#Prc", 100).bool("uglg", false).unit("lagl", "#Ang", 90)
                .unit("Dstn", "#Pxl", 3).unit("blur", "#Pxl", 0).unit("Ckmt", "#Prc", 0);
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("OrGl", glow).obj("IrSh", inner).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(4, 4, 12, 12).fill(-1, 255).fill(0, 200).fill(1, 200).fill(2, 200)
                .block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "glow.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("glow") || w.contains("inner shadow")),
                r.warnings().toString());
        try (PDDocument pdf = open("glow.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertTrue((s[(8 * 16 + 3) * 3] & 0xFF) < 200, "glow beside the layer");
            assertEquals(255, s[0] & 0xFF);
            assertTrue((s[(4 * 16 + 8) * 3] & 0xFF) < (s[(10 * 16 + 8) * 3] & 0xFF), "shadow at the top inside edge");
        }
    }

    @Test
    void aSatinShadesWhereTheShiftedCopiesOfTheLayerDisagree() throws IOException {
        PsdFixture f = new PsdFixture(16, 16);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        Desc satin = new Desc("ChFX").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .obj("Clr ", rgb(0, 0, 0)).unit("Opct", "#Prc", 100).bool("Invr", false).unit("lagl", "#Ang", 90)
                .unit("Dstn", "#Pxl", 3).unit("blur", "#Pxl", 0);
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("ChFX", satin).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(2, 2, 14, 14).fill(-1, 255).fill(0, 200).fill(1, 200).fill(2, 200)
                .block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "satin.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("satin")), r.warnings().toString());
        try (PDDocument pdf = open("satin.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(0, s[(3 * 16 + 8) * 3] & 0xFF);
            assertEquals(200, s[(8 * 16 + 8) * 3] & 0xFF);
            assertEquals(255, s[(1 * 16 + 8) * 3] & 0xFF);
        }
    }

    @Test
    void aLinearBurnEffectScalesItsColourTowardWhiteInsteadOfFadingTheResult() throws IOException {
        PsdFixture f = new PsdFixture(3, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 3).fill(-1, 255).fill(0, 77).fill(1, 77).fill(2, 77));
        Desc shadow = new Desc("DrSh").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "linearBurn")
                .obj("Clr ", rgb(0, 0, 0)).unit("Opct", "#Prc", 50).bool("uglg", false).unit("lagl", "#Ang", 180)
                .unit("Dstn", "#Pxl", 1).unit("blur", "#Pxl", 0).unit("Ckmt", "#Prc", 0);
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("DrSh", shadow).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 77).fill(1, 77).fill(2, 77)
                .block("lfx2", fx));
        convert(f, "burn.psd");
        try (PDDocument pdf = open("burn.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {0, 0, 0}, ints(s, 3));
            assertArrayEquals(new int[] {77, 77, 77}, ints(s, 6));
        }
    }

    @Test
    void everyStrokeOnALayerIsDrawn() throws IOException {
        PsdFixture f = new PsdFixture(9, 9);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 9, 9).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        Desc outside = new Desc("FrFX").bool("enab", true).bool("present", true).en("Styl", "FStl", "OutF")
                .en("PntT", "FrFl", "SClr").en("Md  ", "BlnM", "Nrml").unit("Opct", "#Prc", 100)
                .unit("Sz  ", "#Pxl", 1).obj("Clr ", rgb(255, 0, 0));
        Desc inside = new Desc("FrFX").bool("enab", true).bool("present", true).en("Styl", "FStl", "InsF")
                .en("PntT", "FrFl", "SClr").en("Md  ", "BlnM", "Nrml").unit("Opct", "#Prc", 100)
                .unit("Sz  ", "#Pxl", 1).obj("Clr ", rgb(0, 0, 255));
        byte[] d = new Desc("null").bool("masterFXSwitch", true).list("frameFXMulti", outside, inside).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(2, 2, 7, 7).fill(-1, 255).fill(0, 128).fill(1, 128).fill(2, 128)
                .block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "two-strokes.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("stroke")), r.warnings().toString());
        try (PDDocument pdf = open("two-strokes.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {255, 0, 0}, ints(s, (1 * 9 + 4) * 3));
            assertArrayEquals(new int[] {0, 0, 255}, ints(s, (2 * 9 + 4) * 3));
            assertArrayEquals(new int[] {128, 128, 128}, ints(s, (4 * 9 + 4) * 3));
        }
    }

    @Test
    void aShapeStrokeAlreadyInsideTheStoredPixelsIsNotDrawnAgain() throws IOException {
        PsdFixture f = new PsdFixture(8, 8);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 8, 8).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        PsdFixture.Layer shape = new PsdFixture.Layer().rect(1, 1, 7, 7).fill(0, 0).fill(1, 0).fill(2, 0);
        byte[] alpha = new byte[36];
        for (int y = 1; y < 5; y++) {
            for (int x = 1; x < 5; x++) {
                alpha[y * 6 + x] = (byte) 255;
            }
        }
        shape.channels.put(-1, alpha);
        Desc stroke = new Desc("strokeStyle").bool("strokeEnabled", true).bool("fillEnabled", true)
                .unit("strokeStyleLineWidth", "#Pxl", 1)
                .en("strokeStyleLineAlignment", "strokeStyleLineAlignment", "strokeStyleAlignCenter")
                .unit("strokeStyleOpacity", "#Prc", 100)
                .obj("strokeStyleContent", new Desc("solidColorLayer").obj("Clr ", rgb(0, 0, 0)));
        shape.block("vmsk", square(2 / 8.0, 2 / 8.0, 6 / 8.0, 6 / 8.0)).block("vstk", stroke.bytes(true))
                .block("vowv", new byte[4])
                .block("SoCo", new Desc("null").obj("Clr ", rgb(0, 0, 0)).bytes(true));
        f.layers.add(shape);
        convert(f, "snapped.psd");
        try (PDDocument pdf = open("snapped.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {255, 255, 255}, ints(s, (1 * 8 + 4) * 3));
            assertArrayEquals(new int[] {0, 0, 0}, ints(s, (2 * 8 + 4) * 3));
        }
    }

    @Test
    void anOutsideStrokeOnAGradientShapeFollowsThePathNotTheGradientTransparency() throws IOException {
        PsdFixture f = new PsdFixture(8, 8);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 8, 8).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        PsdFixture.Layer shape = new PsdFixture.Layer().rect(2, 2, 6, 6).fill(0, 0).fill(1, 0).fill(2, 0);
        byte[] alpha = new byte[16];
        for (int y = 0; y < 4; y++) {
            alpha[y * 4] = (byte) 255;
            alpha[y * 4 + 1] = (byte) 255;
        }
        shape.channels.put(-1, alpha);
        shape.block("vmsk", square(2 / 8.0, 2 / 8.0, 6 / 8.0, 6 / 8.0)).block("GdFl", gradient())
                .block("lfx2", effects("FrFX", 1));
        f.layers.add(shape);
        convert(f, "gradient-shape-stroke.psd");
        try (PDDocument pdf = open("gradient-shape-stroke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {0, 0, 0}, ints(s, (4 * 8 + 6) * 3));
            assertArrayEquals(new int[] {255, 255, 255}, ints(s, (4 * 8 + 5) * 3));
        }
    }

    @Test
    void anOutsideStrokeShowsThroughTheTranslucentPartOfALayer() throws IOException {
        PsdFixture f = new PsdFixture(10, 10);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 10, 10).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        Desc stroke = new Desc("FrFX").bool("enab", true).en("Styl", "FStl", "OutF").en("PntT", "FrFl", "SClr")
                .unit("Opct", "#Prc", 100).unit("Sz  ", "#Pxl", 1).obj("Clr ", rgb(255, 0, 0));
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("FrFX", stroke).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        PsdFixture.Layer half = new PsdFixture.Layer().rect(1, 1, 9, 9).fill(0, 0).fill(1, 0).fill(2, 0);
        byte[] alpha = new byte[64];
        for (int i = 0; i < 64; i++) {
            alpha[i] = (byte) (i % 8 < 5 ? 128 : 255);
        }
        half.channels.put(-1, alpha);
        f.layers.add(half.block("lfx2", fx));
        convert(f, "translucent-outside-stroke.psd");
        try (PDDocument pdf = open("translucent-outside-stroke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            int[] inside = ints(s, (5 * 10 + 3) * 3);
            assertEquals(127, inside[0], 3);
            assertEquals(64, inside[1], 3);
            assertArrayEquals(new int[] {255, 0, 0}, ints(s, (5 * 10) * 3));
        }
    }

    @Test
    void anInsideStrokeFollowsAShapeThatRunsPastTheCanvas() throws IOException {
        PsdFixture f = new PsdFixture(8, 8);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 8, 8).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0)
                .block("SoCo", new Desc("null").obj("Clr ", rgb(255, 0, 0)).bytes(true))
                .block("vmsk", square(-2 / 8.0, -2 / 8.0, 10 / 8.0, 10 / 8.0)).block("lfx2", inside(3, 0, 0, 0)));
        convert(f, "beyond.psd");
        try (PDDocument pdf = open("beyond.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {0, 0, 0}, ints(s, (4 * 8) * 3));
            assertArrayEquals(new int[] {255, 0, 0}, ints(s, (4 * 8 + 4) * 3));
        }
    }

    @Test
    void anInsideStrokeAlsoCoversFaintEdgePixels() throws IOException {
        PsdFixture f = new PsdFixture(8, 8);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 8, 8).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        PsdFixture.Layer box = new PsdFixture.Layer().rect(2, 2, 6, 6).fill(0, 0).fill(1, 0).fill(2, 0);
        byte[] alpha = new byte[16];
        for (int i = 0; i < 16; i++) {
            alpha[i] = (byte) (i % 4 == 0 ? 100 : 255);
        }
        box.channels.put(-1, alpha);
        f.layers.add(box.block("lfx2", inside(1, 0, 0, 255)));
        convert(f, "faint-edge.psd");
        try (PDDocument pdf = open("faint-edge.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            int[] edge = ints(s, (4 * 8 + 2) * 3);
            assertTrue(edge[2] > edge[0] + 50, java.util.Arrays.toString(edge));
        }
    }

    @Test
    void aRasterLayerWithAnOpenVectorMaskGetsNoStrokeAlongTheCanvasEdge() throws IOException {
        PsdFixture f = new PsdFixture(8, 8);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 8, 8).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        byte[] all = new byte[8 + 26];
        all[3] = 3;
        all[9] = 8;
        all[11] = 1;
        Desc stroke = new Desc("FrFX").bool("enab", true).en("Styl", "FStl", "OutF").en("PntT", "FrFl", "SClr")
                .unit("Opct", "#Prc", 100).unit("Sz  ", "#Pxl", 1).obj("Clr ", rgb(255, 0, 0));
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("FrFX", stroke).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(3, 3, 5, 5).fill(-1, 255).fill(0, 0).fill(1, 0).fill(2, 0)
                .block("vmsk", all).block("lfx2", fx));
        convert(f, "open-mask.psd");
        try (PDDocument pdf = open("open-mask.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {255, 255, 255}, ints(s, 0));
            assertArrayEquals(new int[] {255, 0, 0}, ints(s, (4 * 8 + 2) * 3));
        }
    }

    private static byte[] inside(double size, double r, double g, double b) {
        Desc stroke = new Desc("FrFX").bool("enab", true).en("Styl", "FStl", "InsF").en("PntT", "FrFl", "SClr")
                .unit("Opct", "#Prc", 100).unit("Sz  ", "#Pxl", size).obj("Clr ", rgb(r, g, b));
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("FrFX", stroke).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        return fx;
    }

    @Test
    void aRestrictedChannelKeepsWhatIsBelowTheLayer() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 255).fill(1, 255).fill(2, 255));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 0).fill(1, 0).fill(2, 0)
                .block("brst", new byte[] {0, 0, 0, 2}));
        convert(f, "restricted.psd");
        try (PDDocument pdf = open("restricted.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {0, 0, 255}, ints(s, 0));
            assertArrayEquals(new int[] {0, 0, 255}, ints(s, 3));
        }
    }

    @Test
    void aChokedInnerShadowThickensInsideTheEdge() throws IOException {
        PsdFixture f = new PsdFixture(16, 16);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        Desc inner = new Desc("IrSh").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .obj("Clr ", rgb(0, 0, 0)).unit("Opct", "#Prc", 100).bool("uglg", false).unit("lagl", "#Ang", 90)
                .unit("Dstn", "#Pxl", 0).unit("blur", "#Pxl", 2).unit("Ckmt", "#Prc", 100);
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("IrSh", inner).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(2, 2, 14, 14).fill(-1, 255).fill(0, 200).fill(1, 200).fill(2, 200)
                .block("lfx2", fx));
        convert(f, "choke.psd");
        try (PDDocument pdf = open("choke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertTrue((s[(2 * 16 + 8) * 3] & 0xFF) < 100, "edge " + (s[(2 * 16 + 8) * 3] & 0xFF));
            assertEquals(200, s[(8 * 16 + 8) * 3] & 0xFF);
        }
    }

    @Test
    void aGradientOuterGlowTakesItsColourFromTheGradient() throws IOException {
        PsdFixture f = new PsdFixture(16, 16);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        Desc stop0 = new Desc("Clrt").obj("Clr ", rgb(0, 0, 255)).lng("Lctn", 0).lng("Mdpn", 50);
        Desc stop1 = new Desc("Clrt").obj("Clr ", rgb(0, 0, 255)).lng("Lctn", 4096).lng("Mdpn", 50);
        Desc glow = new Desc("OrGl").bool("enab", true).bool("present", true).en("Md  ", "BlnM", "Nrml")
                .obj("Grad", new Desc("Grdn").list("Clrs", stop0, stop1)).unit("Opct", "#Prc", 100)
                .unit("blur", "#Pxl", 3).unit("Ckmt", "#Prc", 50);
        byte[] d = new Desc("null").bool("masterFXSwitch", true).obj("OrGl", glow).bytes(true);
        byte[] fx = new byte[d.length + 4];
        System.arraycopy(d, 0, fx, 4, d.length);
        f.layers.add(new PsdFixture.Layer().rect(4, 4, 12, 12).fill(-1, 255).fill(0, 200).fill(1, 200).fill(2, 200)
                .block("lfx2", fx));
        OfficeToPdf.Result r = convert(f, "gradient-glow.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("glow")), r.warnings().toString());
        try (PDDocument pdf = open("gradient-glow.psd")) {
            int[] beside = ints(samples(image(pdf.getPage(0))), (8 * 16 + 3) * 3);
            assertTrue(beside[0] < 255 && beside[2] == 255, Arrays.toString(beside));
        }
    }

    @Test
    void aPatternTooLargeToHoldIsLeftOutWithAWarning() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        ByteArrayOutputStream huge = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(huge);
        o.writeInt(1);
        o.writeInt(PsdDocument.RGB);
        o.writeInt(0);
        o.writeInt(0);
        o.write(2);
        o.writeBytes("p2");
        o.writeInt(3);
        o.writeInt(20);
        o.writeInt(0);
        o.writeInt(0);
        o.writeInt(30000);
        o.writeInt(30000);
        o.writeInt(3);
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        new DataOutputStream(block).writeInt(huge.size());
        block.write(huge.toByteArray());
        f.global("Patt", block.toByteArray());
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("PtFl", PsdFixture.patternFill("p2")));
        OfficeToPdf.Result r = convert(f, "huge-pattern.psd");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("pattern fill")), r.warnings().toString());
    }

    @Test
    void colorBlendingInCmykKeepsTheBlackOfWhatIsBelow() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.CMYK;
        f.flat(255).flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 128).fill(1, 128).fill(2, 128).fill(3, 255));
        PsdFixture.Layer top = new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 255).fill(1, 0)
                .fill(2, 0).fill(3, 128);
        top.blend = "colr";
        f.layers.add(top);
        convert(f, "cmyk-color.psd");
        try (PDDocument pdf = open("cmyk-color.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(0, s[3] & 0xFF);
            assertTrue((s[1] & 0xFF) > (s[0] & 0xFF), java.util.Arrays.toString(s));
        }
    }

    @Test
    void vividLightAndHardMixFollowTheSourceAtTheEnds() throws IOException {
        for (String[] c : new String[][] {{"vLit", "0,255,0"}, {"hMix", "0,0,255"}}) {
            PsdFixture f = new PsdFixture(1, 1);
            f.hasMerged = false;
            f.flat(255).flat(255).flat(255);
            f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 0).fill(1, 0).fill(2, 255));
            PsdFixture.Layer top = new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 0).fill(1, 255)
                    .fill(2, 0);
            top.blend = c[0];
            f.layers.add(top);
            convert(f, c[0] + ".psd");
            try (PDDocument pdf = open(c[0] + ".psd")) {
                assertEquals(c[1], java.util.Arrays.toString(ints(samples(image(pdf.getPage(0))), 0))
                        .replace("[", "").replace("]", "").replace(" ", ""));
            }
        }
    }

    @Test
    void aPassThroughGroupWithReducedFillKeepsItsAdjustmentInside() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 200));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).section(3));
        f.layers.add(new PsdFixture.Layer().rect(0, 1, 1, 2).fill(-1, 255).fill(0, 100));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("nvrt", new byte[0]));
        PsdFixture.Layer group = new PsdFixture.Layer().rect(0, 0, 0, 0)
                .block("lsct", new byte[] {0, 0, 0, 1, '8', 'B', 'I', 'M', 'p', 'a', 's', 's'})
                .block("iOpa", new byte[] {(byte) 255, 0, 0, 0});
        group.blend = "pass";
        f.layers.add(group);
        convert(f, "passthrough.psd");
        try (PDDocument pdf = open("passthrough.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(55, s[0] & 0xFF);
            assertEquals(155, s[1] & 0xFF);
        }
        f.layers.get(4).blocks.reset();
        f.layers.get(4).block("lsct", new byte[] {0, 0, 0, 1, '8', 'B', 'I', 'M', 'p', 'a', 's', 's'})
                .block("iOpa", new byte[] {(byte) 128, 0, 0, 0});
        convert(f, "passthrough-fill.psd");
        try (PDDocument pdf = open("passthrough-fill.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(200, s[0] & 0xFF);
        }
    }

    @Test
    void aStrokeOnAGroupFollowsWhatTheGroupDraws() throws IOException {
        PsdFixture f = new PsdFixture(9, 9);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 9, 9).fill(0, 255));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).section(3));
        f.layers.add(new PsdFixture.Layer().rect(3, 3, 6, 6).fill(-1, 255).fill(0, 200));
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).section(1).block("lfxs", effects("FrFX", 2)));
        convert(f, "group-stroke.psd");
        try (PDDocument pdf = open("group-stroke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(200, s[4 * 9 + 4] & 0xFF);
            assertEquals(0, s[4 * 9 + 1] & 0xFF);
        }
    }

    @Test
    void anOutsideStrokeStaysOutsideATranslucentLayer() throws IOException {
        PsdFixture f = new PsdFixture(9, 9);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 9, 9).fill(0, 255));
        PsdFixture.Layer box = new PsdFixture.Layer().rect(3, 3, 6, 6).fill(-1, 128).fill(0, 200);
        box.block("lfx2", effects("FrFX", 2));
        f.layers.add(box);
        convert(f, "translucent-stroke.psd");
        try (PDDocument pdf = open("translucent-stroke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(227, s[4 * 9 + 4] & 0xFF, 2);
            assertEquals(0, s[4 * 9 + 1] & 0xFF);
        }
    }

    @Test
    void aPathAfterAFilledStartLimitsTheLayerToThePath() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        byte[] square = square(0, 0, 1, 0.5);
        java.nio.ByteBuffer vm = java.nio.ByteBuffer.allocate(8 + 26 + square.length - 8);
        vm.putInt(3).putInt(0).putShort((short) 8).putShort((short) 1).put(new byte[22]);
        vm.put(square, 8, square.length - 8);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 30).block("vmsk", vm.array()));
        convert(f, "startfilled.psd");
        try (PDDocument pdf = open("startfilled.psd")) {
            assertArrayEquals(new byte[] {30, (byte) 255}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void levelsMapTheInputRange() throws IOException {
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 100).fill(1, 100).fill(2, 100));
        java.nio.ByteBuffer levels = java.nio.ByteBuffer.allocate(2 + 29 * 10);
        levels.putShort((short) 2);
        for (int i = 0; i < 29; i++) {
            levels.putShort((short) (i == 0 ? 50 : 0)).putShort((short) (i == 0 ? 150 : 255)).putShort((short) 0)
                    .putShort((short) 255).putShort((short) 100);
        }
        PsdFixture.Layer adjust = new PsdFixture.Layer().rect(0, 0, 0, 0).block("levl", levels.array());
        f.layers.add(adjust);
        convert(f, "levels.psd");
        try (PDDocument pdf = open("levels.psd")) {
            assertArrayEquals(new int[] {128, 128, 128}, ints(samples(image(pdf.getPage(0))), 0));
        }
    }

    @Test
    void aGradientFillLayerRunsAcrossTheCanvas() throws IOException {
        PsdFixture f = new PsdFixture(8, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        PsdFixture.Layer fill = new PsdFixture.Layer().rect(0, 0, 0, 0);
        fill.channels.put(-1, new byte[0]);
        fill.block("GdFl", gradient());
        f.layers.add(fill);
        convert(f, "gradient.psd");
        try (PDDocument pdf = open("gradient.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertTrue((s[0] & 0xFF) < 40, Arrays.toString(s));
            assertTrue((s[21] & 0xFF) > 215, Arrays.toString(s));
            for (int x = 1; x < 8; x++) {
                assertTrue((s[3 * x] & 0xFF) > (s[3 * x - 3] & 0xFF), Arrays.toString(s));
            }
        }
    }

    @Test
    void aReflectedGradientOnATinyCanvasEndsOnWholePixels() throws IOException {
        PsdFixture f = new PsdFixture(4, 4);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        PsdFixture.Layer fill = new PsdFixture.Layer().rect(0, 0, 0, 0);
        fill.channels.put(-1, new byte[0]);
        Desc stop0 = new Desc("Clrt").obj("Clr ", rgb(0, 0, 0)).lng("Lctn", 0).lng("Mdpn", 50);
        Desc stop1 = new Desc("Clrt").obj("Clr ", rgb(255, 255, 255)).lng("Lctn", 4096).lng("Mdpn", 50);
        Desc grad = new Desc("Grdn").list("Clrs", stop0, stop1);
        fill.block("GdFl", new Desc("null").unit("Angl", "#Ang", 30).en("Type", "GrdT", "Rflc").obj("Grad", grad)
                .bytes(true));
        f.layers.add(fill);
        convert(f, "tiny-gradient.psd");
        try (PDDocument pdf = open("tiny-gradient.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(s[(Math.abs(x - y) * 4) * 3] & 0xFF, s[(y * 4 + x) * 3] & 0xFF, 1,
                            Arrays.toString(s));
                }
            }
        }
    }

    @Test
    void aHueSaturationColourRangeChangesOnlyItsHues() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        PsdFixture.Layer base = new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(1, 0).fill(2, 0);
        base.channels.put(0, new byte[] {(byte) 255, 0});
        base.channels.put(2, new byte[] {0, (byte) 255});
        f.layers.add(base);
        java.nio.ByteBuffer hue = java.nio.ByteBuffer.allocate(2 + 2 + 12 + 6 * 14);
        hue.putShort((short) 2).put((byte) 0).put((byte) 0);
        hue.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        hue.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        hue.putShort((short) 315).putShort((short) 345).putShort((short) 15).putShort((short) 45);
        hue.putShort((short) 0).putShort((short) -100).putShort((short) 0);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 0, 0).block("hue2", hue.array()));
        OfficeToPdf.Result r = convert(f, "hue-range.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("left out")), r.warnings().toString());
        try (PDDocument pdf = open("hue-range.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertArrayEquals(new int[] {128, 128, 128}, ints(s, 0));
            assertArrayEquals(new int[] {0, 0, 255}, ints(s, 3));
        }
    }

    @Test
    void anOutsideStrokeIsDrawnAroundTheLayer() throws IOException {
        PsdFixture f = new PsdFixture(9, 9);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 9, 9).fill(0, 255));
        PsdFixture.Layer box = new PsdFixture.Layer().rect(3, 3, 6, 6).fill(-1, 255).fill(0, 200);
        box.block("lfx2", effects("FrFX", 2));
        f.layers.add(box);
        OfficeToPdf.Result r = convert(f, "stroke.psd");
        assertFalse(r.warnings().stream().anyMatch(w -> w.contains("stroke")), r.warnings().toString());
        try (PDDocument pdf = open("stroke.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(200, s[4 * 9 + 4] & 0xFF);
            assertEquals(0, s[4 * 9 + 1] & 0xFF);
            assertEquals(255, s[4 * 9 + 0] & 0xFF, 2);
        }
    }

    @Test
    void aVectorMaskWithNoPathThatStartsFilledShowsTheWholeLayer() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        java.nio.ByteBuffer vm = java.nio.ByteBuffer.allocate(8 + 26);
        vm.putInt(3).putInt(0).putShort((short) 8).putShort((short) 1);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 30).block("vmsk", vm.array()));
        convert(f, "allpath.psd");
        try (PDDocument pdf = open("allpath.psd")) {
            assertArrayEquals(new byte[] {30, 30}, samples(image(pdf.getPage(0))));
        }
    }

    @Test
    void maskParametersAfterARealMaskAreReadInOrder() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 255));
        PsdFixture.Layer l = new PsdFixture.Layer().rect(0, 0, 1, 2).fill(-1, 255).fill(0, 0);
        l.mask = new int[] {0, 0, 1, 1};
        l.maskFill = 0;
        l.maskDensity = 128;
        l.channels.put(-2, new byte[] {0});
        f.layers.add(l);
        convert(f, "density.psd");
        try (PDDocument pdf = open("density.psd")) {
            byte[] s = samples(image(pdf.getPage(0)));
            assertEquals(128, s[0] & 0xFF, 2);
            assertEquals(128, s[1] & 0xFF, 2);
        }
    }

    private static byte[] gradient() {
        Desc stop0 = new Desc("Clrt").obj("Clr ", rgb(0, 0, 0)).lng("Lctn", 0).lng("Mdpn", 50);
        Desc stop1 = new Desc("Clrt").obj("Clr ", rgb(255, 255, 255)).lng("Lctn", 4096).lng("Mdpn", 50);
        Desc grad = new Desc("Grdn").list("Clrs", stop0, stop1);
        Desc root = new Desc("null").unit("Angl", "#Ang", 0).en("Type", "GrdT", "Lnr ").obj("Grad", grad);
        return root.bytes(true);
    }

    private static byte[] effects(String key, double size) {
        Desc stroke = new Desc(key).bool("enab", true).en("Styl", "FStl", "OutF").en("PntT", "FrFl", "SClr")
                .unit("Opct", "#Prc", 100).unit("Sz  ", "#Pxl", size).obj("Clr ", rgb(0, 0, 0));
        Desc root = new Desc("null").bool("masterFXSwitch", true).obj(key, stroke);
        byte[] d = root.bytes(true);
        byte[] out = new byte[d.length + 4];
        System.arraycopy(d, 0, out, 4, d.length);
        return out;
    }

    private static Desc rgb(double r, double g, double b) {
        return new Desc("RGBC").dbl("Rd  ", r).dbl("Grn ", g).dbl("Bl  ", b);
    }

    private static final class Desc {
        final String cls;
        final java.io.ByteArrayOutputStream items = new java.io.ByteArrayOutputStream();
        final java.io.DataOutputStream o = new java.io.DataOutputStream(items);
        int count;

        Desc(String cls) {
            this.cls = cls;
        }

        private Desc key(String k, String type) {
            try {
                id(o, k);
                o.writeBytes(type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            count++;
            return this;
        }

        static void id(java.io.DataOutputStream o, String k) throws IOException {
            o.writeInt(k.length() == 4 ? 0 : k.length());
            o.writeBytes(k);
        }

        Desc dbl(String k, double v) {
            key(k, "doub");
            try {
                o.writeDouble(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc unit(String k, String unit, double v) {
            key(k, "UntF");
            try {
                o.writeBytes(unit);
                o.writeDouble(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc lng(String k, int v) {
            key(k, "long");
            try {
                o.writeInt(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc bool(String k, boolean v) {
            key(k, "bool");
            try {
                o.writeByte(v ? 1 : 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc en(String k, String type, String value) {
            key(k, "enum");
            try {
                id(o, type);
                id(o, value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc text(String k, String v) {
            key(k, "TEXT");
            try {
                o.writeInt(v.length());
                o.writeChars(v);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc obj(String k, Desc d) {
            key(k, "Objc");
            try {
                o.write(d.bytes(false));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Desc list(String k, Desc... ds) {
            key(k, "VlLs");
            try {
                o.writeInt(ds.length);
                for (Desc d : ds) {
                    o.writeBytes("Objc");
                    o.write(d.bytes(false));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        byte[] bytes(boolean versioned) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream d = new java.io.DataOutputStream(b);
            try {
                if (versioned) {
                    d.writeInt(16);
                }
                d.writeInt(1);
                d.writeChar(0);
                id(d, cls);
                d.writeInt(count);
                d.write(items.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return b.toByteArray();
        }
    }

    @Test
    void aBitmapWithoutAMergedPictureIsDrawnFromItsBlankCompositeWithAWarning() throws IOException {
        PsdFixture f = new PsdFixture(8, 1);
        f.mode = PsdDocument.BITMAP;
        f.depth = 1;
        f.hasMerged = false;
        f.plane(0);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 8).fill(0, 255));
        OfficeToPdf.Result r = convert(f, "bitmap-layers.psd");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Maximize Compatibility")), r.warnings().toString());
    }

    @Test
    void aLayerFarAboveTheCanvasIsSkippedQuickly() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 90));
        PsdFixture.Layer far = new PsdFixture.Layer().rect(-2_000_000_000, 0, 1, 2);
        far.channels.put(0, new byte[0]);
        far.channels.put(-1, new byte[0]);
        f.layers.add(far);
        long start = System.nanoTime();
        convert(f, "far.psd");
        assertTrue(System.nanoTime() - start < 30_000_000_000L);
        try (PDDocument pdf = open("far.psd")) {
            assertArrayEquals(new byte[] {90, 90}, samples(image(pdf.getPage(0))));
        }
    }

    private static int[] ints(byte[] s, int at) {
        return new int[] {s[at] & 0xFF, s[at + 1] & 0xFF, s[at + 2] & 0xFF};
    }

    private static void put(byte[] b, int at, float v) {
        int bits = Float.floatToIntBits(v);
        b[at] = (byte) (bits >> 24);
        b[at + 1] = (byte) (bits >> 16);
        b[at + 2] = (byte) (bits >> 8);
        b[at + 3] = (byte) bits;
    }

    private static byte[] profile(String space) {
        byte[] p = new byte[132];
        p[3] = (byte) 132;
        byte[] s = space.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(s, 0, p, 16, 4);
        p[36] = 'a';
        p[37] = 'c';
        p[38] = 's';
        p[39] = 'p';
        return p;
    }

    private static byte[] solid(double r, double g, double b) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(o);
        try {
            d.writeInt(16);
            for (Object[] e : new Object[][] {{"Rd  ", r}, {"Grn ", g}, {"Bl  ", b}}) {
                d.writeInt(0);
                d.writeBytes((String) e[0]);
                d.writeBytes("doub");
                d.writeDouble((Double) e[1]);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return o.toByteArray();
    }

    private static byte[] square(double top, double left, double bottom, double right) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream d = new java.io.DataOutputStream(o);
        try {
            d.writeInt(3);
            d.writeInt(0);
            d.writeShort(0);
            d.writeShort(4);
            d.writeShort(1);
            d.write(new byte[20]);
            double[][] pts = {{top, left}, {top, right}, {bottom, right}, {bottom, left}};
            for (double[] p : pts) {
                d.writeShort(2);
                for (int i = 0; i < 3; i++) {
                    d.writeInt((int) Math.round(p[0] * (1 << 24)));
                    d.writeInt((int) Math.round(p[1] * (1 << 24)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return o.toByteArray();
    }
}
