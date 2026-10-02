package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class AttachedPdfA {

    static final long MAX_BYTES = 16L << 20;

    private static final int MAX_DEPTH = 2;

    private static final String IDENTIFICATION = "http://www.aiim.org/pdfa/ns/id/";

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
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(data)) {
            AttachmentParser parser = new AttachmentParser(source);
            try (PDDocument doc = parser.parse(false)) {
                if (doc.isEncrypted() || !declaresPdfA(doc)) {
                    return false;
                }
                boolean[] ok = {true};
                Set<COSStream> checked = Collections.newSetFromMap(new IdentityHashMap<>());
                CosWalk.walk(doc, b -> ok[0] &= allowed(b, depth, checked));
                parser.checkBudget();
                return ok[0];
            }
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
            Document xml = XmpCarryOver.parse(b);
            if (xml == null) {
                return false;
            }
            String part = property(xml, "part");
            String conformance = property(xml, "conformance");
            return ("1".equals(part) && Set.of("A", "B").contains(conformance))
                    || ("2".equals(part) && Set.of("A", "B", "U").contains(conformance));
        } catch (IOException e) {
            return false;
        }
    }

    private static String property(Document xml, String name) {
        String value = "";
        int count = 0;
        NodeList elements = xml.getElementsByTagNameNS(IDENTIFICATION, name);
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getElementsByTagName("*").getLength() != 0) {
                return "";
            }
            value = element.getTextContent().strip();
            count++;
        }
        NodeList descriptions = xml.getElementsByTagNameNS(XmpCarryOver.RDF, "Description");
        for (int i = 0; i < descriptions.getLength(); i++) {
            Element description = (Element) descriptions.item(i);
            if (description.hasAttributeNS(IDENTIFICATION, name)) {
                value = description.getAttributeNS(IDENTIFICATION, name).strip();
                count++;
            }
        }
        return count == 1 ? value : "";
    }

    private static boolean allowed(COSBase b, int depth, Set<COSStream> checked) {
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
        COSDictionary ef = ContentGraph.dict(d.getDictionaryObject(COSName.EF));
        if (ef != null) {
            for (COSName key : ef.keySet()) {
                if (!(ef.getDictionaryObject(key) instanceof COSStream file)
                        || checked.add(file) && !(depth < MAX_DEPTH && check(file, depth + 1))) {
                    return false;
                }
            }
        }
        if (d instanceof COSStream s && EMBEDDED_FILE.equals(s.getCOSName(COSName.TYPE)) && checked.add(s)) {
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
