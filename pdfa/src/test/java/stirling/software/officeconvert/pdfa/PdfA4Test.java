package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfA4Test {

    @TempDir
    Path dir;

    @Test
    void writesPdf20WithRevisionAndNoInfoDictionary() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Archive me) Tj ET", FontSamples.trueType(d));
            d.getDocumentInformation().setTitle("Quarterly report");
            d.getDocumentInformation().setAuthor("Finance");
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("%PDF-2.0", new String(bytes, 0, 8, StandardCharsets.ISO_8859_1));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNull(d.getDocument().getTrailer().getDictionaryObject(COSName.INFO));
            String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>4</pdfaid:part>"));
            assertTrue(xmp.contains("<pdfaid:rev>2020</pdfaid:rev>"));
            assertFalse(xmp.contains("pdfaid:conformance"));
            assertTrue(xmp.contains("Quarterly report"));
            assertTrue(xmp.contains("Finance"));
        }
    }

    @Test
    void privateUseCharactersLeaveActualText() throws Exception {
        Path in = dir.resolve("pua.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "/Span <</ActualText <FEFFE001>>> BDC BT /A 14 Tf 50 780 Td (Icon) Tj ET EMC "
                    + "/Span <</ActualText <FEFF0041E0020042>>> BDC BT /A 14 Tf 50 740 Td (AB) Tj ET EMC",
                    FontSamples.trueType(d));
            d.save(in.toFile());
        }
        assertFalse(VeraPdf.failures(in, PdfALevel.A4).isEmpty());
        Path out = dir.resolve("pua-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("E001"));
            assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(d).contains("AB"));
        }
    }

    @Test
    void keepsProjectionAnnotationsThatOnlyPartFourAllows() throws Exception {
        Path in = dir.resolve("projection.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Measured) Tj ET", FontSamples.trueType(d));
            COSDictionary annot = new COSDictionary();
            annot.setItem(COSName.TYPE, COSName.ANNOT);
            annot.setItem(COSName.SUBTYPE, COSName.getPDFName("Projection"));
            annot.setItem(COSName.RECT, new PDRectangle(50, 700, 0, 0).getCOSArray());
            annot.setInt(COSName.F, 4);
            COSArray annots = new COSArray();
            annots.add(annot);
            page.getCOSObject().setItem(COSName.ANNOTS, annots);
            d.save(in.toFile());
        }
        Path out = dir.resolve("projection-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A4));
        VeraPdf.assertCompliant(out, PdfALevel.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals("Projection", d.getPage(0).getAnnotations().get(0).getSubtype());
        }
        Path two = dir.resolve("projection-2b.pdf");
        PdfToPdfA.convert(in, two, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        try (PDDocument d = Loader.loadPDF(two.toFile())) {
            assertTrue(d.getPage(0).getAnnotations().isEmpty());
        }
    }
}
