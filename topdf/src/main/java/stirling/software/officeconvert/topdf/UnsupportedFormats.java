package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.io.LegacyOffice;

/** The plain reason for each kind of file the converter does not read, by its extension or, when the name gives
 * nothing away, by its content. */
public final class UnsupportedFormats {

    private UnsupportedFormats() {}

    /** The reason a file with this extension is not converted, or null when it is not a known kind. */
    public static String byExtension(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "wps" -> works();
            case "wpd", "wp", "wp5", "wp6", "wp7", "wpt" -> wordPerfect();
            case "pub" -> publisher();
            case "vsd", "vdx", "vss", "vst", "vsw", "vsx", "vtx" -> visio();
            case "wk1", "wk3", "wk4", "wks", "123", "wk!" -> lotus();
            case "wb1", "wb2", "wb3", "qpw", "wq1", "wq2" -> "Quattro Pro spreadsheets are not supported; save the file"
                    + " as .xlsx";
            case "lwp" -> "Lotus Word Pro documents (.lwp) are not supported; save the file as .docx";
            case "sdw", "sdc", "sdd", "sda", "sds", "sgl", "smf", "sdp" -> starOffice();
            case "cwk", "cws" -> "AppleWorks and ClarisWorks documents are not supported; save the file as .docx or"
                    + " .xlsx";
            case "wri" -> "Windows Write documents (.wri) are not supported; save the file as .docx";
            case "hwp", "hwpx" -> "Hangul word processor documents are not supported; save the file as .docx";
            case "xlw" -> "Excel 4.0 workbooks (.xlw) are not supported; save the file as .xlsx";
            case "xlr" -> "Microsoft Works spreadsheets (.xlr) are not supported; save the file as .xlsx";
            case "mpp", "mpt" -> "Microsoft Project files are not supported; save the plan as PDF from Project";
            case "one", "onepkg" -> "OneNote notebooks are not supported; export the pages as PDF from OneNote";
            case "mht", "mhtml" -> "MHTML web archives are not supported; save the page as HTML or PDF";
            case "htm", "html", "xhtml" -> "HTML pages are not Office documents; convert them with an HTML to PDF tool";
            case "eml", "msg" -> "E-mail messages are not Office documents; convert them with an e-mail to PDF tool";
            case "odf", "mml" -> "OpenDocument formulas on their own are not supported; place the formula in a"
                    + " document";
            case "odb" -> "OpenDocument databases are not supported";
            case "odc", "odi", "otc", "oti" -> "OpenDocument charts and images on their own are not supported";
            default -> null;
        };
    }

    /** The reason a file is not converted, read from its first bytes and, for an OLE2 file, its streams; null when
     * the content is not a known kind. */
    public static String byContent(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(512);
        } catch (IOException e) {
            return null;
        }
        if (head.length >= 4 && (head[0] & 0xFF) == 0xFF && head[1] == 'W' && head[2] == 'P' && head[3] == 'C') {
            return wordPerfect();
        }
        if (head.length >= 4 && head[0] == 0 && head[1] == 0 && (head[2] == 2 || head[2] == 0x1A) && head[3] == 0) {
            return lotus();
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0x31 && (head[1] & 0xFF) == 0xBE
                || head.length >= 2 && (head[0] & 0xFF) == 0x32 && (head[1] & 0xFF) == 0xBE) {
            return "The file is a Windows Write document, which is not supported; save it as .docx";
        }
        String text = new String(head, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        if (text.startsWith("mime-version:") && text.contains("multipart/related")) {
            return byExtension("mht");
        }
        if (text.startsWith("<!doctype html") || text.startsWith("<html")) {
            return "The file is an HTML page, not an Office document; convert it with an HTML to PDF tool";
        }
        try {
            if (!LegacyOffice.ole2(file)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntryCaseInsensitive("Quill")) {
                return publisher();
            }
            if (root.hasEntryCaseInsensitive("VisioDocument")) {
                return visio();
            }
            if (root.hasEntryCaseInsensitive("MatOST") || root.hasEntryCaseInsensitive("CONTENTS")
                    && root.hasEntryCaseInsensitive("SPELLING")) {
                return works();
            }
            if (root.hasEntryCaseInsensitive("StarWriterDocument") || root.hasEntryCaseInsensitive("StarCalcDocument")
                    || root.hasEntryCaseInsensitive("StarDrawDocument3")
                    || root.hasEntryCaseInsensitive("StarImpressDocument")) {
                return starOffice();
            }
            if (root.hasEntryCaseInsensitive("__substg1.0_0037001F") || root.hasEntryCaseInsensitive("__nameid_version1.0")) {
                return byExtension("msg");
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String works() {
        return "Microsoft Works documents (.wps) are not supported; save the file as .docx from Works or Word";
    }

    private static String wordPerfect() {
        return "WordPerfect documents are not supported; save the file as .docx";
    }

    private static String publisher() {
        return "Microsoft Publisher files (.pub) are not supported; save the publication as PDF from Publisher";
    }

    private static String visio() {
        return "Visio 2003-2010 drawings (.vsd, .vdx) are not supported; save the drawing as .vsdx or as PDF from Visio";
    }

    private static String lotus() {
        return "Lotus 1-2-3 and Microsoft Works spreadsheets are not supported; save the file as .xlsx";
    }

    private static String starOffice() {
        return "StarOffice 5 and older documents are not supported; save the file in an OpenDocument or Office format";
    }
}
