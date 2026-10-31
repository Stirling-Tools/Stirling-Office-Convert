package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.function.IntUnaryOperator;

import org.apache.fontbox.cmap.CMap;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class CodeWidths {

    private CodeWidths() {}

    static IntUnaryOperator of(PDFont font, int bytesPerCode) {
        if (!(font instanceof PDType0Font t0) || bytesPerCode == 1 || t0.getCMap() == null
                || t0.getCOSObject().getDictionaryObject(COSName.ENCODING) instanceof COSName n
                && n.getName().startsWith("Identity-")) {
            return code -> bytesPerCode;
        }
        CMap cmap = t0.getCMap();
        return code -> width(cmap, code, bytesPerCode);
    }

    static int width(CMap cmap, int code, int fallback) {
        for (int n = 1; n <= 4; n++) {
            if (n < 4 && code >= 1 << (8 * n)) {
                continue;
            }
            byte[] bytes = new byte[n];
            for (int i = 0; i < n; i++) {
                bytes[i] = (byte) (code >>> (8 * (n - 1 - i)));
            }
            Bounded in = new Bounded(bytes);
            try {
                if (cmap.readCode(in) == code && !in.overrun && in.available() == 0) {
                    return n;
                }
            } catch (IOException | RuntimeException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static final class Bounded extends ByteArrayInputStream {

        private boolean overrun;

        Bounded(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            int b = super.read();
            overrun |= b < 0;
            return b;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            int n = super.read(b, off, len);
            overrun |= n < len;
            return n;
        }
    }
}
