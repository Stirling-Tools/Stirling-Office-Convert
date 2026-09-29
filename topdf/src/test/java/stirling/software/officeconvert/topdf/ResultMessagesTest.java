package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class ResultMessagesTest {

    @TempDir
    Path dir;

    private static byte[] fonts(String xmlDecl, String... families) {
        StringBuilder runs = new StringBuilder();
        for (String f : families) {
            runs.append("<w:r><w:rPr><w:rFonts w:ascii=\"").append(f).append("\" w:hAnsi=\"").append(f)
                    .append("\"/></w:rPr><w:t>x</w:t></w:r>");
        }
        return Fixtures.edit(Fixtures.docx("placeholder")).put("word/document.xml", xmlDecl + "<w:document"
                + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p>" + runs
                + "</w:p></w:body></w:document>").bytes();
    }

    @Test
    void warningsAreBoundedAndPlainText() throws IOException {
        String[] many = new String[3000];
        for (int i = 0; i < many.length; i++) {
            many[i] = "Missing " + i;
        }
        Path in = Fixtures.write(dir, "many.docx", fonts("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", many));
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("many.pdf"));
        assertTrue(r.warnings().size() <= RenderJob.MAX_WARNINGS + 20, "warnings " + r.warnings().size());
        assertTrue(r.warnings().contains(RenderJob.MORE_WARNINGS), r.warnings().toString());
        Path forged = Fixtures.write(dir, "forged.docx", fonts("<?xml version=\"1.1\" encoding=\"UTF-8\"?>",
                "Nofont&#10;OK C:\\secret.docx -&gt; C:\\secret.pdf&#10;x", "Nofont&#x1b;[31mRED&#x1b;[0m&#x202e;txt"));
        OfficeToPdf.Result f = OfficeToPdf.convert(forged, dir.resolve("forged.pdf"));
        assertFalse(f.warnings().isEmpty());
        for (String w : f.warnings()) {
            assertFalse(w.chars().anyMatch(c -> c < 32 || c == 0x7F || c == 0x202E), w);
        }
    }

    @Test
    void aDoctypeIsRefusedWithTheSameMessageInEveryFormat() throws IOException {
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e \"v\">]>";
        byte[][] docs = {
            Fixtures.edit(Fixtures.docx("a")).put("word/document.xml", doctype + Fixtures.edit(Fixtures.docx("a"))
                    .text("word/document.xml").replaceFirst("<\\?xml[^>]*\\?>", "")).bytes(),
            Fixtures.edit(Fixtures.xlsx(new String[][] {{"a"}})).put("xl/workbook.xml", doctype
                    + Fixtures.edit(Fixtures.xlsx(new String[][] {{"a"}})).text("xl/workbook.xml")
                            .replaceFirst("<\\?xml[^>]*\\?>", "")).bytes(),
            Fixtures.edit(Fixtures.pptx("a")).put("ppt/slides/slide1.xml", doctype + Fixtures.edit(Fixtures.pptx("a"))
                    .text("ppt/slides/slide1.xml").replaceFirst("<\\?xml[^>]*\\?>", "")).bytes()};
        String[] names = {"d.docx", "x.xlsx", "p.pptx"};
        for (int i = 0; i < docs.length; i++) {
            Path in = Fixtures.write(dir, names[i], docs[i]);
            IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("out.pdf")));
            assertEquals(OfficeToPdf.DOCTYPE, e.getMessage(), names[i]);
        }
    }
}
