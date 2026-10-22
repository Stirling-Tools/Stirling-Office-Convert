package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Outlines;

class OdtOutlineTest {

    @TempDir
    Path dir;

    @Test
    void headingsBookmarkAtTheirOutlineLevel() throws IOException {
        String styles = OdfFixtures.styles("<style:style style:name=\"Heading_20_2\" style:display-name=\"Heading 2\""
                + " style:family=\"paragraph\" style:default-outline-level=\"2\"/>", "", "");
        String body = "<text:h text:outline-level=\"1\">Chapter</text:h><text:p>Text</text:p>"
                + "<text:h text:style-name=\"Heading_20_2\">From the style</text:h>"
                + "<text:h text:outline-level=\"3\">Third</text:h><text:p>Plain</text:p><text:h>Default level</text:h>";
        Path in = OdfFixtures.write(dir, "doc.odt", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", OdfFixtures.text(body)), styles));
        Path pdf = dir.resolve("doc.pdf");
        OfficeToPdf.convert(in, pdf);
        assertEquals(List.of("1 p1 Chapter", "2 p1 From the style", "3 p1 Third", "1 p1 Default level"),
                Outlines.titles(pdf));
    }
}
