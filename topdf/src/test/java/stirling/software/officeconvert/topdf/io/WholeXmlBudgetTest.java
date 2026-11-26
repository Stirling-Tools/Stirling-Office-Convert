package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class WholeXmlBudgetTest {

    @TempDir
    Path dir;

    private static String many() {
        return "<e/>".repeat((int) XmlElements.domElements() + 10);
    }

    private void refused(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        IOException e = assertTimeoutPreemptively(Duration.ofMinutes(2), () -> assertThrows(IOException.class,
                () -> OfficeToPdf.convert(in, dir.resolve(name + ".pdf"))));
        assertTrue(e.getMessage().contains("XML elements"), e.getMessage());
    }

    @Test
    void aFlatOpcPackagePastTheElementBudgetIsRefused() throws IOException {
        refused("many.xml", ("<?xml version=\"1.0\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/"
                + "2006/xmlPackage\">" + many() + "</pkg:package>").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fictionBooksPastTheElementBudgetAreRefusedAsBytesAndAsDecodedText() throws IOException {
        String book = "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><body><section>" + many()
                + "</section></body></FictionBook>";
        refused("many.fb2", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + book).getBytes(StandardCharsets.UTF_8));
        refused("wide.fb2", ("\uFEFF<?xml version=\"1.0\" encoding=\"UTF-32LE\"?>" + book)
                .getBytes(Charset.forName("UTF-32LE")));
    }
}
