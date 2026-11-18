package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.junit.jupiter.api.Test;

class FontRebuildTest {

    @Test
    void anUnloadableType1WhoseSubroutinesExplodeIsNotTraced() throws Exception {
        Rectangle2D own = outline(3);
        assertEquals(new Rectangle2D.Double(50, 0, 410, 700), own);
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertNotEquals(own, outline(40)));
    }

    private static Rectangle2D outline(int levels) throws Exception {
        FontPrograms.Type1 p = FontPrograms.fanOutType1("FanOut", levels);
        String program = new String(p.data(), StandardCharsets.ISO_8859_1).replace(
                "/FontMatrix [0.001 0 0 0.001 0 0] readonly def", "/FontMatrix [0.001 0 0 0.001 0 0 readonly def");
        int length1 = p.length1() - 1;
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/FanOut/FirstChar 65/LastChar 65"
                + "/Widths[500]/FontDescriptor<</Type/FontDescriptor/FontName/FanOut/Flags 32"
                + "/FontBBox[0 -10 900 760]/ItalicAngle 0/Ascent 760/Descent -10/CapHeight 700/StemV 80"
                + "/FontFile 5 0 R>>>>>>", "BT /F1 12 Tf 50 700 Td (A) Tj ET");
        pdf.add(RawPdf.stream("/Length1 " + length1 + "/Length2 " + p.length2() + "/Length3 " + p.length3(),
                program));
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            PDFont font = d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertFalse(font.isEmbedded());
            FontRebuild.rebuild(d, font, Set.of(65), FontRebuild.Mode.OWN, null, PdfALevel.A2B, 1);
            PDFont rebuilt = PDFontFactory.createFont(font.getCOSObject());
            return ((PDVectorFont) rebuilt).getPath(65).getBounds2D();
        }
    }
}
