package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnProgramGlyphsTest {

    @TempDir
    Path dir;

    @Test
    void aType1ProgramInACidFontIsDrawnByCharstringIndex() throws Exception {
        FontPrograms.Type1 t1 = FontPrograms.type1("TestType", 3);
        Path out = convert("type1", t1.data(), t1.length1(), t1.length2());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("A"));
            assertEquals(0, f.getNormalizedPath(1).getBounds2D().getWidth(), 0.01);
            assertTrue(f.getNormalizedPath(2).getBounds2D().getWidth() > 10);
        }
    }

    @Test
    void aBareCffProgramStoredAsType1IsDrawnFromItsCharstrings() throws Exception {
        Path out = convert("cff", FontPrograms.cff("TestCff"), 0, 0);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("A"));
            assertTrue(f.getNormalizedPath(2).getBounds2D().getWidth() > 10);
        }
    }

    @Test
    void aCompositeGlyphThatContainsItselfIsCopiedEmpty() throws Exception {
        byte[] bytes;
        try (var in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            bytes = in.readAllBytes();
        }
        TrueTypeFont source = new TTFParser().parse(new RandomAccessReadBuffer(bytes.clone()));
        int gid = source.getUnicodeCmapLookup().getGlyphId('A');
        long glyf = source.getTableMap().get("glyf").getOffset();
        long at = glyf + source.getIndexToLocation().getOffsets()[gid];
        ByteBuffer b = ByteBuffer.wrap(bytes);
        b.putShort((int) at, (short) -1);
        b.putShort((int) at + 10, (short) 0x0002);
        b.putShort((int) at + 12, (short) gid);
        b.put((int) at + 14, (byte) 0);
        b.put((int) at + 15, (byte) 0);
        TrueTypeFont broken = new TTFParser().parse(new RandomAccessReadBuffer(bytes));
        TrueTypeWriter w = new TrueTypeWriter(broken);
        int slot = w.addCopy(gid, 500);
        TrueTypeFont rebuilt = new TTFParser().parse(new RandomAccessReadBuffer(w.build("X", new TreeMap<>(), 0)));
        long[] loca = rebuilt.getIndexToLocation().getOffsets();
        assertEquals(loca[slot], loca[slot + 1]);
        assertFalse(rebuilt.getGlyph().getGlyph(slot) != null
                && rebuilt.getGlyph().getGlyph(slot).getNumberOfContours() != 0);
    }

    private Path convert(String name, byte[] program, int length1, int length2) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            if (length1 > 0) {
                file.setInt(COSName.LENGTH1, length1);
                file.setInt(COSName.LENGTH2, length2);
                file.setInt(COSName.LENGTH3, 0);
            }
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "TestType");
            fd.setInt(COSName.FLAGS, 32);
            fd.setItem(COSName.FONT_BBOX, new COSArray(List.of(COSInteger.ZERO, COSInteger.get(-10),
                    COSInteger.get(900), COSInteger.get(760))));
            fd.setInt(COSName.ASCENT, 760);
            fd.setInt(COSName.DESCENT, -10);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.ITALIC_ANGLE, 0);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE, file);
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            cid.setName(COSName.BASE_FONT, "TestType");
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            cid.setItem(COSName.FONT_DESC, fd);
            cid.setInt(COSName.DW, 500);
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE0);
            font.setName(COSName.BASE_FONT, "TestType");
            font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            font.setItem(COSName.DESCENDANT_FONTS, new COSArray(List.of(cid)));
            PDFont f = PDFontFactory.createFont(font);
            FontSamples.page(d, "BT /A 24 Tf 72 700 Td <00010002> Tj ET", f);
            d.save(in.toFile());
        }
        Path out = dir.resolve(name + "-2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        return out;
    }
}
