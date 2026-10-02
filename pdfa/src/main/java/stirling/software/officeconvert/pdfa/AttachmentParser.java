package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdfparser.PDFParser;

import stirling.software.officeconvert.extract.PdfFiles;

final class AttachmentParser extends PDFParser {

    static final int MAX_OBJECTS = 100_000;

    static final long MAX_STRUCTURE_BYTES = 8L << 20;

    private int objects;

    private int depth;

    private long structureBytes;

    private boolean exceeded;

    AttachmentParser(RandomAccessRead source) throws IOException {
        super(source);
        setLenient(false);
    }

    @Override
    protected COSBase parseDirObject() throws IOException {
        PdfFiles.stopIfInterrupted();
        if (exceeded || ++objects > MAX_OBJECTS || depth >= 64) {
            exceeded = true;
            throw new IOException("An attachment exceeds the PDF object parsing budget");
        }
        depth++;
        try {
            return super.parseDirObject();
        } finally {
            depth--;
        }
    }

    @Override
    protected COSStream parseCOSStream(COSDictionary dictionary) throws IOException {
        COSName type = dictionary.getCOSName(COSName.TYPE);
        boolean objectStream = COSName.OBJ_STM.equals(type);
        boolean xref = COSName.XREF.equals(type);
        if (objectStream && dictionary.getLong(COSName.N, 0) > MAX_OBJECTS
                || xref && dictionary.getLong(COSName.SIZE, 0) > MAX_OBJECTS) {
            exceeded = true;
            throw new IOException("An attachment declares too many PDF objects");
        }
        COSStream stream = super.parseCOSStream(dictionary);
        if (objectStream || xref) {
            byte[] bytes = Decoded.bytes(stream, MAX_STRUCTURE_BYTES - structureBytes,
                    "An attachment's object and cross-reference streams");
            structureBytes += bytes.length;
            if (objectStream && ContentTokens.tokens(bytes) > MAX_OBJECTS) {
                exceeded = true;
                throw new IOException("An attachment's object stream has too many values");
            }
            stream.removeItem(COSName.DECODE_PARMS);
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(bytes);
            }
        }
        return stream;
    }

    void checkBudget() throws IOException {
        if (exceeded) {
            throw new IOException("An attachment exceeds the PDF object parsing budget");
        }
    }
}
