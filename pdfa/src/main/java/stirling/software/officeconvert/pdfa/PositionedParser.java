package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.pdfparser.PDFStreamParser;

final class PositionedParser extends PDFStreamParser {

    PositionedParser(byte[] content) {
        super(content);
    }

    long position() {
        try {
            return source.getPosition();
        } catch (IOException e) {
            return -1;
        }
    }
}
