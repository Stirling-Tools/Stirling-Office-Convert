package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleRepairsTest {

    @TempDir
    Path dir;

    @Test
    void aFontWithoutTypeAndWithAStringBaseFontGetsBothAsNames() throws Exception {
        Path in = dir.resolve("names.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = FontSamples.trueType(d);
            font.getCOSObject().removeItem(COSName.TYPE);
            font.getCOSObject().setItem(COSName.BASE_FONT, new COSString("ABCDEF+\0Liberation\0Sans"));
            font.getFontDescriptor().getCOSObject().setItem(COSName.FONT_NAME, new COSString("ABCDEF+LiberationSans"));
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 72 700 Td (Names) Tj ET", font));
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary font = d.getPage(0).getResources().getFont(COSName.getPDFName("A")).getCOSObject();
            assertEquals(COSName.FONT, font.getCOSName(COSName.TYPE));
            assertInstanceOf(COSName.class, font.getDictionaryObject(COSName.BASE_FONT));
            assertTrue(font.getNameAsString(COSName.BASE_FONT).endsWith("LiberationSans"), font.toString());
        }
    }

    @Test
    void anEmbeddedCMapAndItsCidFontShareOneCharacterCollection() throws Exception {
        Path in = dir.resolve("collection.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font font = PDType0Font.load(d, Samples.liberation(), false);
            COSStream cmap = FontSamples.cmap(d, "Spaced", 0, 0, "", 36);
            COSDictionary info = (COSDictionary) cmap.getDictionaryObject(COSName.CIDSYSTEMINFO);
            info.setString(COSName.REGISTRY, " Adobe ");
            info.setString(COSName.ORDERING, " Identity ");
            font.getCOSObject().setItem(COSName.ENCODING, cmap);
            COSDictionary cid = (COSDictionary) ((COSArray) font.getCOSObject()
                    .getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            ((COSDictionary) cid.getDictionaryObject(COSName.CIDSYSTEMINFO)).setInt(COSName.SUPPLEMENT, 3);
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 72 700 Td <0041> Tj ET", font));
            d.save(in.toFile());
        }
        convert(in, PdfALevel.A2B);
    }

    @Test
    void aCMapStreamWithAHigherSupplementRaisesItsCidFontToMatch() throws Exception {
        Path in = dir.resolve("supplement.pdf");
        try (PDDocument d = new PDDocument()) {
            PDType0Font font = PDType0Font.load(d, Samples.liberation(), false);
            COSStream cmap = FontSamples.cmap(d, "Spaced", 0, 0, "", 36);
            ((COSDictionary) cmap.getDictionaryObject(COSName.CIDSYSTEMINFO)).setInt(COSName.SUPPLEMENT, 5);
            font.getCOSObject().setItem(COSName.ENCODING, cmap);
            COSDictionary cid = (COSDictionary) ((COSArray) font.getCOSObject()
                    .getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            ((COSDictionary) cid.getDictionaryObject(COSName.CIDSYSTEMINFO)).setInt(COSName.SUPPLEMENT, 0);
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 72 700 Td <0041> Tj ET", font));
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary font = d.getPage(0).getResources().getFont(COSName.getPDFName("A")).getCOSObject();
            COSDictionary cid = (COSDictionary) ((COSArray) font.getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            int cidSupplement = ((COSDictionary) cid.getDictionaryObject(COSName.CIDSYSTEMINFO)).getInt(COSName.SUPPLEMENT);
            if (font.getDictionaryObject(COSName.ENCODING) instanceof COSStream stream) {
                int cmapSupplement = ((COSDictionary) stream.getDictionaryObject(COSName.CIDSYSTEMINFO))
                        .getInt(COSName.SUPPLEMENT);
                assertTrue(cidSupplement >= cmapSupplement, cidSupplement + " < " + cmapSupplement);
            }
        }
    }

    @Test
    void markedContentNamingMissingPropertiesKeepsItsTag() throws Exception {
        Path in = dir.resolve("properties.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = FontSamples.trueType(d);
            d.addPage(FontSamples.page(d, "/OC /oc7 BDC BT /A 12 Tf 72 700 Td (Shown) Tj ET EMC "
                    + "/Note /gone DP", font));
            d.save(in.toFile());
        }
        Path out = convert(in, PdfALevel.A4);
        assertEquals("Shown", Converted.text(out).strip());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("/OC BMC"), content);
            assertFalse(content.contains("oc7") || content.contains("gone"), content);
        }
    }

    @Test
    void pageBoxesMadeOfIndirectNumbersBecomeDirect() throws Exception {
        RawPdf raw = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 72 700 Td (Box) Tj ET");
        int zero = raw.add("0");
        int width = raw.add("595");
        int height = raw.add("842");
        String box = "[" + zero + " 0 R " + zero + " 0 R " + width + " 0 R " + height + " 0 R]";
        raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox" + box + "/CropBox" + box + "/Resources<<"
                + RawPdf.helvetica() + ">>/Contents 4 0 R>>");
        Path out = convert(Hostile.write(dir, "boxes", raw), PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSArray media = (COSArray) d.getPage(0).getCOSObject().getItem(COSName.MEDIA_BOX);
            assertInstanceOf(COSNumber.class, media.get(2));
            assertEquals(595f, d.getPage(0).getMediaBox().getWidth(), 0.01);
        }
    }

    @Test
    void aCheckboxWhoseStatesAreNotStreamsIsDrawnAgain() throws Exception {
        RawPdf raw = RawPdf.page("", "");
        raw.set(1, "<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[5 0 R]>>>>");
        raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Annots[5 0 R]/Contents 4 0 R>>");
        raw.add("<</Type/Annot/Subtype/Widget/FT/Btn/T(Box)/F 4/Rect[50 700 66 716]/P 3 0 R/V/Yes/AS/Yes"
                + "/AP<</N<</Yes null/Off/null>>>>>>");
        Path out = convert(Hostile.write(dir, "checkbox", raw), PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary widget = d.getPage(0).getAnnotations().get(0).getCOSObject();
            COSDictionary normal = (COSDictionary) ((COSDictionary) widget.getDictionaryObject(COSName.AP))
                    .getDictionaryObject(COSName.N);
            assertInstanceOf(COSStream.class, normal.getDictionaryObject(COSName.getPDFName("Yes")));
            assertInstanceOf(COSStream.class, normal.getDictionaryObject(COSName.getPDFName("Off")));
        }
    }

    @Test
    void aCffOpenTypeProgramStoredAsTrueTypeIsRecognised() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSStream program = d.getDocument().createCOSStream();
            try (OutputStream o = program.createOutputStream()) {
                o.write("OTTO\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
            }
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.FONT_FILE2, program);
            assertTrue(TrueTypeSanity.cffOutlines(new PDFontDescriptor(fd)));
            PDFont font = FontSamples.trueType(d);
            assertFalse(TrueTypeSanity.cffOutlines(font.getFontDescriptor()));
        }
    }

    private Path convert(Path in, PdfALevel level) throws Exception {
        Path out = dir.resolve(in.getFileName().toString().replace(".pdf", "-" + level + ".pdf"));
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        return out;
    }
}
