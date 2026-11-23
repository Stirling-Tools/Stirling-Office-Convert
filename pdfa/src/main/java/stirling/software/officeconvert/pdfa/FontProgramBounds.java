package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.pdfa.WebFonts.FontKind;

final class FontProgramBounds {

    static final long MAX_BYTES = 32L << 20;

    private FontProgramBounds() {}

    static void run(PDDocument doc) throws IOException {
        Set<COSStream> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSStream> failed = Collections.newSetFromMap(new IdentityHashMap<>());
        CosWalk.walk(doc, object -> {
            if (!(object instanceof COSDictionary dictionary)) {
                return;
            }
            for (COSName key : new COSName[] {COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3}) {
                if (!(dictionary.getDictionaryObject(key) instanceof COSStream stream)) {
                    continue;
                }
                if (failed.contains(stream)) {
                    dictionary.removeItem(key);
                } else if (seen.add(stream)) {
                    byte[] bytes;
                    try {
                        bytes = Decoded.bytes(stream, MAX_BYTES, "A font program");
                    } catch (IOException e) {
                        Decoded.rethrowFatal(e);
                        failed.add(stream);
                        dictionary.removeItem(key);
                        continue;
                    }
                    if (expandsTooFar(stream, key, bytes)) {
                        failed.add(stream);
                        dictionary.removeItem(key);
                        continue;
                    }
                    stream.removeItem(COSName.DECODE_PARMS);
                    try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                        out.write(bytes);
                    }
                }
            }
        });
    }

    private static boolean expandsTooFar(COSStream stream, COSName key, byte[] bytes) throws IOException {
        FontProgram program = program(stream, key, bytes);
        if (program == null) {
            return false;
        }
        try {
            program.checkExpansion();
            return false;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return true;
        }
    }

    private static FontProgram program(COSStream stream, COSName key, byte[] bytes) throws IOException {
        if (COSName.FONT_FILE2.equals(key)) {
            // This pass bounds Type 1/CFF charstrings, not TrueType glyf composites.
            return null;
        }
        FontKind kind = WebFonts.sniff(bytes);
        byte[] data = bytes;
        if (kind == FontKind.TRUETYPE) {
            data = cffTable(bytes);
            kind = data == null ? null : FontKind.CFF;
        } else if (kind == null && COSName.FONT_FILE.equals(key)) {
            kind = FontKind.TYPE1;
        }
        if (kind == null) {
            return null;
        }
        boolean type1 = kind == FontKind.TYPE1;
        try {
            return FontProgram.read(data, kind, type1 ? stream.getInt(COSName.LENGTH1) : -1,
                    type1 ? stream.getInt(COSName.LENGTH2) : -1);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] cffTable(byte[] sfnt) throws IOException {
        boolean otto = sfnt.length >= 4 && sfnt[0] == 'O' && sfnt[1] == 'T' && sfnt[2] == 'T' && sfnt[3] == 'O';
        if (!otto) {
            return null;
        }
        try (TrueTypeFont font = new OTFParser(true).parse(new RandomAccessReadBuffer(sfnt))) {
            TTFTable cff = font.getTableMap().get("CFF ");
            return cff == null ? null : font.getTableBytes(cff);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
