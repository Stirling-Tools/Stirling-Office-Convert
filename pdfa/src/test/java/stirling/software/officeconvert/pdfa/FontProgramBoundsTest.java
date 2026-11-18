package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontProgramBoundsTest {

    @TempDir
    Path dir;

    private static byte[] index(List<byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (items.isEmpty()) {
            out.writeBytes(new byte[2]);
            return out.toByteArray();
        }
        ByteBuffer head = ByteBuffer.allocate(3 + 4 * (items.size() + 1));
        head.putShort((short) items.size()).put((byte) 4);
        int offset = 1;
        head.putInt(offset);
        for (byte[] item : items) {
            offset += item.length;
            head.putInt(offset);
        }
        out.writeBytes(head.array());
        items.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] int32(int v) {
        return ByteBuffer.allocate(5).put((byte) 29).putInt(v).array();
    }

    private static byte[] concat(byte[]... parts) {
        return FontPrograms.concat(parts);
    }

    private static byte[] small(int v) {
        return new byte[] {(byte) (v + 139)};
    }

    static byte[] cff(int fanOut) {
        List<byte[]> subrs = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            subrs.add(concat(FontPrograms.repeat(concat(small(i + 1 - 107), new byte[] {10}), fanOut),
                    new byte[] {11}));
        }
        subrs.add(new byte[] {11});
        byte[] notdef = concat(small(0), small(0), new byte[] {21, 14});
        byte[] glyph = concat(small(-107), new byte[] {10}, small(100), small(0), new byte[] {21}, small(0),
                small(100), new byte[] {5, 14});
        byte[] header = {1, 0, 4, 4};
        byte[] names = index(List.of("Runaway".getBytes(StandardCharsets.ISO_8859_1)));
        byte[] strings = index(List.of("A".getBytes(StandardCharsets.ISO_8859_1)));
        byte[] gsubrs = index(List.of());
        byte[] charset = {0, 1, (byte) 135};
        byte[] charStrings = index(List.of(notdef, glyph));
        byte[] privateDict = concat(int32(6), new byte[] {19});
        int topLength = index(List.of(top(0, 0, 0, 0))).length;
        int charsetAt = header.length + names.length + topLength + strings.length + gsubrs.length;
        int charStringsAt = charsetAt + charset.length;
        int privateAt = charStringsAt + charStrings.length;
        byte[] top = index(List.of(top(charsetAt, charStringsAt, privateDict.length, privateAt)));
        return concat(header, names, top, strings, gsubrs, charset, charStrings, privateDict, index(subrs));
    }

    private static byte[] top(int charset, int charStrings, int privateSize, int privateAt) {
        return concat(int32(charset), new byte[] {15}, int32(charStrings), new byte[] {17},
                int32(privateSize), int32(privateAt), new byte[] {18});
    }

    private static byte[] pdf(byte[] program) {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Runaway/FirstChar 65/LastChar 65"
                + "/Widths[500]/Encoding/WinAnsiEncoding/FontDescriptor<</Type/FontDescriptor/FontName/Runaway"
                + "/Flags 32/FontBBox[0 0 500 800]/ItalicAngle 0/Ascent 800/Descent -200/CapHeight 700/StemV 80"
                + "/FontFile3 5 0 R>>>>>>", "BT /F1 24 Tf 72 700 Td (A) Tj ET");
        pdf.add(RawPdf.stream("/Subtype/Type1C", new String(program, StandardCharsets.ISO_8859_1)));
        return pdf.bytes();
    }

    private static PDFont font(PDDocument doc) throws Exception {
        return doc.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
    }

    @Test
    void aCffProgramWhoseSubroutinesExplodeIsDropped() throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf(cff(100)))) {
            FontProgramBounds.run(doc);
            assertNull(font(doc).getFontDescriptor().getFontFile3());
        }
    }

    @Test
    void anOrdinaryCffProgramIsKept() throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf(cff(2)))) {
            FontProgramBounds.run(doc);
            assertNotNull(font(doc).getFontDescriptor().getFontFile3());
        }
    }

    @Test
    void aType1ProgramWhoseSubroutinesExplodeIsDropped() throws Exception {
        FontPrograms.Type1 p = FontPrograms.fanOutType1("FanOut", 40);
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/FanOut/FirstChar 65/LastChar 65"
                + "/Widths[500]/FontDescriptor<</Type/FontDescriptor/FontName/FanOut/Flags 32"
                + "/FontBBox[0 -10 900 760]/ItalicAngle 0/Ascent 760/Descent -10/CapHeight 700/StemV 80"
                + "/FontFile 5 0 R>>>>>>", "BT /F1 12 Tf 50 700 Td (A) Tj ET");
        pdf.add(RawPdf.stream("/Length1 " + p.length1() + "/Length2 " + p.length2() + "/Length3 " + p.length3(),
                new String(p.data(), StandardCharsets.ISO_8859_1)));
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            FontProgramBounds.run(doc);
            assertNull(font(doc).getFontDescriptor().getFontFile());
        }
    }

    @Test
    void aPdfWithAnExplodingCffFontStillBecomesPdfA() throws Exception {
        Path in = dir.resolve("in.pdf");
        Path out = dir.resolve("out.pdf");
        Files.write(in, pdf(cff(100)));

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> PdfToPdfA.convert(in, out,
                PdfToPdfA.Options.defaults().level(PdfALevel.A2B).timeout(Duration.ofSeconds(25))));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertTrue(font(doc).isEmbedded());
            assertFalse(new PDFTextStripper().getText(doc).isBlank());
        }
    }
}
