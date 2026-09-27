package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpreadsheetRobustnessTest {

    @TempDir Path dir;

    private Path pdf(String content, String password) throws IOException {
        Path out = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            COSDictionary broken = new COSDictionary();
            broken.setItem(COSName.TYPE, COSName.FONT);
            broken.setItem(COSName.SUBTYPE, COSName.TYPE0);
            broken.setName(COSName.BASE_FONT, "Broken");
            broken.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            res.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), broken);
            page.setResources(res);
            PDStream stream = new PDStream(doc);
            try (OutputStream os = stream.createOutputStream()) {
                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            doc.addPage(page);
            if (password != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
            }
            doc.save(out.toFile());
        }
        return out;
    }

    private String strings(Path pdf, PdfToXlsx.Options options) throws IOException {
        Path xlsx = dir.resolve("out.xlsx");
        PdfToXlsx.convert(pdf, xlsx, options);
        return SpreadsheetPdfs.part(SpreadsheetPdfs.parts(Files.readAllBytes(xlsx)), "xl/sharedStrings.xml");
    }

    @Test
    void brokenFontLosesOnlyItsOwnText() throws IOException {
        String text = strings(pdf("BT /F1 12 Tf 72 700 Td (Hello) Tj ET\nBT /F2 12 Tf 72 680 Td <0001> Tj ET\n"
                + "BT /F1 12 Tf 72 660 Td (World) Tj ET\n", null), PdfToXlsx.Options.defaults());
        assertTrue(text.contains("Hello") && text.contains("World"), "text around the broken font is kept");
    }

    @Test
    void truncatedStreamKeepsWhatCameBefore() throws IOException {
        String text = strings(pdf("BT /F1 12 Tf 72 700 Td (Before the break) Tj ET\nBI /W 1 /H 1 Infinity", null),
                PdfToXlsx.Options.defaults());
        assertTrue(text.contains("Before the break"));
    }

    @Test
    void aProtectedPdfOpensWithItsPassword() throws IOException {
        Path pdf = pdf("BT /F1 12 Tf 72 700 Td (Confidential figures) Tj ET\n", "secret");
        assertThrows(IOException.class, () -> strings(pdf, PdfToXlsx.Options.defaults()));
        assertTrue(strings(pdf, PdfToXlsx.Options.defaults().withPassword("secret")).contains("Confidential figures"));
    }
}
