package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class ParsedStreamsBudgetTest {

    @Test
    void formsWithHugeStringsAreEvictedByTheirPayloadSize() {
        ParsedStreams parsed = new ParsedStreams();
        COSDictionary[] forms = new COSDictionary[12];
        for (int i = 0; i < forms.length; i++) {
            forms[i] = new COSDictionary();
            byte[] text = new byte[8 * 1024 * 1024];
            Arrays.fill(text, (byte) 'a');
            parsed.put(forms[i], new Object[] {new COSString(text), Operator.getOperator("Tj")}, true);
        }
        assertNull(parsed.take(forms[0]));
        assertNotNull(parsed.take(forms[forms.length - 1]));
    }

    @Test
    void aStreamWhosePayloadIsOverTheBudgetIsNotKept() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            COSStream content = document.getDocument().createCOSStream();
            try (OutputStream out = content.createOutputStream(COSName.FLATE_DECODE)) {
                out.write("BT /F1 12 Tf (".getBytes(StandardCharsets.US_ASCII));
                byte[] row = new byte[1 << 20];
                Arrays.fill(row, (byte) 'a');
                for (int i = 0; i < 40; i++) {
                    out.write(row);
                }
                out.write(") Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, content);
            ParsedStreams parsed = new ParsedStreams();
            GraphicsCollector.read(page, PageReader.displayTransform(page.getCropBox(), 0), 612, 792, parsed);
            assertNull(parsed.take(page.getCOSObject()));
        }
    }
}
