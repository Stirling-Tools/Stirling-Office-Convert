package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibreOfficeTest {

    @TempDir
    Path dir;

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void hardensAFreshProfile() throws IOException {
        LibreOffice.harden(dir);
        String xcu = Files.readString(dir.resolve("user/registrymodifications.xcu"), StandardCharsets.UTF_8);
        assertTrue(xcu.startsWith("<?xml"));
        assertTrue(xcu.contains("<prop oor:name=\"MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value>"));
        assertTrue(xcu.contains("<prop oor:name=\"DisableMacrosExecution\" oor:op=\"fuse\"><value>true</value>"));
        assertTrue(xcu.contains("Office.Writer/Content/Update\"><prop oor:name=\"Link\" oor:op=\"fuse\"><value>0</value>"));
        assertTrue(xcu.contains("Office.Calc/Content/Update\"><prop oor:name=\"Link\" oor:op=\"fuse\"><value>1</value>"));
        assertTrue(xcu.contains("Job[&apos;UpdateCheck&apos;]/Arguments\"><prop oor:name=\"AutoCheckEnabled\""));
        assertTrue(xcu.contains("<prop oor:name=\"ooInetHTTPProxyPort\" oor:op=\"fuse\"><value>9</value>"));
        assertTrue(xcu.strip().endsWith("</oor:items>"));
    }

    @Test
    void replacesLooserSettingsAndKeepsTheRest() throws IOException {
        Path xcu = Files.createDirectories(dir.resolve("user")).resolve("registrymodifications.xcu");
        Files.writeString(xcu, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<oor:items xmlns:oor=\"http://openoffice.org/2001/registry\">\n"
                + "<item oor:path=\"/org.openoffice.Office.Common/Security/Scripting\"><prop oor:name=\"MacroSecurityLevel\""
                + " oor:op=\"fuse\"><value>0</value></prop></item>\n"
                + "<item oor:path=\"/org.openoffice.Office.Common/Misc\"><prop oor:name=\"FirstRun\" oor:op=\"fuse\">"
                + "<value>false</value></prop></item>\n</oor:items>\n", StandardCharsets.UTF_8);
        LibreOffice.harden(dir);
        LibreOffice.harden(dir);
        String text = Files.readString(xcu, StandardCharsets.UTF_8);
        assertEquals(1, count(text, "\"MacroSecurityLevel\""));
        assertTrue(text.contains("\"MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value>"));
        assertTrue(text.contains("\"FirstRun\""));
        assertEquals(1, count(text, "</oor:items>"));
    }

    @Test
    void commandsRunHeadlessWithoutRestoreOrLocks() {
        List<String> cmd = LibreOffice.command(Path.of("soffice"), dir.resolve("p"), List.of("--convert-to", "pdf"),
                dir.resolve("out"), dir.resolve("in.docx"));
        assertEquals("soffice", cmd.get(0));
        assertTrue(cmd.get(1).startsWith("-env:UserInstallation=file:"));
        for (String flag : List.of("--headless", "--norestore", "--nolockcheck", "--nodefault", "--nologo",
                "--nofirststartwizard")) {
            assertTrue(cmd.contains(flag), flag);
        }
        assertEquals(dir.resolve("in.docx").toAbsolutePath().toString(), cmd.get(cmd.size() - 1));
    }

    @Test
    void pdfExportsPickTheFamilysFilterAndCapThePages() {
        assertEquals("pdf:writer_pdf_Export", LibreOffice.pdfTarget("docx", 0));
        assertEquals("pdf:impress_pdf_Export", LibreOffice.pdfTarget("pptx", 0));
        assertEquals("pdf:calc_pdf_Export:{\"PageRange\":{\"type\":\"string\",\"value\":\"1-300\"}}",
                LibreOffice.pdfTarget("xlsx", 300));
    }

    @Test
    void offersPdfBesideItsOfficeTargets() {
        assertTrue(LibreOffice.converts("pdf"));
        assertTrue(LibreOffice.converts("docx"));
        assertFalse(LibreOffice.converts("xlsx"));
        assertTrue(LibreOffice.formats().contains("pdf"));
    }
}
