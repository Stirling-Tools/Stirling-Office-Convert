package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class SmokeRegressionTest {

    private static String text(String content, PDResources resources) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            page.setResources(resources);
            d.addPage(page);
            PDStream stream = new PDStream(d);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            return PdfToText.text(d, PdfToDocx.Options.defaults());
        }
    }

    private static PDResources fonts() {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.COURIER));
        r.put(COSName.getPDFName("F2"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        return r;
    }

    @Test
    void aLongNumberedCodeLineDoesNotSwallowTheNextNumber() throws IOException {
        StringBuilder c = new StringBuilder("BT /F1 10 Tf 12 TL 72 700 Td ");
        String[] lines = {"8      continue", "9  orders.append({'id': fields[0], 'total': float(fields[4])}) # keep it", "10 return orders"};
        for (String l : lines) {
            c.append('(').append(l.replace("(", "\\(").replace(")", "\\)")).append(") Tj T* ");
        }
        String out = text(c.append("ET").toString(), fonts());
        assertTrue(out.lines().anyMatch(l -> l.strip().startsWith("10")), out);
    }

    @Test
    void angledTextStaysOutOfTheBodyText() throws IOException {
        String body = "Volumes grew in every region except the north where a warehouse move cut capacity";
        StringBuilder c = new StringBuilder("BT /F2 11 Tf 14 TL 72 600 Td ");
        for (int i = 0; i < 6; i++) {
            c.append('(').append(body).append(") Tj T* ");
        }
        c.append("ET 0.85 g BT /F2 60 Tf 0.7071 0.7071 -0.7071 0.7071 150 450 Tm (CONFIDENTIAL) Tj ET");
        String out = text(c.toString(), fonts());
        assertTrue(out.lines().filter(l -> l.contains("Volumes")).allMatch(l -> l.contains(body)), out);
        assertFalse(out.contains("CONFIDENTIAL"), "the watermark is artwork, not body text");
    }

    @Test
    void texBitmapFontLigaturesBecomeLetters() throws IOException {
        PDResources r = new PDResources();
        r.put(COSName.getPDFName("T3"), new PDType3Font(type3()));
        String out = text("BT /T3 1 Tf 100 0 0 100 72 600 Tm <1c6e6973686564> Tj ET", r);
        assertTrue(out.contains("finished"), out);
    }

    private static COSDictionary type3() {
        COSDictionary f = new COSDictionary();
        f.setItem(COSName.TYPE, COSName.FONT);
        f.setItem(COSName.SUBTYPE, COSName.TYPE3);
        COSArray matrix = new COSArray();
        for (float v : new float[] {0.01f, 0, 0, 0.01f, 0, 0}) {
            matrix.add(new COSFloat(v));
        }
        f.setItem(COSName.FONT_MATRIX, matrix);
        COSArray bbox = new COSArray();
        for (int v : new int[] {0, 0, 60, 70}) {
            bbox.add(COSInteger.get(v));
        }
        f.setItem(COSName.FONT_BBOX, bbox);
        int[] codes = {0x1C, 'd', 'e', 'h', 'i', 'n', 's'};
        COSArray diffs = new COSArray();
        COSDictionary procs = new COSDictionary();
        for (int code : codes) {
            diffs.add(COSInteger.get(code));
            diffs.add(COSName.getPDFName("a" + code));
            COSStream proc = new COSStream();
            try (OutputStream o = proc.createOutputStream()) {
                o.write("50 0 0 0 40 60 d1 0 0 40 60 re f".getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            procs.setItem(COSName.getPDFName("a" + code), proc);
        }
        COSDictionary enc = new COSDictionary();
        enc.setItem(COSName.TYPE, COSName.ENCODING);
        enc.setItem(COSName.DIFFERENCES, diffs);
        f.setItem(COSName.ENCODING, enc);
        f.setItem(COSName.CHAR_PROCS, procs);
        f.setInt(COSName.FIRST_CHAR, 0x1C);
        f.setInt(COSName.LAST_CHAR, 's');
        COSArray widths = new COSArray();
        for (int i = 0x1C; i <= 's'; i++) {
            widths.add(new COSFloat(50));
        }
        f.setItem(COSName.WIDTHS, widths);
        return f;
    }
}
