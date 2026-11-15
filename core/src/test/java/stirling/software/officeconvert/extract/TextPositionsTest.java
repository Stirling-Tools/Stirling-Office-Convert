package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.cos.COSArray;
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
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.OfficeConvert;

class TextPositionsTest {

    @Test
    void overflowingFontAdvanceIsSkippedWithoutAddingTextToTheDocument() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            COSDictionary dictionary = new COSDictionary();
            dictionary.setItem(COSName.TYPE, COSName.FONT);
            dictionary.setItem(COSName.SUBTYPE, COSName.TYPE1);
            dictionary.setName(COSName.BASE_FONT, "Helvetica");
            dictionary.setInt(COSName.FIRST_CHAR, 65);
            dictionary.setInt(COSName.LAST_CHAR, 65);
            COSArray widths = new COSArray();
            widths.add(new COSFloat(3e38f));
            dictionary.setItem(COSName.WIDTHS, widths);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(dictionary));
            page.setResources(resources);
            PDStream stream = new PDStream(document);
            try (OutputStream output = stream.createOutputStream()) {
                output.write(("BT /F1 1 Tf 10000 0 0 1 72 720 Tm (A) Tj "
                        + "1 0 0 1 72 700 Tm (B) Tj ET").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(stream);
            List<String> glyphs = new ArrayList<>();
            new PageReader(document).read(0, 0, false,
                    read -> read.glyphs().forEach(glyph -> glyphs.add(glyph.text)));
            assertEquals(List.of("B"), glyphs);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            OfficeConvert.convert(document, output, OfficeConvert.Format.DOCX, OfficeConvert.Settings.defaults());
            List<String> text = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    if (entry.getName().equals("word/document.xml")) {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setNamespaceAware(true);
                        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                        var nodes = xml.getElementsByTagNameNS(
                                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
                        for (int i = 0; i < nodes.getLength(); i++) text.add(nodes.item(i).getTextContent());
                    }
                }
            }
            assertEquals(List.of("B"), text);
        }
    }

    @Test
    void overflowingLinearProductIsRejectedEvenWhenTheEndpointWouldBeFinite() throws Exception {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDGraphicsState state = new PDGraphicsState(PDRectangle.LETTER);
        state.getTextState().setFontSize(1e-30f);
        state.setCurrentTransformationMatrix(new Matrix(1e20f, 0, 0, 1e20f, 0, 0));
        Matrix text = new Matrix(1e30f, 0, 0, 1e30f, 0, 0);
        Matrix trm = new Matrix(1e-30f, 0, 0, 1e-30f, 0, 0).multiply(text)
                .multiply(state.getCurrentTransformationMatrix());
        TextPositions positions = new TextPositions(new UnmappedGlyphs(TextPositions.GLYPHS));
        positions.page(new PDPage());
        assertThrows(IllegalArgumentException.class,
                () -> positions.of(state, text, trm, font, 'A', font.getDisplacement('A')));
    }

    @Test
    void largeAndNonAffineMatricesKeepNativeResultsAndRejections() throws Exception {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        Matrix projective = new Matrix(2, 3, 4, 5, 6, 7);
        projective.setValue(0, 2, 0.5f);
        projective.setValue(2, 2, 2);
        compareNative(font, projective, new Matrix(1, 0, 0, 1, 20, 30), 1);
        compareNative(font, new Matrix(1e13f, 0, 0, 1e13f, 0, 0),
                new Matrix(1e-5f, 0, 0, 1e-5f, 0, 0), 1e-7f);
        Random random = new Random(2138305);
        for (int i = 0; i < 20_000; i++) {
            Matrix text = new Matrix(Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()),
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()),
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()));
            Matrix ctm = new Matrix(Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()),
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()),
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()));
            compareNative(font, text, ctm, 1);
        }
    }

    @Test
    void thresholdBoundariesAndDegenerateValuesKeepNativeResults() throws Exception {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        float limit = 1e12f;
        for (float value : new float[] {0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MIN_NORMAL,
                Math.nextDown(limit), limit, Math.nextUp(limit), -limit, 1e13f, Float.MAX_VALUE,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            Matrix matrix = new Matrix(value, value, value, value, value, value);
            compareNative(font, matrix, matrix, 1);
            compareNative(font, matrix, matrix, limit);
            compareNative(font, matrix, matrix, 1e13f);
        }
    }

    private static void compareNative(PDType1Font font, Matrix text, Matrix ctm, float size) throws Exception {
        PDGraphicsState state = new PDGraphicsState(PDRectangle.LETTER);
        state.getTextState().setFontSize(size);
        state.setCurrentTransformationMatrix(ctm);
        Vector displacement = font.getDisplacement('A');
        TextPositions positions = new TextPositions(new UnmappedGlyphs(TextPositions.GLYPHS));
        positions.page(new PDPage());
        Matrix expected;
        try {
            expected = Matrix.getTranslateInstance(displacement.getX() * size, displacement.getY() * size)
                    .multiply(text).multiply(ctm);
        } catch (IllegalArgumentException rejected) {
            assertThrows(IllegalArgumentException.class,
                    () -> positions.of(state, text, new Matrix(), font, 'A', displacement));
            return;
        }
        TextPosition actual = positions.of(state, text, new Matrix(), font, 'A', displacement);
        assertEquals(Float.floatToRawIntBits(expected.getTranslateX()), Float.floatToRawIntBits(actual.getEndX()));
        assertEquals(Float.floatToRawIntBits(expected.getTranslateY()), Float.floatToRawIntBits(actual.getEndY()));
    }

    @Test
    void glyphAdvanceKeepsExactCoordinatesWithRotationShearAndOffsetCropBoxes() throws Exception {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        Vector displacement = font.getDisplacement('A');
        TextPositions positions = new TextPositions(new UnmappedGlyphs(TextPositions.GLYPHS));
        Random random = new Random(3051701);
        for (int crop = 0; crop < 2; crop++) {
            PDRectangle box = new PDRectangle(crop * -7, crop * -11, 612, 792);
            for (int rotation = 0; rotation < 360; rotation += 90) {
                PDPage page = new PDPage(box);
                page.setRotation(rotation);
                positions.page(page);
                for (int i = 0; i < 100; i++) {
                    Matrix text = matrix(random);
                    Matrix ctm = matrix(random);
                    float size = random.nextFloat(6, 40);
                    float scaling = random.nextFloat(50, 200);
                    PDGraphicsState state = new PDGraphicsState(box);
                    state.setCurrentTransformationMatrix(ctm);
                    state.getTextState().setFontSize(size);
                    state.getTextState().setHorizontalScaling(scaling);
                    Matrix trm = new Matrix(size * scaling / 100f, 0, 0, size, 0, 0)
                            .multiply(text).multiply(ctm);
                    float tx = displacement.getX() * size * (scaling / 100f);
                    float ty = displacement.getY() * size;
                    Matrix expected = Matrix.getTranslateInstance(tx, ty).multiply(text).multiply(ctm);
                    TextPosition actual = positions.of(state, text, trm, font, 'A', displacement);
                    assertEquals(Float.floatToIntBits(expected.getTranslateX() - box.getLowerLeftX()),
                            Float.floatToIntBits(actual.getEndX()));
                    assertEquals(Float.floatToIntBits(expected.getTranslateY() - box.getLowerLeftY()),
                            Float.floatToIntBits(actual.getEndY()));
                }
            }
        }
    }

    private static Matrix matrix(Random random) {
        return new Matrix(random.nextFloat(-8, 8), random.nextFloat(-8, 8),
                random.nextFloat(-8, 8), random.nextFloat(-8, 8),
                random.nextFloat(-2000, 2000), random.nextFloat(-2000, 2000));
    }
}
