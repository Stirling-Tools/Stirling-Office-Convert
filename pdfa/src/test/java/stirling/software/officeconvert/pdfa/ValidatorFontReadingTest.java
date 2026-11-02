package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ValidatorFontReadingTest {

    private static final String LIBERATION = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void cffProgramsWithEmptyRealNumbersAreRewritten(PdfALevel level) throws Exception {
        byte[] cff = FontPrograms.cff("EmptyReal", FontPrograms.concat(new byte[] {30, (byte) 0xFF, 12, 3}), false);
        Path in = simple(cff, "Type1C");
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void openTypeProgramsMarkedAsCffKeepTheirCffTable(PdfALevel level) throws Exception {
        byte[] cff = FontPrograms.cff("Wrapped");
        Path in = simple(otto(cff, FontPrograms.NAMES.size() + 1), "Type1C");
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream file = (COSStream) font(d).getFontDescriptor().getCOSObject()
                    .getDictionaryObject(COSName.FONT_FILE3);
            assertEquals(1, StreamFixer.read(file)[0]);
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void namedCffProgramsOfCidFontsGetTheCidSubtype(PdfALevel level) throws Exception {
        Path in = dir.resolve("cid-type1c.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cff("NamedInCid"));
            }
            file.setItem(COSName.SUBTYPE, COSName.getPDFName("Type1C"));
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            cid.setName(COSName.BASE_FONT, "NamedInCid");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            COSArray w = new COSArray();
            for (int g = 1; g <= FontPrograms.NAMES.size(); g++) {
                w.add(COSInteger.get(g));
                COSArray one = new COSArray();
                one.add(COSInteger.get(FontPrograms.width(FontPrograms.NAMES.get(g - 1))));
                w.add(one);
            }
            cid.setItem(COSName.W, w);
            cid.setItem(COSName.FONT_DESC, descriptor("NamedInCid", COSName.FONT_FILE3, file));
            COSDictionary t0 = new COSDictionary();
            t0.setItem(COSName.TYPE, COSName.FONT);
            t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            t0.setName(COSName.BASE_FONT, "NamedInCid");
            t0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            COSArray kids = new COSArray();
            kids.add(cid);
            t0.setItem(COSName.DESCENDANT_FONTS, kids);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <000200030004> Tj ET", PDFontFactory.createFont(t0));
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font t0 = (PDType0Font) font(d);
            COSStream file = (COSStream) t0.getDescendantFont().getFontDescriptor().getCOSObject()
                    .getDictionaryObject(COSName.FONT_FILE3);
            assertEquals("CIDFontType0C", file.getCOSName(COSName.SUBTYPE).getName());
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void type1ProgramsWithPlainCharStringsBecomeCff(PdfALevel level) throws Exception {
        FontPrograms.Type1 t = FontPrograms.plainType1("PlainType");
        Path in = dir.resolve("plain-type1.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(t.data());
            }
            file.setInt(COSName.LENGTH1, t.length1());
            file.setInt(COSName.LENGTH2, t.length2());
            file.setInt(COSName.LENGTH3, t.length3());
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td (ABC) Tj ET",
                    PDFontFactory.createFont(simpleDict("PlainType", COSName.FONT_FILE, file)));
            d.save(in.toFile());
        }
        assertTrue(Type1Lenient.plainCharStrings(t.data(), t.length1(), t.length2()));
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(font(d).getFontDescriptor().getCOSObject().containsKey(COSName.FONT_FILE3));
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void compactedTrueTypeFontsKeepGlyphsTheirNamesFound(PdfALevel level) throws Exception {
        TrueTypeFont lib = Samples.liberation();
        Map<Integer, Integer> unicode = new TreeMap<>();
        CmapSubtable uni = lib.getCmap().getSubtable(3, 1);
        for (int cp = 0x20; cp < 0x100; cp++) {
            int gid = uni.getGlyphId(cp);
            if (gid > 0) {
                unicode.put(cp, gid);
            }
        }
        TreeMap<Integer, Integer> macRoman = new TreeMap<>();
        for (int c = 0x20; c < 0x7F; c++) {
            macRoman.put(c, unicode.get(c));
        }
        byte[] cmap = Cmaps.table(List.of(new Cmaps.Subtable(0, 3, new TreeMap<>(unicode)),
                new Cmaps.Subtable(1, 0, macRoman)));
        byte[] program = FontSamples.withCmap(lib, cmap);
        Path in = dir.resolve("no-windows-cmap.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setInt(COSName.LENGTH1, program.length);
            COSDictionary f = simpleDict("LiberationSans", COSName.FONT_FILE2, file);
            f.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            COSArray w = new COSArray();
            for (int c = 32; c <= 255; c++) {
                Integer gid = unicode.get(c);
                w.add(COSInteger.get(gid == null ? 0
                        : Math.round(lib.getHorizontalMetrics().getAdvanceWidth(gid) * 1000f / lib.getUnitsPerEm())));
            }
            f.setItem(COSName.WIDTHS, w);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td (ðþý edh) Tj ET", PDFontFactory.createFont(f));
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(font(d).getFontDescriptor().getFontFile2().getCOSObject().getLength() < program.length / 4);
        }
        assertSameLook(in, out);
        assertEquals(Converted.text(in), Converted.text(out));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void symbolicTrueTypeFontsWithoutAValidatorCmapAreRebuilt(PdfALevel level) throws Exception {
        TrueTypeFont lib = Samples.liberation();
        TreeMap<Integer, Integer> unicode = new TreeMap<>();
        CmapSubtable uni = lib.getCmap().getSubtable(3, 1);
        for (int cp = 0x20; cp < 0x7F; cp++) {
            unicode.put(cp, uni.getGlyphId(cp));
        }
        byte[] program = FontSamples.withCmap(lib, Cmaps.table(List.of(new Cmaps.Subtable(0, 0, unicode))));
        Path in = dir.resolve("unicode-platform-only.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setInt(COSName.LENGTH1, program.length);
            COSDictionary f = simpleDict("LiberationSans", COSName.FONT_FILE2, file);
            f.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
            f.removeItem(COSName.ENCODING);
            ((COSDictionary) f.getDictionaryObject(COSName.FONT_DESC)).setInt(COSName.FLAGS, 4);
            COSArray w = new COSArray();
            for (int c = 32; c <= 255; c++) {
                Integer gid = unicode.get(c);
                w.add(COSInteger.get(gid == null ? 0
                        : Math.round(lib.getHorizontalMetrics().getAdvanceWidth(gid) * 1000f / lib.getUnitsPerEm())));
            }
            f.setItem(COSName.WIDTHS, w);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td (Glyphs) Tj ET", PDFontFactory.createFont(f));
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void cmapsThatMapCidsWithBfcharAreRewrittenAsTheyRender(PdfALevel level) throws Exception {
        TrueTypeFont lib = Samples.liberation();
        CmapSubtable uni = lib.getCmap().getSubtable(3, 1);
        String text = "Checkliste";
        Path in = dir.resolve("bfchar-encoding.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font t0;
            try (InputStream f = PDDocument.class.getResourceAsStream(LIBERATION)) {
                t0 = PDType0Font.load(d, f, false);
            }
            StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def\n"
                    + "/CMapName /Bfchar def\n/CMapType 1 def\n1 begincodespacerange\n<0000> <FFFF>\n"
                    + "endcodespacerange\n");
            StringBuilder hex = new StringBuilder();
            List<Integer> chars = text.chars().distinct().boxed().toList();
            cmap.append(chars.size()).append(" beginbfchar\n");
            for (int c : chars) {
                cmap.append(String.format("<%04X> <%04X>%n", c, uni.getGlyphId(c)));
            }
            cmap.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            for (char c : text.toCharArray()) {
                hex.append(String.format("%04X", (int) c));
            }
            COSStream enc = d.getDocument().createCOSStream();
            enc.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
            enc.setName(COSName.getPDFName("CMapName"), "Bfchar");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            enc.setItem(COSName.CIDSYSTEMINFO, info);
            try (OutputStream o = enc.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(cmap.toString().getBytes(StandardCharsets.US_ASCII));
            }
            t0.getCOSObject().setItem(COSName.ENCODING, enc);
            t0.getCOSObject().removeItem(COSName.TO_UNICODE);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <" + hex + "> Tj ET", t0);
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font t0 = (PDType0Font) font(d);
            assertTrue(t0.getCMap().hasCIDMappings());
            assertEquals(uni.getGlyphId('C'), t0.codeToCID('C'));
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A4"})
    void codesOutsideTheCodespaceAreReadAsPdfBoxDrawsThem(PdfALevel level) throws Exception {
        TrueTypeFont lib = Samples.liberation();
        CmapSubtable uni = lib.getCmap().getSubtable(3, 1);
        Path in = dir.resolve("stray-codes.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font t0;
            try (InputStream f = PDDocument.class.getResourceAsStream(LIBERATION)) {
                t0 = PDType0Font.load(d, f, false);
            }
            String cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> def\n"
                    + "/CMapName /Stray def\n/CMapType 1 def\n2 begincodespacerange\n<00> <7f>\n<e08080> <efbfbf>\n"
                    + "endcodespacerange\n3 begincidchar\n<e0> " + uni.getGlyphId('C') + "\n<63> "
                    + uni.getGlyphId('c') + "\n<6d> " + uni.getGlyphId('m') + "\nendcidchar\n"
                    + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
            COSStream enc = d.getDocument().createCOSStream();
            enc.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
            enc.setName(COSName.getPDFName("CMapName"), "Stray");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            enc.setItem(COSName.CIDSYSTEMINFO, info);
            try (OutputStream o = enc.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(cmap.getBytes(StandardCharsets.US_ASCII));
            }
            t0.getCOSObject().setItem(COSName.ENCODING, enc);
            t0.getCOSObject().removeItem(COSName.TO_UNICODE);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <e0e0e0636d> Tj ET", t0);
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font t0 = (PDType0Font) font(d);
            assertEquals(uni.getGlyphId('C'), t0.codeToCID(0xe0));
            assertEquals(uni.getGlyphId('m'), t0.codeToCID(0x6d));
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void glyphsMovedOffCidZeroSurviveFontCompaction(PdfALevel level) throws Exception {
        TrueTypeFont lib = Samples.liberation();
        CmapSubtable uni = lib.getCmap().getSubtable(3, 1);
        Path in = dir.resolve("cid-zero.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font t0;
            try (InputStream f = PDDocument.class.getResourceAsStream(LIBERATION)) {
                t0 = PDType0Font.load(d, f, false);
            }
            COSDictionary cid = (COSDictionary) ((COSArray) t0.getCOSObject()
                    .getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            String text = "Test";
            ByteBuffer map = ByteBuffer.allocate(2 * text.length());
            COSArray w = new COSArray();
            w.add(COSInteger.get(0));
            COSArray widths = new COSArray();
            for (char c : "Tes".toCharArray()) {
                int gid = uni.getGlyphId(c);
                map.putShort((short) gid);
                widths.add(COSInteger.get(Math.round(lib.getHorizontalMetrics().getAdvanceWidth(gid) * 1000f
                        / lib.getUnitsPerEm())));
            }
            w.add(widths);
            COSStream cidToGid = d.getDocument().createCOSStream();
            try (OutputStream o = cidToGid.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(map.array(), 0, 6);
            }
            cid.setItem(COSName.CID_TO_GID_MAP, cidToGid);
            cid.setItem(COSName.W, w);
            t0.getCOSObject().removeItem(COSName.TO_UNICODE);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <0000000100020000> Tj ET", t0);
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void cidFontsDeclareNoNewerSupplementThanTheirPredefinedCmap(PdfALevel level) throws Exception {
        Path in = dir.resolve("supplement.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font t0;
            try (InputStream f = PDDocument.class.getResourceAsStream(LIBERATION)) {
                t0 = PDType0Font.load(d, f, false);
            }
            COSDictionary cid = (COSDictionary) ((COSArray) t0.getCOSObject()
                    .getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Japan1");
            info.setInt(COSName.SUPPLEMENT, 6);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            t0.getCOSObject().setItem(COSName.ENCODING, COSName.getPDFName("UniJIS-UTF16-H"));
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <0041> Tj ET", t0);
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font t0 = (PDType0Font) font(d);
            COSDictionary info = (COSDictionary) t0.getDescendantFont().getCOSObject()
                    .getDictionaryObject(COSName.CIDSYSTEMINFO);
            assertEquals(level.part() == 4 ? 6 : 5, info.getInt(COSName.SUPPLEMENT));
        }
        assertFalse(VeraPdf.failures(out, level).containsKey(level.part() == 4 ? "6.2.10.3.1-1" : "6.2.11.3.1-1"));
    }

    @Test
    void namespacesStayIndirectObjects() throws Exception {
        Path in = dir.resolve("namespace.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            Samples.raw(p, d, "");
            COSDictionary ns = new COSDictionary();
            ns.setItem(COSName.TYPE, COSName.getPDFName("Namespace"));
            ns.setString(COSName.getPDFName("NS"), "http://iso.org/pdf2/ssn");
            COSDictionary elem = new COSDictionary();
            elem.setItem(COSName.S, COSName.getPDFName("Document"));
            elem.setItem(COSName.getPDFName("NS"), ns);
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            root.setItem(COSName.K, elem);
            elem.setItem(COSName.P, root);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            d.save(in.toFile());
        }
        for (PdfALevel level : List.of(PdfALevel.A2B, PdfALevel.A4)) {
            Path out = convert(in, level);
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSDictionary root = (COSDictionary) d.getDocumentCatalog().getCOSObject()
                        .getDictionaryObject(COSName.STRUCT_TREE_ROOT);
                COSDictionary elem = (COSDictionary) root.getDictionaryObject(COSName.K);
                assertInstanceOf(COSObject.class, elem.getItem(COSName.getPDFName("NS")));
            }
            VeraPdf.assertCompliant(out, level);
        }
    }

    @Test
    void structureCyclesAreBroken() throws Exception {
        Path in = dir.resolve("cycle.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            Samples.raw(p, d, "");
            COSDictionary a = new COSDictionary();
            a.setItem(COSName.S, COSName.getPDFName("Sect"));
            COSDictionary b = new COSDictionary();
            b.setItem(COSName.S, COSName.P);
            COSArray kids = new COSArray();
            kids.add(b);
            a.setItem(COSName.K, kids);
            COSArray back = new COSArray();
            back.add(a);
            b.setItem(COSName.K, back);
            b.setItem(COSName.P, a);
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            root.setItem(COSName.K, a);
            a.setItem(COSName.P, root);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A3B);
        VeraPdf.assertCompliant(out, PdfALevel.A3B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary root = (COSDictionary) d.getDocumentCatalog().getCOSObject()
                    .getDictionaryObject(COSName.STRUCT_TREE_ROOT);
            COSDictionary sect = (COSDictionary) root.getDictionaryObject(COSName.K);
            COSDictionary para = (COSDictionary) sect.getDictionaryObject(COSName.K);
            assertFalse(para.containsKey(COSName.K));
        }
    }

    @Test
    void remoteFileSpecificationsNeedNoAssociationAtPart3() throws Exception {
        Path in = dir.resolve("gotor.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            Samples.raw(p, d, "");
            COSDictionary fs = new COSDictionary();
            fs.setItem(COSName.TYPE, COSName.FILESPEC);
            fs.setString(COSName.F, "other.pdf");
            fs.setString(COSName.UF, "other.pdf");
            COSDictionary action = new COSDictionary();
            action.setItem(COSName.S, COSName.getPDFName("GoToR"));
            action.setItem(COSName.F, fs);
            COSArray dest = new COSArray();
            dest.add(COSInteger.ZERO);
            dest.add(COSName.getPDFName("Fit"));
            action.setItem(COSName.D, dest);
            COSDictionary link = new COSDictionary();
            link.setItem(COSName.TYPE, COSName.ANNOT);
            link.setItem(COSName.SUBTYPE, COSName.LINK);
            link.setItem(COSName.RECT, new org.apache.pdfbox.pdmodel.common.PDRectangle(50, 50, 100, 40).getCOSArray());
            link.setInt(COSName.F, 4);
            link.setItem(COSName.A, action);
            COSArray annots = new COSArray();
            annots.add(link);
            p.getCOSObject().setItem(COSName.ANNOTS, annots);
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A3B);
        VeraPdf.assertCompliant(out, PdfALevel.A3B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary link = (COSDictionary) ((COSArray) d.getPage(0).getCOSObject()
                    .getDictionaryObject(COSName.ANNOTS)).getObject(0);
            COSDictionary fs = (COSDictionary) ((COSDictionary) link.getDictionaryObject(COSName.A))
                    .getDictionaryObject(COSName.F);
            assertEquals("other.pdf", fs.getString(COSName.F));
        }
    }

    @Test
    void fontOperatorsWithoutASizeNamingNoFontAreDropped() throws Exception {
        Path in = dir.resolve("tf-no-size.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont f;
            try (InputStream s = PDDocument.class.getResourceAsStream(LIBERATION)) {
                f = org.apache.pdfbox.pdmodel.font.PDTrueTypeFont.load(d, s, WinAnsiEncoding.INSTANCE);
            }
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td (Kept) Tj /Missing Tf (Gone) Tj ET", f);
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        assertTrue(Converted.text(out).contains("Kept"));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2U", "A3U"})
    void glyphsReadersExtractAsNothingKeepAnEmptyActualText(PdfALevel level) throws Exception {
        Path in = dir.resolve("silent.pdf");
        try (PDDocument d = new PDDocument()) {
            COSDictionary f = silentType3(d);
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td <01010001> Tj ET", PDFontFactory.createFont(f));
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.matches("(?s).*/Span <</ActualText \\(\\) ?>> BDC.*"), content);
        }
        assertSameLook(in, out);
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2A", "A3A", "A2U"})
    void shapedClustersWithAnActualTextKeepTheirText(PdfALevel level) throws Exception {
        Path in = dir.resolve("cluster.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = FontSamples.page(d, "/P <</MCID 0>> BDC BT /A 24 Tf 50 700 Td /Span <</ActualText (AB)>> BDC "
                    + "<0100> Tj EMC <01> Tj ET EMC", PDFontFactory.createFont(silentType3(d)));
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            COSDictionary para = new COSDictionary();
            para.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
            para.setItem(COSName.S, COSName.P);
            para.setItem(COSName.P, root);
            para.setItem(COSName.PG, p.getCOSObject());
            para.setInt(COSName.K, 0);
            root.setItem(COSName.K, para);
            COSDictionary tree = new COSDictionary();
            COSArray nums = new COSArray();
            nums.add(COSInteger.ZERO);
            COSArray kids = new COSArray();
            kids.add(para);
            nums.add(kids);
            tree.setItem(COSName.NUMS, nums);
            root.setItem(COSName.PARENT_TREE, tree);
            p.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            COSDictionary mark = new COSDictionary();
            mark.setBoolean(COSName.getPDFName("Marked"), true);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.MARK_INFO, mark);
            d.getDocumentCatalog().setLanguage("en");
            d.save(in.toFile());
        }
        Path out = convert(in, level);
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.matches("(?s).*/ActualText \\(AB\\).*"), content);
            int cluster = content.indexOf("(AB)");
            int end = content.indexOf("EMC", cluster);
            assertFalse(content.substring(cluster, end).contains("ActualText ()"), content);
        }
        assertSameLook(in, out);
    }

    private static COSDictionary silentType3(PDDocument d) throws Exception {
        COSDictionary procs = new COSDictionary();
        for (String g : List.of("blob", "A")) {
            COSStream proc = d.getDocument().createCOSStream();
            try (OutputStream o = proc.createOutputStream()) {
                o.write("500 0 0 0 400 700 d1 0 0 400 700 re f".getBytes(StandardCharsets.US_ASCII));
            }
            procs.setItem(g, proc);
        }
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TYPE3);
        f.setItem(COSName.FONT_BBOX, new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 500, 700)
                .getCOSArray());
        COSArray matrix = new COSArray();
        for (float v : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
            matrix.add(new org.apache.pdfbox.cos.COSFloat(v));
        }
        f.setItem(COSName.FONT_MATRIX, matrix);
        f.setItem(COSName.CHAR_PROCS, procs);
        COSDictionary enc = new COSDictionary();
        COSArray diff = new COSArray();
        diff.add(COSInteger.ZERO);
        diff.add(COSName.getPDFName("blob"));
        diff.add(COSName.getPDFName("A"));
        enc.setItem(COSName.DIFFERENCES, diff);
        f.setItem(COSName.ENCODING, enc);
        f.setInt(COSName.FIRST_CHAR, 0);
        f.setInt(COSName.LAST_CHAR, 1);
        COSArray w = new COSArray();
        w.add(COSInteger.get(500));
        w.add(COSInteger.get(500));
        f.setItem(COSName.WIDTHS, w);
        COSStream tu = d.getDocument().createCOSStream();
        try (OutputStream o = tu.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CMapName /Partial def\n"
                    + "/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n1 beginbfchar\n"
                    + "<01> <0041>\nendbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        f.setItem(COSName.TO_UNICODE, tu);
        return f;
    }

    private Path convert(Path in, PdfALevel level) throws Exception {
        Path out = dir.resolve(in.getFileName().toString().replace(".pdf", "-" + level + ".pdf"));
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        return out;
    }

    private Path simple(byte[] program, String subtype) throws Exception {
        Path in = dir.resolve("simple-" + subtype + "-" + program.length + ".pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype));
            FontSamples.page(d, "BT /A 24 Tf 50 700 Td (ABC) Tj ET",
                    PDFontFactory.createFont(simpleDict("TestCff", COSName.FONT_FILE3, file)));
            d.save(in.toFile());
        }
        return in;
    }

    private static COSDictionary simpleDict(String name, COSName key, COSStream file) {
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TYPE1);
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
        f.setItem(COSName.FONT_DESC, descriptor(name, key, file));
        return f;
    }

    private static COSDictionary descriptor(String name, COSName key, COSStream file) {
        PDFontDescriptor fd = new PDFontDescriptor(new COSDictionary());
        fd.setFontName(name);
        fd.setFlags(32);
        fd.setFontBoundingBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, -10, 900, 770));
        fd.setItalicAngle(0);
        fd.setAscent(760);
        fd.setDescent(-10);
        fd.setCapHeight(700);
        fd.setStemV(80);
        fd.getCOSObject().setItem(key, file);
        return fd.getCOSObject();
    }

    private static PDFont font(PDDocument d) throws Exception {
        COSName name = d.getPage(0).getResources().getFontNames().iterator().next();
        return d.getPage(0).getResources().getFont(name);
    }

    private static void assertSameLook(Path in, Path out) throws Exception {
        try (PDDocument a = Loader.loadPDF(in.toFile()); PDDocument b = Loader.loadPDF(out.toFile())) {
            BufferedImage x = new PDFRenderer(a).renderImage(0, 1f);
            BufferedImage y = new PDFRenderer(b).renderImage(0, 1f);
            long diff = 0;
            for (int j = 0; j < x.getHeight(); j++) {
                for (int i = 0; i < x.getWidth(); i++) {
                    int p = x.getRGB(i, j);
                    int q = y.getRGB(i, j);
                    diff += Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF))
                            + Math.abs((p & 0xFF) - (q & 0xFF));
                }
            }
            assertTrue(diff / (3.0 * x.getWidth() * x.getHeight()) < 0.05, "mean difference " + diff);
        }
    }

    private static byte[] otto(byte[] cff, int glyphs) {
        Map<String, byte[]> tables = new TreeMap<>();
        tables.put("CFF ", cff);
        tables.put("head", ByteBuffer.allocate(54).putInt(0x00010000).putInt(0x00010000).putInt(0).putInt(0x5F0F3CF5)
                .putShort((short) 0).putShort((short) 1000).putLong(0).putLong(0).putShort((short) 0)
                .putShort((short) -10).putShort((short) 900).putShort((short) 770).putShort((short) 0)
                .putShort((short) 8).putShort((short) 2).putShort((short) 0).putShort((short) 0).array());
        tables.put("hhea", ByteBuffer.allocate(36).putInt(0x00010000).putShort((short) 760).putShort((short) -10)
                .putShort((short) 0).putShort((short) 900).putShort((short) 0).putShort((short) 0)
                .putShort((short) 900).putShort((short) 1).putShort((short) 0).putShort((short) 0).putLong(0)
                .putShort((short) 0).putShort((short) glyphs).array());
        tables.put("maxp", ByteBuffer.allocate(6).putInt(0x00005000).putShort((short) glyphs).array());
        tables.put("hmtx", new byte[4 * glyphs]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer dir = ByteBuffer.allocate(12 + 16 * tables.size());
        dir.putInt(0x4F54544F).putShort((short) tables.size()).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0);
        int offset = dir.capacity();
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            dir.put(e.getKey().getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(offset)
                    .putInt(e.getValue().length);
            offset += (e.getValue().length + 3) & ~3;
        }
        out.writeBytes(dir.array());
        for (byte[] t : tables.values()) {
            out.writeBytes(t);
            out.writeBytes(new byte[((t.length + 3) & ~3) - t.length]);
        }
        return out.toByteArray();
    }
}
