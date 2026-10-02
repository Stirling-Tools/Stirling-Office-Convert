package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

final class AttachedPdfA {

    static final long MAX_BYTES = 64L << 20;

    private static final int MAX_DEPTH = 2;

    private static final Pattern PART = Pattern.compile("pdfaid:part\\s*(=\\s*[\"']|>\\s*)[12]\\b");

    private static final Set<String> FORBIDDEN = Set.of("JavaScript", "Launch", "Sound", "Movie", "ResetForm",
            "ImportData", "Hide", "SetOCGState", "Rendition", "Trans", "GoTo3DView");

    private static final COSName XFA = COSName.getPDFName("XFA");

    private static final COSName EMBEDDED_FILE = COSName.getPDFName("EmbeddedFile");

    private AttachedPdfA() {}

    static boolean check(COSStream file) {
        return check(file, 0);
    }

    private static boolean check(COSStream file, int depth) {
        byte[] data;
        try {
            data = Decoded.bytes(file, MAX_BYTES, "An attachment");
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (data.length < 5 || !new String(data, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-")) {
            return false;
        }
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.isEncrypted() || !declaresPdfA(doc)) {
                return false;
            }
            boolean[] ok = {true};
            CosWalk.walk(doc, b -> ok[0] &= allowed(b, depth));
            return ok[0];
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean declaresPdfA(PDDocument doc) {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        if (!(cat.getDictionaryObject(COSName.METADATA) instanceof COSStream xmp)) {
            return false;
        }
        try {
            byte[] b = Decoded.bytes(xmp, StreamFixer.MAX_METADATA_BYTES, "XMP metadata");
            return PART.matcher(new String(b, StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean allowed(COSBase b, int depth) {
        if (!(b instanceof COSDictionary d)) {
            return true;
        }
        if (d.containsKey(COSName.JS) || d.containsKey(COSName.AA) || d.containsKey(XFA)
                || d.containsKey(COSName.ENCRYPT)) {
            return false;
        }
        if (d.getDictionaryObject(COSName.S) instanceof COSName s && FORBIDDEN.contains(s.getName())) {
            return false;
        }
        if (COSName.FONT.equals(d.getCOSName(COSName.TYPE)) && !COSName.TYPE0.equals(d.getCOSName(COSName.SUBTYPE))
                && !COSName.TYPE3.equals(d.getCOSName(COSName.SUBTYPE)) && !embedded(d)) {
            return false;
        }
        if (d instanceof COSStream s && EMBEDDED_FILE.equals(s.getCOSName(COSName.TYPE))) {
            return depth < MAX_DEPTH && check(s, depth + 1);
        }
        return true;
    }

    private static boolean embedded(COSDictionary font) {
        COSDictionary fd = ContentGraph.dict(font.getDictionaryObject(COSName.FONT_DESC));
        if (fd == null) {
            COSArray kids = ContentGraph.array(font.getDictionaryObject(COSName.DESCENDANT_FONTS));
            return kids != null;
        }
        return fd.getDictionaryObject(COSName.FONT_FILE) instanceof COSStream
                || fd.getDictionaryObject(COSName.FONT_FILE2) instanceof COSStream
                || fd.getDictionaryObject(COSName.FONT_FILE3) instanceof COSStream;
    }
}
