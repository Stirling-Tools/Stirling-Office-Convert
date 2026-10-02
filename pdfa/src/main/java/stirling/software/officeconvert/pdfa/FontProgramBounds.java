package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class FontProgramBounds {

    static final long MAX_BYTES = 32L << 20;

    private FontProgramBounds() {}

    static void run(PDDocument doc) throws IOException {
        Set<COSStream> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        CosWalk.walk(doc, object -> {
            if (!(object instanceof COSDictionary dictionary)) {
                return;
            }
            for (COSName key : new COSName[] {COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3}) {
                if (dictionary.getDictionaryObject(key) instanceof COSStream stream && seen.add(stream)) {
                    byte[] bytes = Decoded.bytes(stream, MAX_BYTES, "A font program");
                    stream.removeItem(COSName.DECODE_PARMS);
                    try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        out.write(bytes);
                    }
                }
            }
        });
    }
}
