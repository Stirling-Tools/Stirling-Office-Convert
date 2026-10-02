package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class ObjectMetadata {

    private static final Pattern HEADER = Pattern.compile("<\\?xpacket[^?]*\\?>");

    private static final Pattern FORBIDDEN = Pattern.compile("\\s+(bytes|encoding)\\s*=\\s*(\"[^\"]*\"|'[^']*')");

    private ObjectMetadata() {}

    static void run(PDDocument doc, Census census, PdfALevel level, Report report) throws IOException {
        COSDictionary cat = doc.getDocumentCatalog().getCOSObject();
        COSStream main = cat.getDictionaryObject(COSName.METADATA) instanceof COSStream s ? s : null;
        List<COSDictionary> owners = new ArrayList<>();
        for (COSDictionary d : census.withMetadata) {
            if (d.getDictionaryObject(COSName.METADATA) instanceof COSStream s && s != main) {
                owners.add(d);
            }
        }
        if (level.part() > 1) {
            for (COSDictionary d : owners) {
                d.removeItem(COSName.METADATA);
            }
            if (!owners.isEmpty()) {
                report.warn("Removed metadata of pages, images and fonts, as PDF/A-2 and 3 allow only predefined XMP "
                        + "properties there");
            }
            return;
        }
        Map<COSStream, Boolean> verdicts = new IdentityHashMap<>();
        boolean removed = false;
        for (COSDictionary d : owners) {
            COSStream s = (COSStream) d.getDictionaryObject(COSName.METADATA);
            Boolean ok = verdicts.get(s);
            if (ok == null) {
                ok = repair(s);
                verdicts.put(s, ok);
            }
            if (!ok) {
                d.removeItem(COSName.METADATA);
                removed = true;
            }
        }
        if (removed) {
            report.warn("Removed metadata of pages, images or fonts that is not well-formed UTF-8 XMP");
        }
    }

    private static boolean repair(COSStream s) throws IOException {
        byte[] data = StreamFixer.read(s, StreamFixer.MAX_METADATA_BYTES);
        if (data == null) {
            return false;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        if (!wellFormed(data)) {
            return false;
        }
        Matcher m = HEADER.matcher(text);
        if (m.find() && FORBIDDEN.matcher(m.group()).find()) {
            String header = FORBIDDEN.matcher(m.group()).replaceAll("");
            String fixed = text.substring(0, m.start()) + header + text.substring(m.end());
            s.removeItem(COSName.DECODE_PARMS);
            try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(fixed.getBytes(StandardCharsets.UTF_8));
            }
        }
        return true;
    }

    private static boolean wellFormed(byte[] data) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.parse(new ByteArrayInputStream(data));
            return true;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            return false;
        }
    }
}
