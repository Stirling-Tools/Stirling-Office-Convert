package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class WrappedRowTest {

    private static final String BODY = "This report summarises how the regional teams performed during the third quarter.";

    @Test
    void aWrappedDescriptionStaysWithItsNumericRow() throws IOException {
        StringBuilder c = new StringBuilder(at("F1", 60, 720, BODY));
        c.append(at("F2", 60, 690, "Code")).append(at("F2", 160, 690, "Description")).append(at("F2", 400, 690, "Amount"));
        String[][] rows = {{"A1", "Recycled office paper", "120"}, {"A2", "Toner cartridges", "45"}, {"B1", "Archive boxes", "300"}};
        float y = 674;
        for (String[] r : rows) {
            c.append(at("F1", 60, y, r[0])).append(at("F1", 160, y, r[1])).append(at("F1", 400, y, r[2]));
            y -= 16;
        }
        c.append(at("F1", 160, y, "Preferred supplier"));
        y -= 12;
        c.append(at("F1", 60, y, "B2")).append(at("F1", 160, y, "Acme Ltd")).append(at("F1", 400, y, "75"));
        y -= 16;
        c.append(at("F1", 60, y, "C1")).append(at("F1", 160, y, "Labels")).append(at("F1", 400, y, "9"));
        String md = markdown(c.toString());
        assertTrue(md.contains("Preferred supplier"), md);
        assertFalse(md.contains("| Preferred supplier |"), md);
    }

    private static String at(String font, float x, float y, String s) {
        return "BT /" + font + " 10 Tf " + x + " " + y + " Td (" + s + ") Tj ET\n";
    }

    private static String markdown(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            PDResources r = new PDResources();
            r.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
            page.setResources(r);
            d.addPage(page);
            PDStream stream = new PDStream(d);
            try (OutputStream o = stream.createOutputStream()) {
                o.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            PdfToMarkdown.convert(d, out, PdfToDocx.Options.defaults());
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
