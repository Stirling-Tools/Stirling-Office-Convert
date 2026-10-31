package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UndefinedResourcesTest {

    @TempDir
    Path dir;

    private static RawPdf selfReferencingType3() {
        RawPdf r = RawPdf.page("/ExtGState<<>>/Font<</F0 5 0 R/F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica"
                + "/Encoding/WinAnsiEncoding>>>>",
                "/G0 gs 0 0 1 rg 10 10 50 50 re f BT /F0 18 Tf 72 700 Td <01> Tj /Missing 12 Tf 72 650 Td (gone) Tj "
                        + "(also) ' /F1 12 Tf 72 600 Td (kept) Tj ET /Nothing Do");
        r.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 750 750]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</g1 6 0 R>>/Encoding<</Type/Encoding/Differences[1/g1]>>/FirstChar 1/LastChar 1"
                + "/Widths[750]>>");
        r.add(RawPdf.stream("", "750 0 0 0 750 750 d1 /G0 gs 0 0 750 750 re f BT /F0 18 Tf (\\001) Tj ET"));
        return r;
    }

    private static String content(PDDocument d) throws Exception {
        try (var in = d.getPage(0).getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String glyph(PDDocument d) throws Exception {
        COSDictionary fonts = (COSDictionary) d.getPage(0).getResources().getCOSObject().getDictionaryObject(COSName.FONT);
        COSDictionary t3 = (COSDictionary) fonts.getDictionaryObject(COSName.getPDFName("F0"));
        COSDictionary procs = (COSDictionary) t3.getDictionaryObject(COSName.CHAR_PROCS);
        StringBuilder b = new StringBuilder();
        for (COSBase v : procs.getValues()) {
            COSStream s = (COSStream) (v instanceof org.apache.pdfbox.cos.COSObject o ? o.getObject() : v);
            try (var in = s.createInputStream()) {
                b.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)).append('\n');
            }
        }
        return b.toString();
    }

    @Test
    void operatorsNamingResourcesThatAreNotDefinedAreRemovedAtEveryLevel() throws Exception {
        Path in = Hostile.write(dir, "undefined", selfReferencingType3());
        for (PdfALevel level : List.of(PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A4)) {
            Path out = dir.resolve("undefined-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                String page = content(d);
                assertFalse(page.contains("/G0"), level + ": " + page);
                assertFalse(page.contains("/Missing"), level + ": " + page);
                assertFalse(page.contains("gone"), level + ": " + page);
                assertFalse(page.contains("also"), level + ": " + page);
                assertFalse(page.contains("/Nothing"), level + ": " + page);
                assertTrue(page.contains("kept"), level + ": " + page);
                assertTrue(page.contains("/F0"), level + ": " + page);
                String glyph = glyph(d);
                assertFalse(glyph.contains("Tf"), level + ": " + glyph);
                assertFalse(glyph.contains("/G0"), level + ": " + glyph);
                assertTrue(glyph.contains("re"), level + ": " + glyph);
            }
            assertTrue(Converted.text(out).contains("kept"), level.toString());
        }
    }

    @Test
    void aLineMoveOfTextWithoutAFontIsKept() {
        UndefinedResources u = UndefinedResources.in(
                new ContentGraph.Node(ContentGraph.Kind.FORM, 0, new COSDictionary(), List.of(), new COSDictionary()),
                java.util.Map.of());
        assertEquals(List.of(), u.fix(List.of(COSName.getPDFName("X"), COSInteger.get(9), Operator.getOperator("Tf"))));
        assertEquals(List.of(Operator.getOperator("T*")), u.fix(List.of(new COSString("a"), Operator.getOperator("'"))));
        assertEquals(List.of(COSInteger.ONE, Operator.getOperator("Tw"), new COSFloat(0.5f), Operator.getOperator("Tc"),
                Operator.getOperator("T*")), u.fix(List.of(COSInteger.ONE, new COSFloat(0.5f), new COSString("a"),
                        Operator.getOperator("\""))));
        assertEquals(3, u.removed());
    }
}
