package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.xps.XpsToPdf;

final class XpsInput {

    private static final String SEQUENCE_TYPE = "application/vnd.ms-package.xps-fixeddocumentsequence+xml";

    private XpsInput() {}

    static boolean named(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".xps") || n.endsWith(".oxps");
    }

    private static boolean is(Path source) throws IOException {
        if (sniff(source)) {
            return true;
        }
        if (named(source) && !OfficeToPdf.container(source)) {
            throw new UnsupportedFormatException("The file is not an XPS document: it is not a zip package");
        }
        if (named(source) && OfficeToPdf.container(source) && !officePackage(source)) {
            throw new UnsupportedFormatException("The file is not an XPS document: its package names no fixed"
                    + " document sequence");
        }
        return false;
    }

    private static boolean sniff(Path source) {
        try (ZipFile z = new ZipFile(source.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)) {
            ZipEntry rels = null;
            for (var e = z.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                String n = entry.getName().replace('\\', '/').replaceFirst("^/+", "").toLowerCase(Locale.ROOT);
                if (n.equals("_rels/.rels") || n.startsWith("_rels/.rels/[0]")) {
                    rels = entry;
                    break;
                }
            }
            if (rels == null) {
                return declaresSequence(z);
            }
            try (InputStream in = z.getInputStream(rels)) {
                String text = text(in.readNBytes(64 << 10));
                return text.contains(XpsToPdf.XPS_REPRESENTATION) || text.contains(XpsToPdf.OXPS_REPRESENTATION);
            } catch (ZipException damaged) {
                if (declaresSequence(z)) {
                    return true;
                }
                throw damaged;
            }
        } catch (ZipException e) {
            return XpsToPdf.isRepaired(source);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String text(byte[] b) {
        boolean big = b.length >= 2 && b[0] == (byte) 0xFE && b[1] == (byte) 0xFF;
        boolean little = b.length >= 2 && b[0] == (byte) 0xFF && b[1] == (byte) 0xFE;
        if (big || little) {
            return new String(b, StandardCharsets.UTF_16);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private static boolean declaresSequence(ZipFile z) {
        ZipEntry types = z.getEntry("[Content_Types].xml");
        if (types == null) {
            return false;
        }
        try (InputStream in = z.getInputStream(types)) {
            String text = text(in.readNBytes(64 << 10)).toLowerCase(Locale.ROOT);
            return text.contains(SEQUENCE_TYPE);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean officePackage(Path source) {
        try (ZipFile z = new ZipFile(source.toFile())) {
            return z.getEntry("word/document.xml") != null || z.getEntry("ppt/presentation.xml") != null
                    || z.getEntry("xl/workbook.xml") != null || z.getEntry("visio/document.xml") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static Long estimate(Path source) throws IOException {
        return is(source) ? footprint(source) : null;
    }

    private static long footprint(Path source) throws IOException {
        long page = 0;
        long fonts = 0;
        long pictures = 0;
        try (ZipFile z = new ZipFile(source.toFile())) {
            for (var e = z.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                long size = Math.max(0, entry.getSize());
                String n = entry.getName().toLowerCase(Locale.ROOT);
                if (n.contains(".fpage") || n.endsWith(".dict")) {
                    page = Math.max(page, size);
                } else if (n.endsWith("ttf") || n.endsWith("otf") || n.endsWith("ttc")) {
                    fonts += size;
                } else if (!n.endsWith(".rels") && !n.endsWith(".xml") && !n.endsWith(".fdoc")) {
                    pictures = Math.max(pictures, size);
                }
            }
        } catch (IOException | RuntimeException e) {
            return 2 * Admission.BASE_BYTES + Files.size(source) * 8;
        }
        long decode = Math.min(Math.max(pictures * 16, 0), 4 * PictureDecoder.DECODE_PIXELS);
        long total = 2 * Admission.BASE_BYTES + page * 12 + Math.min(fonts, XpsToPdf.MAX_ALL_FONT_BYTES) * 3 + decode;
        return total < 0 ? Long.MAX_VALUE : total;
    }

    static Result render(Path source, OutputStream sink, Options options) throws IOException {
        if (!is(source)) {
            return null;
        }
        try (Admission.Ticket _ = Admission.jvm().enter(footprint(source));
                PdfOutput output = new PdfOutput(options.fontLibrary(), options.maxScratchBytes())) {
            XpsToPdf.Outcome outcome = XpsToPdf.convert(source, output, options.fontLibrary(), options.maxPages());
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
            List<String> warnings = new ArrayList<>();
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(outcome.pages(), outcome.lost(), warnings, outcome.limited());
        }
    }
}
