package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stirling.software.officeconvert.pdfa.FontPrograms.concat;
import static stirling.software.officeconvert.pdfa.FontPrograms.index;
import static stirling.software.officeconvert.pdfa.FontPrograms.int5;
import static stirling.software.officeconvert.pdfa.FontPrograms.num2;
import static stirling.software.officeconvert.pdfa.FontPrograms.real;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExactFontWidthTest {

    private static final int GLYPHS = 8;
    private static final int SECOND_FD_START = 3;

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void cidKeyedCffGlyphsInASecondFdAreMeasuredWithThatFdsFontMatrix(PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = PDFontFactory.createFont(cidFont(d, cidCff(0.0005, 0, 0, 1000, 1000), 1000, 1000));
            FontSamples.page(d, "BT /A 20 Tf 50 700 Td <00010002000400050006> Tj ET", font);
            d.save(in.toFile());
        }
        assertTrue(VeraPdf.failures(in, level).containsKey(widthRule(level)));
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void cidKeyedCffWithPerFdNominalWidthsThatAlreadyMatchIsLeftAlone(PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = PDFontFactory.createFont(cidFont(d, cidCff(0, 0, 200, 600, 300.75), 600, 500.75));
            assertEquals(FontCheck.Verdict.OK, FontCheck.check(font, Set.of(1, 2, 4, 5), level));
            FontSamples.page(d, "BT /A 20 Tf 50 700 Td <0001000200040005> Tj ET", font);
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, level).containsKey(widthRule(level)));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void type1WidthsWithinOneOfTheExactAdvanceButNotOfTheTruncatedOneAreFixed(PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            COSDictionary font = type1Font(d, 9, 1.5f);
            FontSamples.page(d, "BT /A 20 Tf 50 760 Td (ABC abc) Tj ET", PDFontFactory.createFont(font));
            d.save(in.toFile());
        }
        assertTrue(VeraPdf.failures(in, level).containsKey(widthRule(level)));
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
    }

    @Test
    void type1ExactWidthsComeFromTheDivOperator() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDFont font = PDFontFactory.createFont(type1Font(d, 9, 0));
            assertEquals(FontPrograms.width("A") + 0.9, FontCheck.programWidth(font, 'A'), 1e-3);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void type1CWithANonDefaultFontMatrixAndMatchingWidthsIsLeftAlone(PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            COSDictionary font = OutlineCompactionTest.font(d, true);
            COSDictionary descriptor = (COSDictionary) font.getDictionaryObject(COSName.FONT_DESC);
            COSStream file = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE3);
            byte[] matrix = concat(real(0.0005), num2(0), num2(0), real(0.0005), num2(0), num2(0),
                    new byte[] {12, 7});
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cff("TestCff", matrix, false, 0, 0.5));
            }
            COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
            for (int i = 0; i < widths.size(); i++) {
                float w = ((COSNumber) widths.getObject(i)).floatValue();
                if (w > 250) {
                    widths.set(i, new COSFloat((w + 0.5f) / 2));
                } else if (w > 0) {
                    widths.set(i, new COSFloat(w / 2));
                }
            }
            PDFont pdFont = PDFontFactory.createFont(font);
            assertEquals(FontCheck.Verdict.OK, FontCheck.check(pdFont, Set.of((int) 'A', (int) 'b', 32), level));
            FontSamples.page(d, "BT /A 20 Tf 50 760 Td (Ab b) Tj ET", pdFont);
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, level).containsKey(widthRule(level)));
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getPage(0).getResources().getFont(COSName.getPDFName("A")).getWidth('A') < 400);
        }
    }

    private static String widthRule(PdfALevel level) {
        return level == PdfALevel.A1B ? "6.3.6-1" : "6.2.11.5-1";
    }

    private static COSDictionary type1Font(PDDocument d, int tenths, float widthOffset) throws Exception {
        COSDictionary font = OutlineCompactionTest.font(d, false);
        COSDictionary descriptor = (COSDictionary) font.getDictionaryObject(COSName.FONT_DESC);
        COSStream file = (COSStream) descriptor.getDictionaryObject(COSName.FONT_FILE);
        FontPrograms.Type1 t = FontPrograms.type1("TestType", 40, tenths);
        try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(t.data());
        }
        file.setInt(COSName.LENGTH1, t.length1());
        file.setInt(COSName.LENGTH2, t.length2());
        file.setInt(COSName.LENGTH3, t.length3());
        COSArray widths = (COSArray) font.getDictionaryObject(COSName.WIDTHS);
        for (int i = 0; i < widths.size(); i++) {
            float w = ((COSNumber) widths.getObject(i)).floatValue();
            if (w > 250) {
                widths.set(i, new COSFloat(w + widthOffset));
            }
        }
        return font;
    }

    private static COSDictionary cidFont(PDDocument d, byte[] cff, double firstFdWidth, double secondFdWidth)
            throws Exception {
        COSStream file = d.getDocument().createCOSStream();
        try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(cff);
        }
        file.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType0C"));
        PDFontDescriptor fd = new PDFontDescriptor(new COSDictionary());
        fd.setFontName("TestCid");
        fd.setFlags(4);
        fd.setFontBoundingBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, -10, 900, 770));
        fd.setItalicAngle(0);
        fd.setAscent(760);
        fd.setDescent(-10);
        fd.setCapHeight(700);
        fd.setStemV(80);
        fd.getCOSObject().setItem(COSName.FONT_FILE3, file);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
        cid.setName(COSName.BASE_FONT, "TestCid");
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, "Identity");
        info.setInt(COSName.SUPPLEMENT, 0);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, fd.getCOSObject());
        cid.setInt(COSName.DW, 1000);
        COSArray list = new COSArray();
        for (int g = 1; g < GLYPHS; g++) {
            double w = g < SECOND_FD_START ? firstFdWidth : secondFdWidth;
            list.add(w == Math.rint(w) ? COSInteger.get((long) w) : new COSFloat((float) w));
        }
        COSArray w = new COSArray();
        w.add(COSInteger.ONE);
        w.add(list);
        cid.setItem(COSName.W, w);
        COSDictionary t0 = new COSDictionary();
        t0.setItem(COSName.TYPE, COSName.FONT);
        t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
        t0.setName(COSName.BASE_FONT, "TestCid");
        t0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        COSArray kids = new COSArray();
        kids.add(cid);
        t0.setItem(COSName.DESCENDANT_FONTS, kids);
        return t0;
    }

    private static byte[] cidCff(double secondFdScale, int firstNominal, int secondNominal, double firstOperand,
            double secondOperand) {
        byte[][] glyphs = new byte[GLYPHS][];
        glyphs[0] = concat(num2(0), new byte[] {14});
        for (int g = 1; g < GLYPHS; g++) {
            double op = g < SECOND_FD_START ? firstOperand : secondOperand;
            byte[] advance = op == Math.rint(op) ? num2((int) op) : FontPrograms.fixed(op);
            glyphs[g] = concat(advance, num2(50), num2(0), new byte[] {21}, num2(300), num2(0), new byte[] {5},
                    num2(0), num2(600), new byte[] {5}, num2(-300), num2(0), new byte[] {5}, new byte[] {14});
        }
        byte[] name = index(new byte[][] {"TestCid".getBytes(StandardCharsets.US_ASCII)});
        byte[] strings = index(new byte[][] {"Adobe".getBytes(StandardCharsets.US_ASCII),
                "Identity".getBytes(StandardCharsets.US_ASCII)});
        byte[] gsubrs = index(new byte[0][]);
        ByteArrayOutputStream charset = new ByteArrayOutputStream();
        charset.write(0);
        for (int g = 1; g < GLYPHS; g++) {
            charset.write(g >> 8);
            charset.write(g);
        }
        byte[] fdSelect = {3, 0, 2, 0, 0, 0, 0, SECOND_FD_START, 1, 0, GLYPHS};
        byte[] charstrings = index(glyphs);
        byte[] priv0 = concat(num2(0), new byte[] {20}, num2(firstNominal), new byte[] {21});
        byte[] priv1 = concat(num2(0), new byte[] {20}, num2(secondNominal), new byte[] {21});
        byte[] matrix = secondFdScale == 0 ? new byte[0]
                : concat(real(secondFdScale), num2(0), num2(0), real(secondFdScale), num2(0), num2(0),
                        new byte[] {12, 7});
        byte[] probe = index(new byte[][] {cidTop(0, 0, 0, 0)});
        int charsetAt = 4 + name.length + probe.length + strings.length + gsubrs.length;
        int fdSelectAt = charsetAt + charset.size();
        int charstringsAt = fdSelectAt + fdSelect.length;
        int fdArrayAt = charstringsAt + charstrings.length;
        byte[] fdProbe = fdArray(matrix, 0, 0, 0, 0);
        int priv0At = fdArrayAt + fdProbe.length;
        int priv1At = priv0At + priv0.length;
        byte[] fdArray = fdArray(matrix, priv0.length, priv0At, priv1.length, priv1At);
        byte[] topIndex = index(new byte[][] {cidTop(charsetAt, fdSelectAt, charstringsAt, fdArrayAt)});
        return concat(new byte[] {1, 0, 4, 4}, name, topIndex, strings, gsubrs, charset.toByteArray(), fdSelect,
                charstrings, fdArray, priv0, priv1);
    }

    private static byte[] fdArray(byte[] secondMatrix, int size0, int at0, int size1, int at1) {
        return index(new byte[][] {concat(int5(size0), int5(at0), new byte[] {18}),
                concat(secondMatrix, int5(size1), int5(at1), new byte[] {18})});
    }

    private static byte[] cidTop(int charset, int fdSelect, int charstrings, int fdArray) {
        return concat(num2(391), num2(392), num2(0), new byte[] {12, 30}, num2(0), num2(-10), num2(900), num2(760),
                new byte[] {5}, int5(GLYPHS), new byte[] {12, 34}, int5(charset), new byte[] {15}, int5(fdSelect),
                new byte[] {12, 37}, int5(charstrings), new byte[] {17}, int5(fdArray), new byte[] {12, 36});
    }

}
