package stirling.software.officeconvert.topdf.uof;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.w3c.dom.Element;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

public final class UofPackage {

    public enum Kind { TEXT, SPREADSHEET, PRESENTATION }

    static final long MAX_BYTES = 256L << 20;

    private UofPackage() {}

    public static Kind sniff(Path file) {
        String head;
        try {
            head = new String(SourceFile.head(file, 4096), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        int at = head.indexOf("schemas.uof.org/cn/2003/uof\"");
        if (at < 0 || !head.stripLeading().startsWith("<") && !head.startsWith("\uFEFF<")) {
            return null;
        }
        if (head.contains("vnd.uof.spreadsheet") || head.contains(":电子表格")) {
            return Kind.SPREADSHEET;
        }
        if (head.contains("vnd.uof.presentation") || head.contains(":演示文稿")) {
            return Kind.PRESENTATION;
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
