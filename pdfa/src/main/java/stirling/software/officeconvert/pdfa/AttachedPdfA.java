package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.extract.PdfFiles;

final class AttachedPdfA {

    static final long MAX_BYTES = 16L << 20;

    private static final int MAX_DEPTH = 2;

    private static final String IDENTIFICATION = "http://www.aiim.org/pdfa/ns/id/";

    private static final COSName EMBEDDED_FILE = COSName.getPDFName("EmbeddedFile");

    private AttachedPdfA() {}

    static boolean check(COSStream file) throws IOException {
        return check(file, false);
    }

    static boolean check(COSStream file, boolean partFour) throws IOException {
        return check(file, 0, partFour);
    }

    private static boolean check(COSStream file, int depth, boolean partFour) throws IOException {
        byte[] data;
        try {
            if (!new String(Decoded.prefix(file, 5), StandardCharsets.ISO_8859_1).equals("%PDF-")) {
                return false;
            }
            data = Decoded.bytes(file, MAX_BYTES, "An attachment");
        } catch (IOException | RuntimeException e) {
            if (e instanceof IOException io) {
                rethrowBudget(io);
            }
            PdfFiles.stopIfInterrupted();
            return false;
        }
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(data)) {
            AttachmentParser parser = new AttachmentParser(source);
            try (PDDocument doc = parser.parse(false)) {
                String profile = profile(doc, partFour);
                if (doc.isEncrypted() || profile == null) {
                    return false;
                }
                boolean[] ok = {true};
                Set<COSStream> checked = Collections.newSetFromMap(new IdentityHashMap<>());
                boolean carriesAnyFile = profile.equals("4f") || profile.equals("4e");
                CosWalk.walk(doc, b -> ok[0] &= allowed(b, depth, checked, partFour, carriesAnyFile));
                parser.checkBudget();
                FontProgramBounds.run(doc);
                ContentBudget.peakBytes(doc);
                return ok[0] && AttachmentValidation.compliant(data, profile);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IOException io) {
                rethrowBudget(io);
            }
            PdfFiles.stopIfInterrupted();
            return false;
        }
    }

    private static String profile(PDDocument doc, boolean partFour) throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        if (!(cat.getDictionaryObject(COSName.METADATA) instanceof COSStream xmp)) {
            return null;
        }
        try {
            byte[] b = Decoded.bytes(xmp, StreamFixer.MAX_METADATA_BYTES, "XMP metadata");
            metadataBudget(b);
            Document xml = XmpCarryOver.parse(b);
            if (xml == null) {
                return null;
            }
            String part = property(xml, "part");
            String conformance = property(xml, "conformance");
            boolean allowed = ("1".equals(part) && Set.of("A", "B").contains(conformance))
                    || ("2".equals(part) && Set.of("A", "B", "U").contains(conformance))
                    || (partFour && "4".equals(part) && Set.of("", "E", "F").contains(conformance));
            return allowed ? part + conformance.toLowerCase(java.util.Locale.ROOT) : null;
        } catch (IOException e) {
            rethrowBudget(e);
            return null;
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

    private static boolean allowed(COSBase b, int depth, Set<COSStream> checked, boolean partFour,
            boolean carriesAnyFile) throws IOException {
        if (!(b instanceof COSDictionary d)) {
            return true;
        }
        if (d instanceof COSStream stream && COSName.METADATA.equals(d.getCOSName(COSName.TYPE))) {
            metadataBudget(Decoded.bytes(stream, StreamFixer.MAX_METADATA_BYTES, "An attachment's XMP metadata"));
        }
        if (carriesAnyFile) {
            return true;
        }
        COSDictionary ef = ContentGraph.dict(d.getDictionaryObject(COSName.EF));
        if (ef != null) {
            for (COSName key : ef.keySet()) {
                if (!(ef.getDictionaryObject(key) instanceof COSStream file)
                        || checked.add(file) && !nested(file, depth, partFour)) {
                    return false;
                }
            }
        }
        if (d instanceof COSStream s && EMBEDDED_FILE.equals(s.getCOSName(COSName.TYPE)) && checked.add(s)) {
            return nested(s, depth, partFour);
        }
        return true;
    }

    private static boolean nested(COSStream stream, int depth, boolean partFour) throws IOException {
        if (depth >= MAX_DEPTH) {
            throw new AttachmentParser.LimitExceeded(
                    "An attachment nests PDF/A files beyond the verification depth limit");
        }
        return check(stream, depth + 1, partFour);
    }

    private static void metadataBudget(byte[] bytes) throws IOException {
        if (!XmpBounds.allows(bytes)) {
            throw new AttachmentParser.LimitExceeded("An attachment's XMP metadata cannot be verified safely");
        }
    }

    private static void rethrowBudget(IOException exception) throws IOException {
        Decoded.rethrowFatal(exception);
        if (exception instanceof AttachmentParser.LimitExceeded) {
            throw exception;
        }
    }
}
