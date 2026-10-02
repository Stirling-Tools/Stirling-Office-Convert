package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

class ContentTokensTest {

    private static final String[] STREAMS = {
        "BT /F1 12 Tf 72 712 Td (Hello) Tj ET",
        "q 1 0 0 1 10 20 cm 0.5 g 10 10 100 50 re f Q",
        "[(A) -250 (B) 3.5 (C\\)) -.5 (D)] TJ",
        "(esc \\n\\r\\t\\b\\f \\( \\) \\\\ \\101\\1\\12x \\8) Tj",
        "(line\\\r\n continued) Tj (line\\\ncontinued) Tj",
        "(nested (paren) here) Tj (unbalanced ( one) Tj",
        "(close\r/early) Tj (close)\r\n/F1 Tf",
        "(open ( inner)\r/F1 12 Tf (open ( two)\r\n>x) Tj (esc ( \\)\n/G) Tj",
        "<48656C6C6F> Tj < 48 65 6c\n6C 6 > Tj <4G> Tj",
        "/Name#20Space /A/B/C /été /",
        "/P <</MCID 3 /ActualText (x)>> BDC (a) Tj EMC",
        "BI /W 2 /H 2 /BPC 8 /CS /G ID \u0001\u0002\u0003\u0004 EI Q",
        "0 0 d0 1 0 0 1 0 0 d1",
        "--5 m +3 l + 4 1.2.3 - . -.5 3e 0.5- 1-2 TL",
        "[1e2 3E 4.5e -1e-2 1 0 R /X] TJ",
        "% comment line\nq % trailing\rQ%eof",
        "true false null nullx truex falsey Tj",
        "] ] q",
        ") > { } * ' \" T* Tw",
        "( unterminated string",
        "[ (a) [ (b) [ (c) ] ] ] TJ",
        "[ (a) <</K 1>> ] TJ",
        "[ (a) true ] TJ",
        "<< /Unclosed 1",
        "\u0080ÿ   x",
        "Id IDx q",
        "[ 1 2",
        "<414",
        "(\\",
        "12345678901234567890 99999999999 -0 0.000001 .5. 1..2 TJ",
        "",
        "   \n\r\t\f\u0000 ",
    };

    @Test
    void tokensMatchThePdfBoxParserExactly() throws IOException {
        for (String text : STREAMS) {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            List<Object> expected = new ArrayList<>();
            List<Object> actual = new ArrayList<>();
            String expectedError = collect(new PDFStreamParser(new Bytes(bytes))::parseNextToken, expected);
            String actualError = collect(ContentTokens.open(new Bytes(bytes)), actual);
            assertEquals(describe(expected) + " " + expectedError, describe(actual) + " " + actualError, text);
        }
    }

    private static String collect(StreamRunner.Tokens tokens, List<Object> out) {
        try {
            Object token;
            while ((token = tokens.next()) != null) {
                out.add(token);
            }
            return "end";
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static String describe(List<Object> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Object t : tokens) {
            sb.append(describe(t)).append(' ');
        }
        return sb.toString();
    }

    private static String describe(Object t) {
        if (t instanceof Operator op) {
            return "op:" + op.getName() + (op.getImageData() == null ? "" : Arrays.toString(op.getImageData()))
                    + (op.getImageParameters() == null ? "" : describe(op.getImageParameters()));
        }
        if (t instanceof COSString s) {
            return "str:" + Arrays.toString(s.getBytes()) + s.getForceHexForm();
        }
        if (t instanceof COSFloat f) {
            return "float:" + Float.floatToIntBits(f.floatValue()) + ":" + f;
        }
        if (t instanceof COSInteger i) {
            return "int:" + i.longValue();
        }
        if (t instanceof COSName n) {
            return "name:" + n.getName();
        }
        if (t instanceof COSArray a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.size(); i++) {
                sb.append(describe(a.get(i))).append(',');
            }
            return sb.append(']').toString();
        }
        if (t instanceof COSDictionary d) {
            StringBuilder sb = new StringBuilder("<<");
            for (COSName k : d.keySet()) {
                sb.append(k.getName()).append('=').append(describe(d.getItem(k))).append(',');
            }
            return sb.append(">>").toString();
        }
        return t.getClass().getSimpleName() + ":" + t;
    }

    private record Bytes(byte[] bytes) implements PDContentStream {
        @Override
        public InputStream getContents() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RandomAccessRead getContentsForRandomAccess() {
            return new RandomAccessReadBuffer(bytes);
        }

        @Override
        public RandomAccessRead getContentsForStreamParsing() {
            return new RandomAccessReadBuffer(bytes);
        }

        @Override
        public PDResources getResources() {
            return null;
        }

        @Override
        public PDRectangle getBBox() {
            return null;
        }

        @Override
        public Matrix getMatrix() {
            return null;
        }
    }
}
