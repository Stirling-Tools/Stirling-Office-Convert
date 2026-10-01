package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InputStream;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

final class CodeReader {

    private final CMap cmap;

    private CodeReader(CMap cmap) {
        this.cmap = cmap;
    }

    static CodeReader of(COSDictionary font) {
        if (!COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))) {
            return new CodeReader(null);
        }
        CMap cmap = cmap(font.getDictionaryObject(COSName.ENCODING));
        if (cmap == null) {
            cmap = cmap(COSName.IDENTITY_H);
        }
        return new CodeReader(cmap);
    }

    static CMap cmap(COSBase encoding) {
        try {
            if (encoding instanceof COSName n) {
                return new CMapParser().parsePredefined(n.getName());
            }
            if (encoding instanceof COSStream s) {
                try (var in = s.createInputStream()) {
                    return new CMapParser().parse(new RandomAccessReadBuffer(in.readNBytes(4 << 20)));
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    boolean multiByte() {
        return cmap != null;
    }

    CMap cmap() {
        return cmap;
    }

    int read(InputStream in) throws IOException {
        if (cmap == null) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("end of string");
            }
            return b;
        }
        return cmap.readCode(in);
    }

    int cid(int code) {
        return cmap == null ? code : cmap.toCID(code);
    }
}
