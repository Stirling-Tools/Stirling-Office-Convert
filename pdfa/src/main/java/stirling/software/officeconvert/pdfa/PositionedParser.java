package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.pdfparser.PDFStreamParser;

final class PositionedParser extends PDFStreamParser {

    private final byte[] content;

    private long cut = -1;

    PositionedParser(byte[] content) {
        super(content);
        this.content = content;
    }

    long position() {
        try {
            return source.getPosition();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Where parsing stopped before the end because PDFBox gave up on a token without throwing, or -1. */
    long cut() {
        return cut;
    }

    @Override
    public Object parseNextToken() throws IOException {
        long before = position();
        Object token = super.parseNextToken();
        if (token == null && before >= 0 && !blank(before)) {
            cut = before;
        }
        return token;
    }

    private boolean blank(long from) {
        boolean comment = false;
        for (long i = from; i < content.length; i++) {
            byte b = content[(int) i];
            if (b == '\n' || b == '\r') {
                comment = false;
            } else if (b == '%') {
                comment = true;
            } else if (!comment && !ContentTokens.space(b)) {
                return false;
            }
        }
        return true;
    }
}
