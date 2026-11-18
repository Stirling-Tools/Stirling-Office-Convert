package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrayscaleTest {

    private static final PdfOptimizer.Options GRAY = PdfOptimizer.Options.defaults().grayscale(true);

    @TempDir
    Path dir;

    @Test
    void deviceColoursBecomeGrayOperators() throws Exception {
        Path out = optimize(doc -> page(doc, "1 0 0 rg 0 0 10 10 re f 0 0 1 RG 0 0 10 10 re S 0 0 0 1 k 0 0 1 0 K"));

        String content = content(out);

        assertFalse(content.contains(" rg") || content.contains(" RG") || content.contains(" k")
                || content.contains(" K"), content);
        assertTrue(has(content, "0?\\.3 g"), content);
        assertTrue(has(content, "0?\\.11 G"), content);
        assertTrue(has(content, "(^|\\s)0 g"), content);
        assertTrue(has(content, "0?\\.89 G"), content);
    }

    @Test
    void aNamedColourSpaceIsReplacedAndItsValuesConverted() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "/CS0 cs 1 sc 0 0 10 10 re f");
            COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICERGB);
            indexed.add(COSInteger.get(1));
            indexed.add(new COSString(new byte[] {(byte) 255, 0, 0, 0, (byte) 255, 0}));
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("CS0"), indexed);
            page.getResources().getCOSObject().setItem(COSName.COLORSPACE, spaces);
        });

        String content = content(out);

        assertTrue(content.contains("/DeviceGray cs"), content);
        assertTrue(has(content, "0?\\.59 sc"), content);
    }

    @Test
    void shadingsAreResampledInGray() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "/Sh0 sh");
            COSDictionary function = new COSDictionary();
            function.setInt(COSName.FUNCTION_TYPE, 2);
            function.setItem(COSName.DOMAIN, floats(0, 1));
            function.setItem(COSName.C0, floats(1, 0, 0));
            function.setItem(COSName.C1, floats(0, 0, 1));
            function.setInt(COSName.N, 1);
            COSDictionary shading = new COSDictionary();
            shading.setInt(COSName.SHADING_TYPE, 2);
            shading.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            shading.setItem(COSName.COORDS, floats(0, 0, 100, 0));
            shading.setItem(COSName.FUNCTION, function);
            COSDictionary shadings = new COSDictionary();
            shadings.setItem(COSName.getPDFName("Sh0"), shading);
            page.getResources().getCOSObject().setItem(COSName.SHADING, shadings);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDShading shading = doc.getPage(0).getResources().getShading(COSName.getPDFName("Sh0"));
            assertEquals(COSName.DEVICEGRAY, shading.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
            assertEquals(0.3f, shading.evalFunction(new float[] {0})[0], 0.01f);
            assertEquals(0.11f, shading.evalFunction(new float[] {1})[0], 0.01f);
        }
    }

    @Test
    void annotationColoursAndDefaultAppearancesTurnGray() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "");
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.ANNOT);
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("FreeText"));
            annot.setItem(COSName.RECT, floats(0, 0, 50, 50));
            annot.setItem(COSName.C, floats(1, 0, 0));
            annot.setString(COSName.DA, "0 0 1 rg /Helv 12 Tf");
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSDictionary annot = (COSDictionary) doc.getPage(0).getCOSObject().getCOSArray(COSName.ANNOTS).getObject(0);
            assertEquals(1, annot.getCOSArray(COSName.C).size());
            assertEquals(0.3f, ((COSFloat) annot.getCOSArray(COSName.C).getObject(0)).floatValue(), 0.001f);
            assertTrue(has(annot.getString(COSName.DA), "0?\\.11 g"), annot.getString(COSName.DA));
        }
    }

    @Test
    void inlineColourImagesBecomeGray() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            COSStream content = doc.getDocument().createCOSStream();
            try (OutputStream os = content.createOutputStream()) {
                os.write("q 10 0 0 10 0 0 cm BI /W 2 /H 1 /BPC 8 /CS /RGB ID ".getBytes(StandardCharsets.ISO_8859_1));
                os.write(new byte[] {(byte) 255, 0, 0, 0, 0, (byte) 255});
                os.write("\nEI Q".getBytes(StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);
        });

        String content = content(out);

        assertTrue(content.contains("/CS /G"), content);
        assertFalse(content.contains("/RGB"), content);
    }

    @Test
    void anInlineImageTooLargeToDecodeIsLeftInColour() throws Exception {
        Path out = optimize(doc -> page(doc, "q 10 0 0 10 0 0 cm BI /W 26000 /H 26000 /BPC 8 /CS /RGB ID \0\0\0\nEI Q"));

        assertTrue(content(out).contains("/RGB"), content(out));
    }

    @Test
    void anInlineImageWhoseGraySamplesSpellEiStillParsesWhole() throws Exception {
        int[] greys = {32, 69, 73, 32, 10, 69, 73, 13};
        Path out = optimize(doc -> {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            COSStream content = doc.getDocument().createCOSStream();
            try (OutputStream os = content.createOutputStream()) {
                os.write(("q 10 0 0 10 0 0 cm BI /W " + greys.length + " /H 1 /BPC 8 /CS /RGB ID ")
                        .getBytes(StandardCharsets.ISO_8859_1));
                for (int g : greys) {
                    os.write(new byte[] {(byte) g, (byte) g, (byte) g});
                }
                os.write("\nEI Q 0 0 1 1 re f".getBytes(StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<Object> tokens = new org.apache.pdfbox.pdfparser.PDFStreamParser(doc.getPage(0)).parse();
            org.apache.pdfbox.contentstream.operator.Operator image = null;
            int operators = 0;
            for (Object token : tokens) {
                if (token instanceof org.apache.pdfbox.contentstream.operator.Operator op) {
                    operators++;
                    if ("BI".equals(op.getName())) {
                        image = op;
                    }
                }
            }
            assertTrue(image != null, "no inline image");
            byte[] samples = new org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage(image.getImageParameters(),
                    image.getImageData(), new PDResources()).getData();
            assertEquals(greys.length, samples.length);
            for (int i = 0; i < greys.length; i++) {
                assertEquals(greys[i], samples[i] & 0xFF, "sample " + i);
            }
            assertEquals(6, operators, tokens.toString());
            assertFalse(new String(image.getImageData(), StandardCharsets.ISO_8859_1).contains("EI"));
        }
    }

    @Test
    void meshShadingColoursAreRepackedInGray() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "/Sh0 sh");
            COSStream mesh = doc.getDocument().createCOSStream();
            mesh.setInt(COSName.SHADING_TYPE, 4);
            mesh.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            mesh.setInt(COSName.BITS_PER_COORDINATE, 8);
            mesh.setInt(COSName.BITS_PER_COMPONENT, 8);
            mesh.setInt(COSName.BITS_PER_FLAG, 8);
            mesh.setItem(COSName.DECODE, floats(0, 255, 0, 255, 0, 1, 0, 1, 0, 1));
            try (OutputStream os = mesh.createOutputStream()) {
                os.write(new byte[] {0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0, 0,
                        (byte) 255, 0, 0, (byte) 255});
            }
            COSDictionary shadings = new COSDictionary();
            shadings.setItem(COSName.getPDFName("Sh0"), mesh);
            page.getResources().getCOSObject().setItem(COSName.SHADING, shadings);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSStream mesh = (COSStream) doc.getPage(0).getResources().getShading(COSName.getPDFName("Sh0"))
                    .getCOSObject();
            assertEquals(COSName.DEVICEGRAY, mesh.getDictionaryObject(COSName.COLORSPACE));
            byte[] data = mesh.createInputStream().readAllBytes();
            assertEquals(12, data.length);
            assertEquals(Math.round(0.3f * 255), data[3] & 0xFF);
            assertEquals(Math.round(0.59f * 255), data[7] & 0xFF);
            assertEquals(Math.round(0.11f * 255), data[11] & 0xFF);
        }
    }

    @Test
    void uncolouredPatternsTakeGrayComponents() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "/P0 cs 1 0 0 /Pat scn 0 0 10 10 re f");
            COSArray patternSpace = new COSArray();
            patternSpace.add(COSName.PATTERN);
            patternSpace.add(COSName.DEVICERGB);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("P0"), patternSpace);
            COSStream tile = doc.getDocument().createCOSStream();
            tile.setInt(COSName.PATTERN_TYPE, 1);
            tile.setInt(COSName.PAINT_TYPE, 2);
            tile.setInt(COSName.TILING_TYPE, 1);
            tile.setItem(COSName.BBOX, floats(0, 0, 4, 4));
            tile.setInt(COSName.X_STEP, 4);
            tile.setInt(COSName.Y_STEP, 4);
            tile.setItem(COSName.RESOURCES, new COSDictionary());
            try (OutputStream os = tile.createOutputStream()) {
                os.write("0 0 2 2 re f".getBytes(StandardCharsets.ISO_8859_1));
            }
            COSDictionary patterns = new COSDictionary();
            patterns.setItem(COSName.getPDFName("Pat"), tile);
            page.getResources().getCOSObject().setItem(COSName.COLORSPACE, spaces);
            page.getResources().getCOSObject().setItem(COSName.PATTERN, patterns);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSArray space = (COSArray) doc.getPage(0).getResources().getCOSObject()
                    .getCOSDictionary(COSName.COLORSPACE).getDictionaryObject(COSName.getPDFName("P0"));
            assertEquals(COSName.DEVICEGRAY, space.getObject(1));
        }
        assertTrue(has(content(out), "0?\\.3 ?/Pat scn"), content(out));
    }

    @Test
    void anUncolouredPatternSpaceUsedAgainKeepsConverting() throws Exception {
        Path in = dir.resolve("reused.pdf");
        String operators = "/P0 cs 1 0 0 /Pat scn 0 0 10 10 re f /P0 cs 0 1 0 /Pat scn 0 0 5 5 re f";
        try (PDDocument doc = new PDDocument()) {
            PDPage first = page(doc, operators);
            COSArray patternSpace = new COSArray();
            patternSpace.add(COSName.PATTERN);
            patternSpace.add(COSName.DEVICERGB);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("P0"), patternSpace);
            COSStream tile = doc.getDocument().createCOSStream();
            tile.setInt(COSName.PATTERN_TYPE, 1);
            tile.setInt(COSName.PAINT_TYPE, 2);
            tile.setInt(COSName.TILING_TYPE, 1);
            tile.setItem(COSName.BBOX, floats(0, 0, 4, 4));
            tile.setInt(COSName.X_STEP, 4);
            tile.setInt(COSName.Y_STEP, 4);
            tile.setItem(COSName.RESOURCES, new COSDictionary());
            try (OutputStream os = tile.createOutputStream()) {
                os.write("0 0 2 2 re f".getBytes(StandardCharsets.ISO_8859_1));
            }
            COSDictionary patterns = new COSDictionary();
            patterns.setItem(COSName.getPDFName("Pat"), tile);
            COSDictionary shared = first.getResources().getCOSObject();
            shared.setDirect(false);
            shared.setItem(COSName.COLORSPACE, spaces);
            shared.setItem(COSName.PATTERN, patterns);
            PDPage second = page(doc, operators);
            second.setResources(first.getResources());
            doc.save(in.toFile());
        }
        Path out = dir.resolve("reused-out.pdf");

        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, GRAY);

        assertEquals(List.of(), result.warnings());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            for (int p = 0; p < 2; p++) {
                String content = new String(doc.getPage(p).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(has(content, "\\d\\s+[\\d.]+\\s+[\\d.]+\\s*/Pat scn"), content);
                assertTrue(has(content, "0?\\.3 ?/Pat scn"), content);
                assertTrue(has(content, "0?\\.59 ?/Pat scn"), content);
            }
        }
    }

    @Test
    void anOperationSplitAcrossContentStreamsIsStillConverted() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = new PDPage();
            doc.addPage(page);
            page.setResources(new PDResources());
            COSArray parts = new COSArray();
            for (String part : new String[] {"1 0 0", "rg 0 0 10 10 re f"}) {
                COSStream stream = doc.getDocument().createCOSStream();
                try (OutputStream os = stream.createOutputStream()) {
                    os.write(part.getBytes(StandardCharsets.ISO_8859_1));
                }
                parts.add(stream);
            }
            page.getCOSObject().setItem(COSName.CONTENTS, parts);
        });

        String content = content(out);

        assertTrue(has(content, "0?\\.3 g"), content);
        assertFalse(content.contains("rg"), content);
    }

    @Test
    void anEncryptedPdfKeepsItsColoursWithAWarning() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            page(doc, "1 0 0 rg 0 0 10 10 re f");
            org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy =
                    new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "",
                            new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");

        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, GRAY);

        assertTrue(result.warnings().contains("The PDF is encrypted, so its images and colours were kept as they are"),
                result.warnings().toString());
    }

    @Test
    void perChannelTransferFunctionsAndDefaultGrayAreDropped() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "/GS0 gs /DefaultGray cs 0 sc 0 0 10 10 re f");
            COSArray identity = new COSArray();
            for (int i = 0; i < 4; i++) {
                identity.add(COSName.IDENTITY);
            }
            COSDictionary state = new COSDictionary();
            state.setItem(COSName.TYPE, COSName.EXT_G_STATE);
            state.setItem(COSName.TR, identity);
            COSDictionary states = new COSDictionary();
            states.setItem(COSName.getPDFName("GS0"), state);
            COSArray separation = new COSArray();
            separation.add(COSName.SEPARATION);
            separation.add(COSName.getPDFName("Red"));
            separation.add(COSName.DEVICERGB);
            COSDictionary tint = new COSDictionary();
            tint.setInt(COSName.FUNCTION_TYPE, 2);
            tint.setItem(COSName.DOMAIN, floats(0, 1));
            tint.setItem(COSName.C0, floats(1, 1, 1));
            tint.setItem(COSName.C1, floats(1, 0, 0));
            tint.setInt(COSName.N, 1);
            separation.add(tint);
            COSDictionary spaces = new COSDictionary();
            spaces.setItem(COSName.getPDFName("DefaultGray"), separation);
            page.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
            page.getResources().getCOSObject().setItem(COSName.COLORSPACE, spaces);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSDictionary resources = doc.getPage(0).getResources().getCOSObject();
            COSDictionary state = (COSDictionary) resources.getCOSDictionary(COSName.EXT_G_STATE)
                    .getDictionaryObject(COSName.getPDFName("GS0"));
            assertEquals(COSName.IDENTITY, state.getDictionaryObject(COSName.TR));
            COSDictionary spaces = resources.getCOSDictionary(COSName.COLORSPACE);
            assertTrue(spaces == null || !spaces.containsKey(COSName.getPDFName("DefaultGray")));
        }
    }

    @Test
    void aPaletteJpxImageUsesThePdfsIndexedColours() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "q 80 0 0 40 0 0 cm /Im0 Do Q");
            COSStream image = doc.getDocument().createCOSStream();
            try (OutputStream os = image.createRawOutputStream()) {
                os.write(paletteJp2(Base64.getDecoder().decode(INDICES_128)));
            }
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.FILTER, COSName.JPX_DECODE);
            image.setInt(COSName.WIDTH, 8);
            image.setInt(COSName.HEIGHT, 4);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            byte[] lookup = new byte[256 * 3];
            lookup[128 * 3] = 0x20;
            lookup[128 * 3 + 1] = 0x50;
            lookup[128 * 3 + 2] = (byte) 0xE0;
            COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICERGB);
            indexed.add(COSInteger.get(255));
            indexed.add(new COSString(lookup));
            image.setItem(COSName.COLORSPACE, indexed);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), image);
            page.getResources().getCOSObject().setItem(COSName.XOBJECT, xobjects);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDResources resources = doc.getPage(0).getResources();
            PDImageXObject image = (PDImageXObject) resources.getXObject(COSName.getPDFName("Im0"));
            assertEquals(COSName.DEVICEGRAY, image.getCOSObject().getDictionaryObject(COSName.COLORSPACE));
            int grey = image.getImage().getRaster().getSample(3, 2, 0);
            assertTrue(Math.abs(grey - 81) <= 3, "grey " + grey);
        }
    }

    @Test
    void theSameInputGivesTheSameBytesEveryTime() throws Exception {
        Path in = dir.resolve("shadings.pdf");
        try (PDDocument doc = new PDDocument()) {
            StringBuilder operators = new StringBuilder();
            COSDictionary shadings = new COSDictionary();
            for (int i = 0; i < 12; i++) {
                operators.append("/Sh").append(i).append(" sh ");
                COSDictionary function = new COSDictionary();
                function.setInt(COSName.FUNCTION_TYPE, 2);
                function.setItem(COSName.DOMAIN, floats(0, 1));
                function.setItem(COSName.C0, floats(1, 0, 0));
                function.setItem(COSName.C1, floats(0, 0, i % 2));
                function.setInt(COSName.N, 1);
                COSDictionary shading = new COSDictionary();
                shading.setInt(COSName.SHADING_TYPE, 2);
                shading.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
                shading.setItem(COSName.COORDS, floats(0, 0, 100, i));
                shading.setItem(COSName.FUNCTION, function);
                shadings.setItem(COSName.getPDFName("Sh" + i), shading);
            }
            PDPage page = page(doc, operators.toString());
            page.getResources().getCOSObject().setItem(COSName.SHADING, shadings);
            doc.save(in.toFile());
        }

        byte[] first = null;
        for (int run = 0; run < 6; run++) {
            Path out = dir.resolve("run" + run + ".pdf");
            PdfOptimizer.optimize(in, out, GRAY);
            byte[] bytes = java.nio.file.Files.readAllBytes(out);
            if (first == null) {
                first = bytes;
            } else {
                assertTrue(java.util.Arrays.equals(first, bytes), "run " + run + " differs");
            }
        }
    }

    @Test
    void anImageThatAlreadyRendersGrayIsLeftAlone() throws Exception {
        Path out = optimize(doc -> {
            PDPage page = page(doc, "q 100 0 0 100 0 0 cm /Im0 Do Q");
            COSStream image = doc.getDocument().createCOSStream();
            try (OutputStream os = image.createOutputStream(COSName.FLATE_DECODE)) {
                byte[] indices = new byte[64 * 64];
                for (int i = 0; i < indices.length; i++) {
                    indices[i] = (byte) (i * 7 % 256);
                }
                os.write(indices);
            }
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setInt(COSName.WIDTH, 64);
            image.setInt(COSName.HEIGHT, 64);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            byte[] lookup = new byte[256];
            for (int i = 0; i < lookup.length; i++) {
                lookup[i] = (byte) (255 - i);
            }
            COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICEGRAY);
            indexed.add(COSInteger.get(255));
            indexed.add(new COSString(lookup));
            image.setItem(COSName.COLORSPACE, indexed);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), image);
            page.getResources().getCOSObject().setItem(COSName.XOBJECT, xobjects);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDImageXObject image = (PDImageXObject) doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
            assertEquals("Indexed", image.getColorSpace().getName());
        }
    }

    @Test
    void aHeavilyCompressedColourJpegDoesNotGrowWhenTurnedGray() throws Exception {
        byte[] source = colourJpeg(0.3f);
        Path out = optimize(doc -> {
            PDPage page = page(doc, "q 400 0 0 300 0 0 cm /Im0 Do Q");
            COSStream image = doc.getDocument().createCOSStream();
            try (OutputStream os = image.createRawOutputStream()) {
                os.write(source);
            }
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.FILTER, COSName.DCT_DECODE);
            image.setInt(COSName.WIDTH, 400);
            image.setInt(COSName.HEIGHT, 300);
            image.setInt(COSName.BITS_PER_COMPONENT, 8);
            image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), image);
            page.getResources().getCOSObject().setItem(COSName.XOBJECT, xobjects);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDImageXObject image = (PDImageXObject) doc.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
            assertEquals("DeviceGray", image.getColorSpace().getName());
            long gray = image.getCOSObject().getLength();
            assertTrue(gray <= source.length, gray + " bytes of gray JPEG from " + source.length + " bytes of colour");
        }
    }

    @Test
    void aPdfaDocumentGainsAPdfaOutputIntentForItsNewGray() throws Exception {
        Path out = optimize(doc -> {
            page(doc, "1 0 0 rg 0 0 10 10 re f");
            COSStream metadata = doc.getDocument().createCOSStream();
            try (OutputStream os = metadata.createOutputStream()) {
                os.write(("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                        + "<rdf:Description xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' pdfaid:part='2'"
                        + " pdfaid:conformance='B'/></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8));
            }
            metadata.setItem(COSName.TYPE, COSName.METADATA);
            metadata.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
            COSStream profile = doc.getDocument().createCOSStream();
            try (OutputStream os = profile.createOutputStream()) {
                os.write(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
            }
            profile.setInt(COSName.N, 3);
            COSDictionary intent = new COSDictionary();
            intent.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
            intent.setItem(COSName.S, COSName.getPDFName("GTS_PDFA"));
            intent.setItem(COSName.getPDFName("DestOutputProfile"), profile);
            COSArray intents = new COSArray();
            intents.add(intent);
            COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
            catalog.setItem(COSName.METADATA, metadata);
            catalog.setItem(COSName.OUTPUT_INTENTS, intents);
        });

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSArray intents = doc.getDocumentCatalog().getCOSObject().getCOSArray(COSName.OUTPUT_INTENTS);
            assertEquals(2, intents.size());
            COSDictionary original = (COSDictionary) intents.getObject(0);
            COSDictionary added = (COSDictionary) intents.getObject(1);
            assertEquals(COSName.getPDFName("GTS_PDFA1"), added.getCOSName(COSName.S));
            assertTrue(original.getDictionaryObject(COSName.getPDFName("DestOutputProfile"))
                    == added.getDictionaryObject(COSName.getPDFName("DestOutputProfile")));
        }
    }

    private interface Builder {
        void build(PDDocument doc) throws Exception;
    }

    private Path optimize(Builder builder) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            builder.build(doc);
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, GRAY);
        return out;
    }

    private static PDPage page(PDDocument doc, String operators) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        page.setResources(new PDResources());
        COSStream content = doc.getDocument().createCOSStream();
        try (OutputStream os = content.createOutputStream()) {
            os.write(operators.getBytes(StandardCharsets.ISO_8859_1));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, content);
        return page;
    }

    private static byte[] colourJpeg(float quality) throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(400, 300,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int r = (x * 255 / 400 + random.nextInt(48)) & 0xff;
                int g = (y * 255 / 300 + random.nextInt(48)) & 0xff;
                int b = ((x + y) * 255 / 700 + random.nextInt(48)) & 0xff;
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream out = javax.imageio.ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static String content(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean has(String content, String regex) {
        return java.util.regex.Pattern.compile(regex).matcher(content).find();
    }

    private static final String INDICES_128 = "/0//UQApAAAAAAAIAAAABAAAAAAAAAAAAAAACAAAAAQAAAAAAAAAAAABBwEB/1IADAAAAAEAAAQEAAH/XAAEQED/ZAAl"
            + "AAFDcmVhdGVkIGJ5IE9wZW5KUEVHIHZlcnNpb24gMi41LjT/kAAKAAAAAAAPAAH/k4D/2Q==";

    private static byte[] paletteJp2(byte[] codestream) throws Exception {
        ByteArrayOutputStream pclr = new ByteArrayOutputStream();
        pclr.write(new byte[] {1, 0, 3, 7, 7, 7});
        pclr.write(new byte[256 * 3]);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(box("ihdr", new byte[] {0, 0, 0, 4, 0, 0, 0, 8, 0, 1, 7, 7, 0, 0}));
        header.write(box("colr", new byte[] {1, 0, 0, 0, 0, 0, 16}));
        header.write(box("pclr", pclr.toByteArray()));
        header.write(box("cmap", new byte[] {0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 1, 2}));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(box("jP  ", new byte[] {0x0D, 0x0A, (byte) 0x87, 0x0A}));
        file.write(box("ftyp", new byte[] {'j', 'p', '2', ' ', 0, 0, 0, 0, 'j', 'p', '2', ' '}));
        file.write(box("jp2h", header.toByteArray()));
        file.write(box("jp2c", codestream));
        return file.toByteArray();
    }

    private static byte[] box(String type, byte[] body) {
        byte[] out = new byte[body.length + 8];
        int length = out.length;
        out[0] = (byte) (length >>> 24);
        out[1] = (byte) (length >>> 16);
        out[2] = (byte) (length >>> 8);
        out[3] = (byte) length;
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) type.charAt(i);
        }
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    private static COSArray floats(float... values) {
        COSArray array = new COSArray();
        array.setFloatArray(values);
        return array;
    }
}
