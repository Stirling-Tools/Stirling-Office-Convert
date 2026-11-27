package stirling.software.officeconvert.topdf.uof;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Element;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

public final class UofPackage {

    public enum Kind { TEXT, SPREADSHEET, PRESENTATION }

    static final long MAX_BYTES = 256L << 20;

    private UofPackage() {}

    public static Kind sniff(Path file) {
        byte[] head;
        try {
            head = SourceFile.head(file, 16384);
        } catch (IOException e) {
            return null;
        }
        if (!markup(head) || !declares(head)) {
            return null;
        }
        try {
            XMLStreamReader r = SecureXml.reader(new ByteArrayInputStream(head));
            while (r.hasNext() && r.next() != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            if (!r.isStartElement() || !Uof.NS.equals(r.getNamespaceURI()) || !"UOF".equals(r.getLocalName())) {
                return null;
            }
            Kind kind = mimetype(r);
            if (kind != null) {
                return kind;
            }
            return body(r);
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return null;
        }
    }

    private static boolean declares(byte[] b) {
        String ns = "schemas.uof.org/cn/2003/uof";
        for (Charset c : new Charset[] {StandardCharsets.ISO_8859_1,
                StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            if (new String(b, c).contains(ns)) {
                return true;
            }
        }
        return false;
    }

    private static boolean markup(byte[] b) {
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF || (b[0] & 0xFF) == 0xFF
                && (b[1] & 0xFF) == 0xFE || b[0] == 0 && b[1] == '<' || b[0] == '<' && b[1] == 0)) {
            return true;
        }
        int i = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        while (i < b.length && (b[i] == ' ' || b[i] == '\t' || b[i] == '\r' || b[i] == '\n')) {
            i++;
        }
        return i < b.length && b[i] == '<';
    }

    private static Kind mimetype(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (!"mimetype".equals(r.getAttributeLocalName(i))) {
                continue;
            }
            String v = r.getAttributeValue(i);
            if (v.contains("vnd.uof.spreadsheet")) {
                return Kind.SPREADSHEET;
            }
            if (v.contains("vnd.uof.presentation")) {
                return Kind.PRESENTATION;
            }
        }
        return null;
    }

    private static Kind body(XMLStreamReader r) {
        try {
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "电子表格" -> {
                        return Kind.SPREADSHEET;
                    }
                    case "演示文稿" -> {
                        return Kind.PRESENTATION;
                    }
                    case "文字处理" -> {
                        return Kind.TEXT;
                    }
                    default -> {}
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            return Kind.TEXT;
        }
        return Kind.TEXT;
    }

    public static long estimate(Path file) throws IOException {
        return (64L << 20) + SourceFile.size(file) * 12;
    }

    public static String extension(Kind kind) {
        return switch (kind) {
            case TEXT -> "docx";
            case SPREADSHEET -> "xlsx";
            case PRESENTATION -> "pptx";
        };
    }

    public static void write(Path source, Kind kind, OutputStream out) throws IOException {
        if (Files.size(source) > MAX_BYTES) {
            throw new IOException("The UOF document is too large: it is over " + (MAX_BYTES >> 20) + " MB");
        }
        Element root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source), 1 << 16)) {
            root = SecureXml.parse(in).getDocumentElement();
        }
        Kind actual = kind(root, kind);
        try (UofParts parts = new UofParts(out)) {
            parts.limit(Files.size(source));
            Element meta = Uof.kid(root, "元数据");
            parts.info(Uof.text(Uof.kid(meta, "标题")), Uof.text(Uof.kid(meta, "创建者")));
            switch (actual) {
                case TEXT -> UofText.write(root, parts);
                case SPREADSHEET -> UofSheets.write(root, parts);
                case PRESENTATION -> UofSlides.write(root, parts);
            }
        }
    }

    static Kind kind(Element root, Kind fallback) throws IOException {
        if (!Uof.NS.equals(root.getNamespaceURI()) || !"UOF".equals(Uof.name(root))) {
            throw new UnsupportedFormatException("The file is not a UOF document");
        }
        if (Uof.kid(root, "文字处理") != null) {
            return Kind.TEXT;
        }
        if (Uof.kid(root, "电子表格") != null) {
            return Kind.SPREADSHEET;
        }
        if (Uof.kid(root, "演示文稿") != null) {
            return Kind.PRESENTATION;
        }
        return fallback;
    }
}
