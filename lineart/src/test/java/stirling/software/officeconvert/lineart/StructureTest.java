package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class StructureTest {

    @Test
    void anImageSharedByPagesAndAPatternIsConvertedOnce() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = LosslessFactory.createFromImage(doc, Pictures.drawing(40, 30, Color.BLACK, Color.WHITE, 1));
            COSStream pattern = doc.getDocument().createCOSStream();
            pattern.setItem(COSName.TYPE, COSName.PATTERN);
            pattern.setInt(COSName.PATTERN_TYPE, 1);
            pattern.setInt(COSName.PAINT_TYPE, 1);
            pattern.setInt(COSName.TILING_TYPE, 1);
            pattern.setItem(COSName.BBOX, new PDRectangle(0, 0, 40, 30).getCOSArray());
            pattern.setInt(COSName.X_STEP, 40);
            pattern.setInt(COSName.Y_STEP, 30);
            PDResources patternResources = new PDResources();
            patternResources.put(COSName.getPDFName("P0"), image);
            pattern.setItem(COSName.RESOURCES, patternResources.getCOSObject());
            write(pattern, "q 40 0 0 30 0 0 cm /P0 Do Q");
            PDImageXObject onlyInPattern = LosslessFactory.createFromImage(doc, Pictures.drawing(20, 20, Color.BLACK, Color.WHITE, 2));
            patternResources.put(COSName.getPDFName("P1"), onlyInPattern);
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(new PDRectangle(100, 100));
                doc.addPage(page);
                PDResources resources = new PDResources();
                resources.put(COSName.getPDFName("Im0"), image);
                resources.getCOSObject().setItem(COSName.PATTERN, dictionary(COSName.getPDFName("Pa0"), pattern));
                page.setResources(resources);
                write(page, "q 40 0 0 30 0 0 cm /Im0 Do Q /Pattern cs /Pa0 scn 0 0 100 100 re f");
            }
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());
            assertEquals(2, result.images(), result.toString());
            assertEquals(2, result.converted());
            assertEquals(1, image.getCOSObject().getInt(COSName.BITS_PER_COMPONENT));
            assertEquals(1, onlyInPattern.getCOSObject().getInt(COSName.BITS_PER_COMPONENT));
            new PDFRenderer(Pictures.reload(doc)).renderImage(2, 0.5f);
        }
    }

    @Test
    void deeplyNestedFormsDoNotOverflowTheStack() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = LosslessFactory.createFromImage(doc, Pictures.drawing(30, 30, Color.BLACK, Color.WHITE, 3));
            PDPage page = new PDPage(new PDRectangle(30, 30));
            doc.addPage(page);
            PDResources resources = new PDResources();
            page.setResources(resources);
            write(page, "/F Do");
            for (int i = 0; i <= 3_000; i++) {
                PDFormXObject form = new PDFormXObject(doc);
                form.setBBox(new PDRectangle(0, 0, 30, 30));
                resources.put(COSName.getPDFName("F"), form);
                resources = new PDResources();
                form.setResources(resources);
                write(form.getCOSObject(), i == 3_000 ? "q 30 0 0 30 0 0 cm /I Do Q" : "/F Do");
            }
            resources.put(COSName.getPDFName("I"), image);
            assertEquals(3_002, ImageWalk.walk(doc).contents().size());
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults());
            assertEquals(1, result.converted(), result.toString());
            assertTrue(result.warnings().isEmpty(), result.toString());
        }
    }

    @Test
    void imagesInsideType3GlyphsAreConvertedAndStencilGlyphsAreLeft() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject glyphImage = LosslessFactory.createFromImage(doc, Pictures.drawing(24, 24, Color.BLACK, Color.WHITE, 4));
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE3);
            font.setItem(COSName.FONT_BBOX, new PDRectangle(0, 0, 1, 1).getCOSArray());
            COSArray matrix = new COSArray();
            for (int v : new int[] {1, 0, 0, 1, 0, 0}) {
                matrix.add(COSInteger.get(v));
            }
            font.setItem(COSName.FONT_MATRIX, matrix);
            font.setInt(COSName.FIRST_CHAR, 65);
            font.setInt(COSName.LAST_CHAR, 66);
            COSArray widths = new COSArray();
            widths.add(COSInteger.ONE);
            widths.add(COSInteger.ONE);
            font.setItem(COSName.WIDTHS, widths);
            COSDictionary encoding = new COSDictionary();
            COSArray differences = new COSArray();
            differences.add(COSInteger.get(65));
            differences.add(COSName.A);
            differences.add(COSName.getPDFName("B"));
            encoding.setItem(COSName.DIFFERENCES, differences);
            font.setItem(COSName.ENCODING, encoding);
            PDResources glyphResources = new PDResources();
            glyphResources.put(COSName.getPDFName("G"), glyphImage);
            font.setItem(COSName.RESOURCES, glyphResources.getCOSObject());
            COSStream a = doc.getDocument().createCOSStream();
            write(a, "1 0 0 0 1 1 d1 q 1 0 0 1 0 0 cm /G Do Q");
            COSStream b = doc.getDocument().createCOSStream();
            write(b, "1 0 0 0 1 1 d1 q 1 0 0 1 0 0 cm BI /W 8 /H 2 /IM true /BPC 1 ID ð\u000f EI Q "
                    + "q 1 0 0 1 0 0 cm BI /W 4 /H 3 /CS /G /BPC 8 ID " + "\u0000ÿ\u0000ÿÿ\u0000ÿ\u0000"
                    + "\u0000ÿ\u0000ÿ EI Q");
            COSDictionary procs = new COSDictionary();
            procs.setItem(COSName.A, a);
            procs.setItem(COSName.getPDFName("B"), b);
            font.setItem(COSName.CHAR_PROCS, procs);
            PDPage page = new PDPage(new PDRectangle(100, 100));
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.FONT, dictionary(COSName.getPDFName("F0"), font));
            page.setResources(resources);
            write(page, "BT /F0 40 Tf 10 10 Td (AB) Tj ET");
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(3, result.images(), result.toString());
            assertEquals(2, result.converted(), result.toString());
            assertEquals(1, glyphImage.getCOSObject().getInt(COSName.BITS_PER_COMPONENT));
            String glyphB = read(b);
            assertTrue(glyphB.contains("BI /W 8 /H 2 /IM true /BPC 1 ID ð\u000f EI"), glyphB);
            assertTrue(glyphB.contains("/BPC 1 /CS /DeviceGray"), glyphB);
            new PDFRenderer(Pictures.reload(doc)).renderImage(0, 1);
        }
    }

    @Test
    void inlineImagesAreRewrittenAndEverythingAroundThemIsKept() throws IOException {
        BufferedImage picture = Pictures.drawing(30, 20, Color.BLACK, Color.WHITE, 5);
        StringBuilder samples = new StringBuilder();
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 30; x++) {
                int rgb = picture.getRGB(x, y);
                samples.append((char) ((rgb >>> 16) & 0xFF)).append((char) ((rgb >>> 8) & 0xFF)).append((char) (rgb & 0xFF));
            }
        }
        String before = "% BI ID EI in a comment\nq (a string with BI and ID EI \\) inside) Tj /BI /ID <4249> pop "
                + "30 0 0 20 10 10 cm\nBI /W 30 /H 20 /CS /RGB /BPC 8 ID ";
        String after = "\nEI Q\n1 0 0 RG 0 0 m 50 50 l S\n";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(50, 40));
            doc.addPage(page);
            page.setResources(new PDResources());
            write(page, before + samples + after);
            Bitmap expected = LineArt.binarize(picture, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
            assertEquals(1, result.converted(), result.toString());
            String content = read(page.getCOSObject().getCOSStream(COSName.CONTENTS));
            int cut = before.indexOf("BI /W");
            assertTrue(content.startsWith(before.substring(0, cut)), content);
            assertTrue(content.endsWith("EI Q\n1 0 0 RG 0 0 m 50 50 l S\n"), content);
            assertTrue(content.length() < (before + samples + after).length() / 3, content.length() + "");
            BufferedImage rendered = new PDFRenderer(Pictures.reload(doc)).renderImage(0, 1);
            assertEquals(50, rendered.getWidth());
            long agree = 0;
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 30; x++) {
                    int rgb = rendered.getRGB(10 + x, 10 + y);
                    if (((rgb & 0xFF) < 128) == expected.ink(x, y) || (rgb & 0xFF0000) == 0xFF0000) {
                        agree++;
                    }
                }
            }
            assertTrue(agree > 600 * 9 / 10, agree + " of 600");
        }
    }

    @Test
    void rewrittenInlineContentDropsTheOriginalPredictorAndDecodedLength() throws IOException {
        StringBuilder samples = new StringBuilder();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                samples.append((char) ((x + y) % 2 == 0 ? 0 : 255));
            }
        }
        String body = "q 8 0 0 8 0 0 cm\nBI /W 8 /H 8 /CS /G /BPC 8 ID " + samples + "\nEI Q\n";
        byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        for (boolean array : new boolean[] {false, true}) {
            try (PDDocument doc = new PDDocument()) {
                COSStream stream = doc.getDocument().createCOSStream();
                COSDictionary predictor = new COSDictionary();
                predictor.setInt(COSName.PREDICTOR, 12);
                predictor.setInt(COSName.COLUMNS, bytes.length);
                if (array) {
                    COSArray filters = new COSArray();
                    filters.add(COSName.FLATE_DECODE);
                    stream.setItem(COSName.FILTER, filters);
                    COSArray parameters = new COSArray();
                    parameters.add(predictor);
                    stream.setItem(COSName.DECODE_PARMS, parameters);
                } else {
                    stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
                    stream.setItem(COSName.DECODE_PARMS, predictor);
                }
                stream.setInt(COSName.getPDFName("DL"), bytes.length);
                try (OutputStream out = new DeflaterOutputStream(stream.createRawOutputStream())) {
                    out.write(2);
                    out.write(bytes);
                }
                assertEquals(body, read(stream));
                PDPage page = new PDPage(new PDRectangle(8, 8));
                doc.addPage(page);
                page.setResources(new PDResources());
                page.getCOSObject().setItem(COSName.CONTENTS, stream);
                LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.FIXED));
                assertEquals(1, result.converted(), result.toString());
                assertTrue(result.warnings().isEmpty(), result.toString());
                String rewritten = read(stream);
                assertTrue(rewritten.startsWith("q 8 0 0 8 0 0 cm\nBI"), rewritten);
                assertNull(stream.getItem(COSName.DECODE_PARMS));
                assertNull(stream.getItem(COSName.getPDFName("DL")));
                assertEquals(COSName.FLATE_DECODE, stream.getItem(COSName.FILTER));
                try (PDDocument converted = Pictures.reload(doc)) {
                    BufferedImage rendered = new PDFRenderer(converted).renderImage(0, 1);
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            assertEquals((x + y) % 2 == 0, Pictures.dark(rendered.getRGB(x, y)), x + "," + y);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aHugeImageConvertsInBands() throws IOException {
        int w = 20_000;
        int h = 4_000;
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            stream.setItem(COSName.TYPE, COSName.XOBJECT);
            stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
            stream.setInt(COSName.WIDTH, w);
            stream.setInt(COSName.HEIGHT, h);
            stream.setInt(COSName.BITS_PER_COMPONENT, 8);
            stream.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            byte[] row = new byte[w * 3];
            try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        byte v = (byte) ((x / 13 + y / 11) % 17 == 0 ? 20 : 230);
                        row[x * 3] = v;
                        row[x * 3 + 1] = v;
                        row[x * 3 + 2] = v;
                    }
                    os.write(row);
                }
            }
            PDPage page = new PDPage(new PDRectangle(w / 20f, h / 20f));
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.XOBJECT, dictionary(COSName.getPDFName("Im0"), stream));
            page.setResources(resources);
            LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(LineArt.Method.OTSU));
            assertEquals(1, result.converted(), result.toString());
            PDImageXObject image = new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(stream), null);
            BufferedImage corner = image.getImage(new java.awt.Rectangle(0, 0, 40, 40), 1);
            for (int y = 0; y < 40; y++) {
                for (int x = 0; x < 40; x++) {
                    assertEquals((x / 13 + y / 11) % 17 == 0, Pictures.dark(corner.getRGB(x, y)), x + "," + y);
                }
            }
        }
    }

    @Test
    void tinyImagesConvert() throws IOException {
        for (int[] size : new int[][] {{1, 1}, {1, 9}, {9, 1}, {2, 2}}) {
            try (PDDocument doc = Pictures.onePage(Pictures.drawing(size[0], size[1], Color.BLACK, Color.WHITE, 7))) {
                for (LineArt.Method method : LineArt.Method.values()) {
                    LineArt.Result result = LineArt.convert(doc, LineArt.Options.defaults().method(method));
                    assertTrue(result.warnings().isEmpty(), result.toString());
                }
            }
        }
    }

    private static COSDictionary dictionary(COSName key, org.apache.pdfbox.cos.COSBase value) {
        COSDictionary d = new COSDictionary();
        d.setItem(key, value);
        return d;
    }

    private static void write(PDPage page, String content) throws IOException {
        COSStream stream = new COSStream();
        write(stream, content);
        page.getCOSObject().setItem(COSName.CONTENTS, stream);
    }

    private static void write(COSStream stream, String content) throws IOException {
        try (OutputStream os = stream.createOutputStream()) {
            os.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static String read(COSStream stream) throws IOException {
        try (InputStream in = stream.createInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
