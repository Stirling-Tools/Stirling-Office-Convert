package stirling.software.officeconvert.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import stirling.software.officeconvert.topdf.io.LegacyOffice;
import stirling.software.officeconvert.topdf.rtf.RtfPackage;
import stirling.software.officeconvert.topdf.odf.OdfDocument;
import stirling.software.officeconvert.topdf.odf.OdfPackage;

final class OfficeFiles {

    private record Kind(String contentType, String extension) {}

    private static final String OOXML = "application/vnd.openxmlformats-officedocument.";

    private static final List<Kind> KINDS = List.of(
            new Kind(OOXML + "wordprocessingml.document.main+xml", "docx"),
            new Kind("application/vnd.ms-word.document.macroEnabled.main+xml", "docm"),
            new Kind(OOXML + "wordprocessingml.template.main+xml", "dotx"),
            new Kind("application/vnd.ms-word.template.macroEnabledTemplate.main+xml", "dotm"),
            new Kind(OOXML + "presentationml.presentation.main+xml", "pptx"),
            new Kind("application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml", "pptm"),
            new Kind(OOXML + "presentationml.slideshow.main+xml", "ppsx"),
            new Kind("application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml", "ppsm"),
            new Kind(OOXML + "presentationml.template.main+xml", "potx"),
            new Kind("application/vnd.ms-powerpoint.template.macroEnabled.main+xml", "potm"),
            new Kind(OOXML + "spreadsheetml.sheet.main+xml", "xlsx"),
            new Kind("application/vnd.ms-excel.sheet.macroEnabled.main+xml", "xlsm"),
            new Kind(OOXML + "spreadsheetml.template.main+xml", "xltx"),
            new Kind("application/vnd.ms-excel.template.macroEnabled.main+xml", "xltm"));

    private static final Map<String, String> MAIN_PARTS = Map.of(
            "word/document.xml", "docx", "ppt/presentation.xml", "pptx", "xl/workbook.xml", "xlsx");

    private static final int MAX_TYPES_BYTES = 1 << 20;

    static final String NOT_OFFICE = "That file is not a PDF or a Word, PowerPoint or Excel document.";

    static final String LEGACY = "Only Word, PowerPoint and Excel 97-2003 files convert from the binary formats, and"
            + " password protected Office files are not supported. Save the file as .docx, .pptx or .xlsx without a"
            + " password.";

    /** A file this demo does not convert at all, as opposed to a damaged one. */
    static final class Unsupported extends IOException {
        Unsupported(String message) {
            super(message);
        }
    }

    private OfficeFiles() {}

    static String family(String extension) {
        return switch (extension) {
            case "docx", "docm", "dotx", "dotm", "doc", "dot", "rtf", "odt" -> "docx";
            case "pptx", "pptm", "ppsx", "ppsm", "potx", "potm", "ppt", "pps", "pot", "odp" -> "pptx";
            case "xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods" -> "xlsx";
            default -> throw new IllegalArgumentException("Not an Office extension: " + extension);
        };
    }

    static String extension(Path zip) throws IOException {
        if (RtfPackage.isRtf(zip)) {
            return "rtf";
        }
        OdfDocument.Kind odf = OdfPackage.sniff(zip);
        if (odf != null) {
            return switch (odf) {
                case TEXT -> "odt";
                case SPREADSHEET -> "ods";
                case PRESENTATION -> "odp";
            };
        }
        if (ole2(zip)) {
            String kind = LegacyOffice.kind(zip);
            if ("xls".equals(kind) || "ppt".equals(kind) || "doc".equals(kind)) {
                return kind;
            }
            throw new Unsupported(LEGACY);
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry mimetype = file.getEntry("mimetype");
            if (mimetype != null && read(file, mimetype).startsWith("application/vnd.oasis.opendocument")) {
                throw new IOException("OpenDocument drawings, charts and formulas are not supported; text documents,"
                        + " spreadsheets and presentations are.");
            }
            ZipEntry types = file.getEntry("[Content_Types].xml");
            String xml = types == null ? "" : read(file, types);
            for (Kind k : KINDS) {
                if (xml.contains(k.contentType())) {
                    return k.extension();
                }
            }
            for (Map.Entry<String, String> part : MAIN_PARTS.entrySet()) {
                if (file.getEntry(part.getKey()) != null) {
                    return part.getValue();
                }
            }
            throw new IOException("This zip file holds no Word, PowerPoint or Excel document.");
        } catch (ZipException e) {
            throw new IOException("This document is damaged: it is not a readable Office package.", e);
        }
    }

    private static boolean ole2(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            return head.length == 4 && (head[0] & 0xff) == 0xd0 && (head[1] & 0xff) == 0xcf && head[2] == 0x11
                    && (head[3] & 0xff) == 0xe0;
        }
    }

    private static String read(ZipFile file, ZipEntry entry) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            return new String(in.readNBytes(MAX_TYPES_BYTES), StandardCharsets.UTF_8);
        }
    }
}
