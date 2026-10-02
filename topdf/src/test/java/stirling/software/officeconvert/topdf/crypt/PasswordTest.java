package stirling.software.officeconvert.topdf.crypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class PasswordTest {

    @TempDir
    Path dir;

    private static byte[] encrypt(byte[] pkg, EncryptionMode mode, String password) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncryptionInfo info = new EncryptionInfo(mode);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword(password);
            try (OutputStream os = enc.getDataStream(fs)) {
                os.write(pkg);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private String convert(String name, byte[] data, String password) throws IOException {
        Path in = Fixtures.write(dir, name, data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().password(password));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    private IOException refused(String name, byte[] data, String password) {
        Path in = Fixtures.write(dir, name, data);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf"),
                OfficeToPdf.Options.defaults().password(password)));
        try (Stream<Path> left = Files.list(dir)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith(name + ".pdf")), "no output");
        } catch (IOException x) {
            throw new AssertionError(x);
        }
        return e;
    }

    @Test
    void anAgileEncryptedDocumentOpensWithItsPasswordOnly() throws Exception {
        byte[] locked = encrypt(Fixtures.docx("Quarterly secret"), EncryptionMode.agile, "s3cret");
        assertTrue(convert("agile.docx", locked, "s3cret").contains("Quarterly secret"));
        assertEquals(Passwords.PROTECTED, refused("none.docx", locked, null).getMessage());
        assertEquals(Passwords.WRONG, refused("wrong.docx", locked, "guess").getMessage());
    }

    @Test
    void aStandardEncryptedWorkbookOpensWithItsPassword() throws Exception {
        byte[] locked = encrypt(Fixtures.xlsx(new String[][] {{"Ledger", "42"}}), EncryptionMode.standard, "pw");
        String text = convert("standard.xlsx", locked, "pw");
        assertTrue(text.contains("Ledger") && text.contains("42"), text);
    }

    @Test
    void anEncryptedPresentationPackageIsFoundByContentWhateverItsName() throws Exception {
        byte[] locked = encrypt(Fixtures.pptx("Slide secret"), EncryptionMode.agile, "pw");
        assertTrue(convert("deck.docx", locked, "pw").contains("Slide secret"));
    }

    @Test
    void anExcelWorkbookEncryptedWithRc4OpensWithItsPassword() throws Exception {
        byte[] plain;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Accounts").createRow(0).createCell(0).setCellValue("Hidden total");
            wb.createSheet("Second").createRow(3).createCell(2).setCellValue(1234.5);
            wb.write(out);
            plain = out.toByteArray();
        }
        byte[] locked = legacyEncrypt(plain, "pass");
        String text = convert("rc4.xls", locked, "pass");
        assertTrue(text.contains("Hidden total") && text.contains("1234.5"), text);
        assertEquals(Passwords.PROTECTED, refused("rc4none.xls", locked, null).getMessage());
        assertEquals(Passwords.WRONG, refused("rc4wrong.xls", locked, "nope").getMessage());
    }

    @Test
    void aWorkbookLockedOnlyWithExcelsBuiltInPasswordNeedsNone() throws Exception {
        byte[] plain;
        try (HSSFWorkbook wb = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Shared").createRow(0).createCell(0).setCellValue("Read only");
            wb.write(out);
            plain = out.toByteArray();
        }
        assertTrue(convert("default.xls", legacyEncrypt(plain, "VelvetSweatshop"), null).contains("Read only"));
    }

    @Test
    void anEncryptedLegacyPresentationOpensWithItsPassword() throws Exception {
        byte[] plain;
        try (HSLFSlideShow ppt = new HSLFSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            HSLFTextBox box = ppt.createSlide().createTextBox();
            box.setAnchor(new Rectangle2D.Double(60, 60, 400, 60));
            box.setText("Board only");
            ppt.write(out);
            plain = out.toByteArray();
        }
        byte[] locked;
        Biff8EncryptionKey.setCurrentUserPassword("deck");
        try (HSLFSlideShow ppt = new HSLFSlideShow(new ByteArrayInputStream(plain));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.write(out);
            locked = out.toByteArray();
        } finally {
            Biff8EncryptionKey.setCurrentUserPassword(null);
        }
        assertTrue(convert("locked.ppt", locked, "deck").contains("Board only"));
        assertEquals(Passwords.WRONG, refused("wrong.ppt", locked, "nope").getMessage());
        assertEquals(null, Biff8EncryptionKey.getCurrentUserPassword());
    }

    @Test
    void optionsNeverPrintThePassword() {
        String s = OfficeToPdf.Options.defaults().password("hunter2").toString();
        assertFalse(s.contains("hunter2"), s);
        assertThrows(IllegalArgumentException.class, () -> OfficeToPdf.Options.defaults().password("x".repeat(256)));
    }

    private static byte[] legacyEncrypt(byte[] xls, String password) throws IOException {
        Biff8EncryptionKey.setCurrentUserPassword(password);
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(xls));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } finally {
            Biff8EncryptionKey.setCurrentUserPassword(null);
        }
    }
}
