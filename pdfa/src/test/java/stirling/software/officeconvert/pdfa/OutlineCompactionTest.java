package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutlineCompactionTest {

    @TempDir
    Path dir;

    static COSDictionary font(PDDocument d, boolean cff) throws Exception {
        COSStream file = d.getDocument().createCOSStream();
        if (cff) {
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cff("TestCff"));
            }
            file.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1C"));
        } else {
            FontPrograms.Type1 t = FontPrograms.type1("TestType", 40);
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(t.data());
            }
            file.setInt(COSName.LENGTH1, t.length1());
            file.setInt(COSName.LENGTH2, t.length2());
            file.setInt(COSName.LENGTH3, t.length3());
        }
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TYPE1);
        String name = cff ? "TestCff" : "TestType";
        f.setName(COSName.BASE_FONT, name);
        f.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
        f.setInt(COSName.FIRST_CHAR, 32);
        f.setInt(COSName.LAST_CHAR, 255);
        COSArray w = new COSArray();
        for (int c = 32; c <= 255; c++) {
            String g = WinAnsiEncoding.INSTANCE.getName(c);
            w.add(COSInteger.get(FontPrograms.NAMES.contains(g) ? FontPrograms.width("Aacute".equals(g) ? "A" : g) : 0));
        }
        f.setItem(COSName.WIDTHS, w);
        PDFontDescriptor fd = new PDFontDescriptor(new COSDictionary());
        fd.setFontName(name);
        fd.setFlags(32);
        fd.setFontBoundingBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, -10, 900, 770));
        fd.setItalicAngle(0);
        fd.setAscent(760);
        fd.setDescent(-10);
        fd.setCapHeight(700);
        fd.setStemV(80);
        fd.getCOSObject().setItem(cff ? COSName.FONT_FILE3 : COSName.FONT_FILE, file);
        f.setItem(COSName.FONT_DESC, fd.getCOSObject());
        return f;
    }

    @ParameterizedTest
    @CsvSource({"false, A1B", "false, A2B", "true, A1B", "true, A2U"})
    void type1AndCffProgramsKeepOnlyTheGlyphsInUse(boolean cff, PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("T"), PDFontFactory.createFont(font(d, cff)));
            p.setResources(res);
            Samples.raw(p, d, "BT /T 40 Tf 50 700 Td (AB) Tj <C1> Tj ET");
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        long before = program(in);
        long after = program(out);
        assertTrue(after < before / 2, before + " to " + after);
        assertTrue(ColourRenderingTest.dark(ColourRenderingTest.render(in)) > 100);
        assertEquals(0, ColourRenderingTest.differing(ColourRenderingTest.render(in), ColourRenderingTest.render(out)),
                0.0005);
        assertTrue(Files.size(out) > 0);
    }

    static long program(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            COSDictionary f = d.getPage(0).getResources().getFont(COSName.getPDFName("T")).getCOSObject();
            COSDictionary fd = (COSDictionary) f.getDictionaryObject(COSName.FONT_DESC);
            COSStream s = (COSStream) (fd.containsKey(COSName.FONT_FILE) ? fd.getDictionaryObject(COSName.FONT_FILE)
                    : fd.getDictionaryObject(COSName.FONT_FILE3));
            try (var in = s.createInputStream()) {
                return in.readAllBytes().length;
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"A1B", "A2B"})
    void cidKeyedCffProgramsKeepOnlyTheGlyphsInUse(PdfALevel level) throws Exception {
        Path in = dir.resolve("cid.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cidCff("TestCid"));
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
            cid.setInt(COSName.DW, 500);
            COSDictionary t0 = new COSDictionary();
            t0.setItem(COSName.TYPE, COSName.FONT);
            t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            t0.setName(COSName.BASE_FONT, "TestCid");
            t0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            COSArray kids = new COSArray();
            kids.add(cid);
            t0.setItem(COSName.DESCENDANT_FONTS, kids);
            PDPage p = Samples.page(d);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("T"), PDFontFactory.createFont(t0));
            p.setResources(res);
            Samples.raw(p, d, "BT /T 40 Tf 50 700 Td <000200030005> Tj ET");
            d.save(in.toFile());
        }
        Path out = dir.resolve("cid-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary t0 = d.getPage(0).getResources().getFont(COSName.getPDFName("T")).getCOSObject();
            COSDictionary cid = (COSDictionary) ((COSArray) t0.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            COSDictionary fd = (COSDictionary) cid.getDictionaryObject(COSName.FONT_DESC);
            try (var s = ((COSStream) fd.getDictionaryObject(COSName.FONT_FILE3)).createInputStream()) {
                int after = s.readAllBytes().length;
                assertTrue(after < FontPrograms.cidCff("TestCid").length / 2, "size " + after);
            }
        }
        assertTrue(ColourRenderingTest.dark(ColourRenderingTest.render(in)) > 100);
        assertEquals(0, ColourRenderingTest.differing(ColourRenderingTest.render(in), ColourRenderingTest.render(out)),
                0.0005);
    }
}
