package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;

final class FontFileSubtype {

    private static final COSName TYPE1C = COSName.getPDFName("Type1C");

    private static final COSName CID_TYPE0C = COSName.getPDFName("CIDFontType0C");

    private static final COSName OPEN_TYPE = COSName.getPDFName("OpenType");

    private FontFileSubtype() {}

    static String fix(PDDocument doc, COSDictionary fd, boolean cid, PdfALevel level) throws IOException {
        if (!(fd.getDictionaryObject(COSName.FONT_FILE3) instanceof COSStream s)) {
            return null;
        }
        COSName sub = s.getCOSName(COSName.SUBTYPE);
        boolean valid = TYPE1C.equals(sub) || CID_TYPE0C.equals(sub) || level.part() > 1 && OPEN_TYPE.equals(sub);
        if (valid) {
            return null;
        }
        byte[] data = StreamFixer.read(s);
        if (data == null || data.length < 4) {
            return null;
        }
        COSName cff = cid ? CID_TYPE0C : TYPE1C;
        if (data[0] == 1 && (data[2] & 0xFF) >= 4) {
            s.setItem(COSName.SUBTYPE, cff);
            return "Gave an embedded CFF font program its subtype";
        }
        boolean otto = data[0] == 'O' && data[1] == 'T' && data[2] == 'T' && data[3] == 'O';
        if (otto && level.part() > 1) {
            s.setItem(COSName.SUBTYPE, OPEN_TYPE);
            return "Gave an embedded OpenType font program its subtype";
        }
        if (!otto) {
            return null;
        }
        byte[] table;
        try (OpenTypeFont otf = new OTFParser(true).parse(new RandomAccessReadBuffer(data))) {
            TTFTable t = otf.getTableMap().get("CFF ");
            table = t == null ? null : otf.getTableBytes(t);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (table == null) {
            return null;
        }
        COSStream out = doc.getDocument().createCOSStream();
        out.setItem(COSName.SUBTYPE, cff);
        try (OutputStream o = out.createOutputStream(COSName.FLATE_DECODE)) {
            o.write(table);
        }
        fd.setItem(COSName.FONT_FILE3, out);
        return "Stored an OpenType font program as the CFF program PDF/A-1 accepts";
    }
}
