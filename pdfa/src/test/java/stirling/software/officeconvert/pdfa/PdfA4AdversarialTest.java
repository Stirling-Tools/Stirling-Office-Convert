package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PdfA4AdversarialTest {

    @TempDir
    Path dir;

    private Path convert(Path in, String name, PdfALevel level) throws Exception {
        Path out = dir.resolve(name + "-" + level.name() + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        return out;
    }

    private static String xmp(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void staleConformanceInExistingXmpIsNotCarriedIntoPartFour() throws Exception {
        Path in = dir.resolve("xmp.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Was 2b) Tj ET", FontSamples.trueType(d));
            String old = "<?xpacket begin='' id='W5M0MpCehiHzreSzNTczkc9d'?><x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                    + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                    + "<rdf:Description rdf:about='' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/'"
                    + " pdfaid:part='2' pdfaid:conformance='B' pdfaid:amd='2005'/>"
                    + "<rdf:Description rdf:about='' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/'>"
                    + "<pdfaid:rev>2011</pdfaid:rev></rdf:Description>"
                    + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                    + "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Old title</rdf:li></rdf:Alt></dc:title>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='w'?>";
            PDMetadata m = new PDMetadata(d, new ByteArrayInputStream(old.getBytes(StandardCharsets.UTF_8)));
            d.getDocumentCatalog().setMetadata(m);
            d.save(in.toFile());
        }
        Path out = convert(in, "xmp", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        String x = xmp(out);
        assertFalse(x.contains("conformance"), x);
        assertFalse(x.contains("2011"), x);
        assertTrue(x.contains("Old title"), x);
    }

    @Test
    void pieceInfoKeepsAnInfoDictionaryWithOnlyModDate() throws Exception {
        Path in = dir.resolve("piece.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Piece info) Tj ET", FontSamples.trueType(d));
            d.getDocumentInformation().setTitle("Keep in XMP");
            d.getDocumentInformation().setCustomMetadataValue("Department", "Legal");
            COSDictionary app = new COSDictionary();
            COSDictionary data = new COSDictionary();
            data.setDate(COSName.LAST_MODIFIED, java.util.Calendar.getInstance());
            data.setString(COSName.getPDFName("Private"), "x");
            app.setItem(COSName.getPDFName("Data"), data);
            app.setDate(COSName.LAST_MODIFIED, java.util.Calendar.getInstance());
            COSDictionary piece = new COSDictionary();
            piece.setItem(COSName.getPDFName("SomeApp"), app);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.PIECE_INFO, piece);
            d.save(in.toFile());
        }
        Path out = convert(in, "piece", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getDocumentCatalog().getCOSObject().containsKey(COSName.PIECE_INFO));
            COSDictionary info = (COSDictionary) d.getDocument().getTrailer().getDictionaryObject(COSName.INFO);
            assertNotNull(info);
            assertEquals(1, info.size(), info.toString());
            assertNotNull(info.getDictionaryObject(COSName.MOD_DATE));
        }
        assertTrue(xmp(out).contains("Keep in XMP"));
        VeraPdf.assertCompliant(convert(in, "piece", PdfALevel.A2B), PdfALevel.A2B);
    }

    @Test
    void partFourKeepsAPdfAFourAttachmentAndPartTwoDropsIt() throws Exception {
        Path plain = plain("plain");
        Path four = convert(plain, "plain", PdfALevel.A4);
        VeraPdf.assertCompliant(four, PdfALevel.A4);
        Path in = attach("four-attached", Map.of("four.pdf", Files.readAllBytes(four)), "application/pdf");
        Path out4 = convert(in, "four-attached", PdfALevel.A4);
        VeraPdf.assertCompliant(out4, PdfALevel.A4);
        assertEquals(1, attachments(out4));
        try (PDDocument d = Loader.loadPDF(out4.toFile())) {
            PDComplexFileSpecification fs = d.getDocumentCatalog().getNames().getEmbeddedFiles().getNames()
                    .get("four.pdf");
            assertNotNull(fs.getCOSObject().getDictionaryObject(COSName.getPDFName("AFRelationship")));
            assertNotNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF")));
        }
        Path out2 = convert(in, "four-attached", PdfALevel.A2B);
        VeraPdf.assertCompliant(out2, PdfALevel.A2B);
        assertEquals(0, attachments(out2));
    }

    @Test
    void partFourDropsAPdfAThreeAttachment() throws Exception {
        Path plain = plain("plain3");
        Path three = convert(plain, "plain3", PdfALevel.A3B);
        Path in = attach("three-attached", Map.of("three.pdf", Files.readAllBytes(three)), "application/pdf");
        Path out = convert(in, "three-attached", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        assertEquals(0, attachments(out));
    }

    @Test
    void partFourRepairsAnInvalidMimeTypeOnAKeptAttachment() throws Exception {
        Path plain = plain("plain1");
        Path one = convert(plain, "plain1", PdfALevel.A1B);
        Path in = attach("bad-mime", Map.of("one.pdf", Files.readAllBytes(one)), "PDF");
        Path out3 = convert(in, "bad-mime", PdfALevel.A3B);
        VeraPdf.assertCompliant(out3, PdfALevel.A3B);
        Path out = convert(in, "bad-mime", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
    }

    private Path plain(String name) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Attachment) Tj ET", FontSamples.trueType(d));
            d.save(in.toFile());
        }
        return in;
    }

    private Path attach(String name, Map<String, byte[]> files, String mime) throws Exception {
        Path in = dir.resolve(name + ".pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Host) Tj ET", FontSamples.trueType(d));
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            Map<String, PDComplexFileSpecification> specs = new java.util.TreeMap<>();
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                PDEmbeddedFile ef = new PDEmbeddedFile(d, new ByteArrayInputStream(e.getValue()));
                ef.getCOSObject().setItem(COSName.SUBTYPE, COSName.getPDFName(mime));
                PDComplexFileSpecification fs = new PDComplexFileSpecification();
                fs.setFile(e.getKey());
                fs.setFileUnicode(e.getKey());
                fs.setEmbeddedFile(ef);
                fs.setEmbeddedFileUnicode(ef);
                specs.put(e.getKey(), fs);
            }
            tree.setNames(specs);
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(d.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            d.getDocumentCatalog().setNames(names);
            d.save(in.toFile());
        }
        return in;
    }

    private static int attachments(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            var names = d.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null || names.getEmbeddedFiles().getNames() == null) {
                return 0;
            }
            return names.getEmbeddedFiles().getNames().size();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bogus"})
    void typeZeroFontWithoutSubtypeKeepsTwoByteGlyphs(String subtype) throws Exception {
        String words = "Жизнь Ωμέγα €";
        Path in = dir.resolve("type0-nosub.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font f = PDType0Font.load(d, Samples.liberation(), false);
            StringBuilder hex = new StringBuilder();
            for (byte b : f.encode(words)) {
                hex.append(String.format("%02X", b));
            }
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td <" + hex + "> Tj ET", f);
            setSubtype(f.getCOSObject(), subtype);
            d.save(in.toFile());
        }
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A2B, PdfALevel.A4}) {
            Path out = convert(in, "type0-nosub", level);
            VeraPdf.assertCompliant(out, level);
            assertTrue(text(out).contains(words), level + ": " + text(out));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bogus"})
    void simpleFontWithoutSubtypeWhoseProgramIsTrueTypeOpenTypeInFontFile3(String subtype) throws Exception {
        Path in = dir.resolve("otf-nosub.pdf");
        try (PDDocument d = new PDDocument()) {
            byte[] program;
            try (java.io.InputStream s = PDDocument.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                program = s.readAllBytes();
            }
            COSDictionary font = Samples.trueTypeUnembedded("LiberationSans", Samples.arialWidths());
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program);
            }
            file.setItem(COSName.SUBTYPE, COSName.getPDFName("OpenType"));
            ((COSDictionary) font.getDictionaryObject(COSName.FONT_DESC)).setItem(COSName.FONT_FILE3, file);
            PDFont f = PDFontFactory.createFont(font);
            setSubtype(font, subtype);
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (OpenType program) Tj ET", f);
            d.save(in.toFile());
        }
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A2B, PdfALevel.A4}) {
            Path out = convert(in, "otf-nosub", level);
            VeraPdf.assertCompliant(out, level);
            assertTrue(text(out).contains("OpenType program"), level + ": " + text(out));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bogus"})
    void typeThreeFontWithoutSubtypeIsRepaired(String subtype) throws Exception {
        Path in = dir.resolve("t3-nosub.pdf");
        try (PDDocument d = new PDDocument()) {
            COSDictionary t3 = new COSDictionary();
            t3.setItem(COSName.TYPE, COSName.FONT);
            t3.setItem(COSName.FONT_BBOX, new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 1000, 1000)
                    .getCOSArray());
            COSArray matrix = new COSArray();
            for (float v : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
                matrix.add(new org.apache.pdfbox.cos.COSFloat(v));
            }
            t3.setItem(COSName.FONT_MATRIX, matrix);
            COSStream glyph = d.getDocument().createCOSStream();
            try (OutputStream o = glyph.createOutputStream()) {
                o.write("1000 0 0 0 800 800 d1 0 0 800 800 re f".getBytes(StandardCharsets.US_ASCII));
            }
            COSDictionary procs = new COSDictionary();
            procs.setItem(COSName.getPDFName("box"), glyph);
            t3.setItem(COSName.CHAR_PROCS, procs);
            COSDictionary enc = new COSDictionary();
            enc.setItem(COSName.TYPE, COSName.ENCODING);
            COSArray diff = new COSArray();
            diff.add(org.apache.pdfbox.cos.COSInteger.get(65));
            diff.add(COSName.getPDFName("box"));
            enc.setItem(COSName.DIFFERENCES, diff);
            t3.setItem(COSName.ENCODING, enc);
            t3.setInt(COSName.FIRST_CHAR, 65);
            t3.setInt(COSName.LAST_CHAR, 65);
            COSArray widths = new COSArray();
            widths.add(org.apache.pdfbox.cos.COSInteger.get(1000));
            t3.setItem(COSName.WIDTHS, widths);
            t3.setItem(COSName.RESOURCES, new COSDictionary());
            setSubtype(t3, subtype);
            PDPage p = Samples.page(d);
            COSDictionary fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("A"), t3);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.FONT, fonts);
            p.getCOSObject().setItem(COSName.RESOURCES, res);
            Samples.raw(p, d, "BT /A 20 Tf 50 780 Td (AAA) Tj ET");
            d.save(in.toFile());
        }
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A4}) {
            Path out = convert(in, "t3-nosub", level);
            VeraPdf.assertCompliant(out, level);
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSDictionary f = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                        .getCOSDictionary(COSName.FONT).getDictionaryObject(COSName.getPDFName("A"));
                assertEquals(COSName.TYPE3, f.getCOSName(COSName.SUBTYPE), level.toString());
            }
        }
    }

    @Test
    void pageWithoutResourcesIsCompliantAtPartFour() throws Exception {
        Path in = Hostile.write(dir, "no-resources", Hostile::plainPage);
        VeraPdf.assertCompliant(convert(in, "no-resources", PdfALevel.A2B), PdfALevel.A2B);
        VeraPdf.assertCompliant(convert(in, "no-resources", PdfALevel.A4), PdfALevel.A4);
    }

    @Test
    void supplementaryPrivateUseIsStrippedFromInlineAndNamedMarkedContent() throws Exception {
        Path in = dir.resolve("pua-supp.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = FontSamples.page(d, "/Span <</ActualText <FEFFDB80DC000041DBFFDFFD0042>>> BDC "
                    + "BT /A 14 Tf 50 780 Td (AB) Tj ET EMC /Span /P0 BDC BT /A 14 Tf 50 740 Td (C) Tj ET EMC "
                    + "/Span <</MCID 3 /ActualText <FEFFF8FF>>> DP", FontSamples.trueType(d));
            COSDictionary named = new COSDictionary();
            named.setItem(COSName.getPDFName("ActualText"),
                    new COSString(new byte[] {(byte) 0xFE, (byte) 0xFF, (byte) 0xDB, (byte) 0xC0, (byte) 0xDC, 0, 0,
                            0x43}));
            COSDictionary props = new COSDictionary();
            props.setItem(COSName.getPDFName("P0"), named);
            p.getResources().getCOSObject().setItem(COSName.PROPERTIES, props);
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, PdfALevel.A4).isEmpty());
        Path out = convert(in, "pua-supp", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        String t = text(out);
        assertTrue(t.contains("AB") && t.contains("C"), t);
        Path out2 = convert(in, "pua-supp", PdfALevel.A2B);
        VeraPdf.assertCompliant(out2, PdfALevel.A2B);
    }

    private static void setSubtype(COSDictionary font, String subtype) {
        if (subtype.isEmpty()) {
            font.removeItem(COSName.SUBTYPE);
        } else {
            font.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype));
        }
    }

    @Test
    void forbiddenToUnicodeValuesAreDroppedButTheRestOfTheMapSurvives() throws Exception {
        Path in = dir.resolve("tu-zero.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont f = FontSamples.trueType(d);
            COSStream tu = d.getDocument().createCOSStream();
            String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo << /Registry "
                    + "(Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Adobe-Identity-UCS def /CMapType 2 def "
                    + "1 begincodespacerange <00> <FF> endcodespacerange 5 beginbfchar <41> <0000> <42> <0042> "
                    + "<43> <FEFF> <44> <0044FFFE> <45> <0045> endbfchar endcmap CMapName currentdict /CMap "
                    + "defineresource pop end end";
            try (OutputStream o = tu.createOutputStream()) {
                o.write(cmap.getBytes(StandardCharsets.US_ASCII));
            }
            f.getCOSObject().setItem(COSName.TO_UNICODE, tu);
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (ABCDE) Tj ET", f);
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, PdfALevel.A4).isEmpty());
        Path out = convert(in, "tu-zero", PdfALevel.A4);
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        String t = text(out);
        assertTrue(t.contains("B") && t.contains("E"), t);
        VeraPdf.assertCompliant(convert(in, "tu-zero", PdfALevel.A2B), PdfALevel.A2B);
    }
}
