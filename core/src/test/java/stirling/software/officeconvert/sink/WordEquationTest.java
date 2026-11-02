package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.text.TextWriter;

class WordEquationTest {

    private static final String MATH = "<m:sSub><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sub><m:r><m:t>i</m:t></m:r></m:sub></m:sSub>";

    private static Paragraph paragraph() {
        RunStyle math = SampleDocument.BODY.withMath(MATH);
        Paragraph p = new Paragraph();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = 10;
        p.inlines.add(new Inline.Text("Mean ", SampleDocument.BODY, null, -1));
        p.inlines.add(new Inline.Text("x", math, null, -1));
        p.inlines.add(new Inline.Text("i", math.withVertAlign(-1), null, -1));
        p.inlines.add(new Inline.Text(" here", SampleDocument.BODY, null, -1));
        return p;
    }

    private static String document(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("document.xml missing");
    }

    @Test
    void aFormulaIsOneWordEquationInPlaceOfItsRuns() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter w = new DocxWriter(out)) {
            StyleSheet styles = new StyleSheet(SampleDocument.BODY);
            w.begin(styles, null);
            w.block(paragraph());
            w.finish(JsonModel.defaultPage(), null, new Numbering(), styles, "Math", "Tester");
        }
        String xml = document(out.toByteArray());
        assertEquals(1, xml.split("<m:oMath ", -1).length - 1, xml);
        assertTrue(xml.indexOf("Mean") < xml.indexOf("<m:oMath ") && xml.indexOf("<m:oMath ") < xml.indexOf("here"));
        assertFalse(xml.contains("<w:t xml:space=\"preserve\">x</w:t>"), "the formula's own runs are not written too");
        assertTrue(xml.contains("w:lineRule=\"atLeast\""), "an equation is not clipped by an exact line height");
    }

    @Test
    void otherFormatsKeepTheTextRuns() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TextWriter w = new TextWriter(out)) {
            StyleSheet styles = new StyleSheet(SampleDocument.BODY);
            w.begin(styles, null);
            w.block(paragraph());
            w.finish(JsonModel.defaultPage(), null, new Numbering(), styles, "Math", "Tester");
        }
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Mean xi here"));
    }
}
