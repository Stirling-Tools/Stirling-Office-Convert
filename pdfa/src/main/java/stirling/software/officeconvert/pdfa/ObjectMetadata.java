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

import stirling.software.officeconvert.extract.PdfFiles;

final class ObjectMetadata {

    private static final String HEADER_START = "<?xpacket";

    private static final int MAX_HEADER_CHARS = 1024;

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
        if (level.part() == 2 || level.part() == 3) {
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
            PdfFiles.stopIfInterrupted();
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
        int[] at = header(text);
        if (at == null) {
            return true;
        }
        if (at[1] - at[0] > MAX_HEADER_CHARS) {
            return false;
        }
        String header = text.substring(at[0], at[1]);
        String stripped = withoutSizeAttributes(header);
        if (!stripped.equals(header)) {
            String fixed = text.substring(0, at[0]) + stripped + text.substring(at[1]);
            s.removeItem(COSName.DECODE_PARMS);
            try (OutputStream out = s.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(fixed.getBytes(StandardCharsets.UTF_8));
            }
        }
        return true;
    }

    static int[] header(String text) {
        int from = 0;
        while (true) {
            int start = text.indexOf(HEADER_START, from);
            if (start < 0) {
                return null;
            }
            int mark = text.indexOf('?', start + HEADER_START.length());
            if (mark < 0) {
                return null;
            }
            if (mark + 1 < text.length() && text.charAt(mark + 1) == '>') {
                return new int[] {start, mark + 2};
            }
            from = start + 1;
        }
    }

    static String withoutSizeAttributes(String header) {
        StringBuilder out = new StringBuilder(header.length());
        int i = 0;
        int n = header.length();
        while (i < n) {
            if (!space(header.charAt(i))) {
                out.append(header.charAt(i++));
                continue;
            }
            int run = i;
            while (run < n && space(header.charAt(run))) {
                run++;
            }
            int end = sizeAttributeEnd(header, run);
            if (end < 0) {
                out.append(header, i, run);
                i = run;
            } else {
                i = end;
            }
        }
        return out.toString();
    }

    private static int sizeAttributeEnd(String s, int at) {
        int i;
        if (s.startsWith("bytes", at)) {
            i = at + 5;
        } else if (s.startsWith("encoding", at)) {
            i = at + 8;
        } else {
            return -1;
        }
        i = skipSpaces(s, i);
        if (i >= s.length() || s.charAt(i) != '=') {
            return -1;
        }
        i = skipSpaces(s, i + 1);
        if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) {
            return -1;
        }
        int close = s.indexOf(s.charAt(i), i + 1);
        return close < 0 ? -1 : close + 1;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && space(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean space(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
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
