package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentRulesTest {

    @TempDir
    Path dir;

    @Test
    void encryptionIsRemovedAndAUserPasswordIsHonoured() throws Exception {
        Converted.convert(dir, "s06_encrypted_owner", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s06_encrypted_owner", PdfALevel.A2B).toFile())) {
            assertFalse(d.isEncrypted());
        }
        Path in = Samples.write(dir, "s06_encrypted_user");
        Path out = dir.resolve("user.pdf");
        assertThrows(InvalidPasswordException.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults()));
        assertFalse(Files.exists(out));
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().password("secret"));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        assertTrue(Converted.text(out).contains("Encrypted document"));
    }

    @Test
    void lzwStreamsAreRecompressed() throws Exception {
        Converted.convert(dir, "s07_lzw", PdfALevel.A1B);
        Path out = Converted.out(dir, "s07_lzw", PdfALevel.A1B);
        assertFalse(new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1).contains("LZW"));
        assertTrue(Converted.text(out).contains("LZW compressed content stream"));
    }

    @Test
    void anSrgbOutputIntentAndADefaultCmykSpaceAreAdded() throws Exception {
        Converted.convert(dir, "s08_cmyk_lab_separation", PdfALevel.A1B);
        Path out = Converted.out(dir, "s08_cmyk_lab_separation", PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(1, d.getDocumentCatalog().getOutputIntents().size());
            PDOutputIntent oi = d.getDocumentCatalog().getOutputIntents().get(0);
            assertEquals("GTS_PDFA1", oi.getCOSObject().getNameAsString(COSName.S));
            COSStream profile = (COSStream) oi.getCOSObject().getDictionaryObject(COSName.getPDFName("DestOutputProfile"));
            assertEquals(3, profile.getInt(COSName.N));
            byte[] icc = profile.createInputStream().readAllBytes();
            assertEquals(2, icc[8]);
            COSDictionary cs = d.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.COLORSPACE);
            COSArray cmyk = (COSArray) cs.getDictionaryObject(COSName.getPDFName("DefaultCMYK"));
            assertEquals(COSName.ICCBASED, cmyk.getObject(0));
        }
    }

    @Test
    void embeddedFilesFollowEachPart() throws Exception {
        Converted.convert(dir, "s11_embedded_files", PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s11_embedded_files", PdfALevel.A1B).toFile())) {
            assertNull(d.getDocumentCatalog().getNames().getEmbeddedFiles());
            assertTrue(d.getPage(0).getAnnotations().isEmpty());
        }
        Converted.convert(dir, "s11_embedded_files", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s11_embedded_files", PdfALevel.A2B).toFile())) {
            assertTrue(d.getDocumentCatalog().getNames().getEmbeddedFiles().getNames().isEmpty());
        }
        Converted.convert(dir, "s11_embedded_files", PdfALevel.A3B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s11_embedded_files", PdfALevel.A3B).toFile())) {
            var files = d.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertEquals(1, files.size());
            COSDictionary fs = files.get("data.csv").getCOSObject();
            assertEquals(COSName.getPDFName("Unspecified"), fs.getDictionaryObject(COSName.getPDFName("AFRelationship")));
            COSArray af = (COSArray) d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
            assertEquals(2, af.size());
            assertEquals(1, d.getPage(0).getAnnotations().size());
        }
    }

    @Test
    void hiddenLayersAreDroppedForPartOneAndConfiguredForPartTwo() throws Exception {
        Converted.convert(dir, "s12_optional_content", PdfALevel.A1B);
        Path one = Converted.out(dir, "s12_optional_content", PdfALevel.A1B);
        String text = Converted.text(one);
        assertTrue(text.contains("Visible layer text"));
        assertFalse(text.contains("HIDDEN LAYER TEXT"));
        try (PDDocument d = Loader.loadPDF(one.toFile())) {
            assertNull(d.getDocumentCatalog().getOCProperties());
        }
        Converted.convert(dir, "s12_optional_content", PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s12_optional_content", PdfALevel.A2B).toFile())) {
            COSDictionary config = d.getDocumentCatalog().getOCProperties().getCOSObject().getCOSDictionary(COSName.D);
            assertNotNull(config.getDictionaryObject(COSName.NAME));
            assertNull(config.getDictionaryObject(COSName.getPDFName("AS")));
        }
    }

    @Test
    void metadataMatchesTheInfoDictionary() throws Exception {
        Converted.convert(dir, "s17_all_in_one", PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(Converted.out(dir, "s17_all_in_one", PdfALevel.A1B).toFile())) {
            String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("<pdfaid:part>1</pdfaid:part>"));
            assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"));
            assertTrue(xmp.contains("All in one été"));
            assertTrue(xmp.contains("<xmp:CreateDate>2026-01-02T03:04:05"));
            assertEquals("All in one été", d.getDocumentInformation().getTitle());
            assertEquals("False", d.getDocumentInformation().getTrapped());
            assertEquals("Stirling", d.getDocumentInformation().getCustomMetadataValue("Company"));
        }
    }

    @Test
    void forbiddenImageAndStateKeysGo() throws Exception {
        Converted.convert(dir, "s13_misc_forbidden", PdfALevel.A2B);
        Path out = Converted.out(dir, "s13_misc_forbidden", PdfALevel.A2B);
        String raw = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("/Alternates"));
        assertFalse(raw.contains("/TR "));
        assertFalse(raw.contains("/Interpolate true"));
        assertFalse(raw.contains("/Subtype /PS"));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void aBrokenCrossReferenceTableIsRebuilt() throws Exception {
        Path in = Samples.write(dir, "s01_std14_unembedded");
        byte[] bytes = Files.readAllBytes(in);
        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        Path broken = dir.resolve("broken.pdf");
        Files.writeString(broken, s.substring(0, s.lastIndexOf("startxref")) + "startxref\n999999\n%%EOF\n",
                StandardCharsets.ISO_8859_1);
        Path out = dir.resolve("fixed.pdf");
        PdfToPdfA.convert(broken, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }
}
