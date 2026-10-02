package stirling.software.officeconvert.topdf.ooo1;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.w3c.dom.Document;

import stirling.software.officeconvert.topdf.io.BoundedZip;
import stirling.software.officeconvert.topdf.io.SecureXml;
import stirling.software.officeconvert.topdf.io.SourceFile;

/** An OpenOffice.org 1.x / StarOffice 6-7 document (.sxw, .sxc, .sxi, .sxd and their templates) rewritten as the
 * OpenDocument package its successor format defines; embedded objects are left out. */
public final class Ooo1Package {

    /** The OpenDocument file extension the document becomes. */
    public enum Kind { TEXT, SPREADSHEET, PRESENTATION, DRAWING }

    private static final long MAX_XML_BYTES = 128L << 20;

    private static final long MAX_PICTURE_BYTES = 64L << 20;

    private Ooo1Package() {}

    public static Kind sniff(Path file) {
        try {
            byte[] head = SourceFile.head(file, 2);
            if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        try (BoundedZip zip = BoundedZip.open(file)) {
            ZipEntry m = zip.entry("mimetype");
            if (m == null || m.getSize() > 256) {
                return null;
            }
            String type;
            try (InputStream in = zip.open(m, 256)) {
                type = new String(in.readNBytes(256), StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
            }
            if (!type.startsWith("application/vnd.sun.xml.")) {
                return null;
            }
            String k = type.substring("application/vnd.sun.xml.".length());
            if (k.startsWith("writer") && !k.startsWith("writer.web")) {
                return Kind.TEXT;
            }
            if (k.startsWith("calc")) {
                return Kind.SPREADSHEET;
            }
            if (k.startsWith("impress")) {
                return Kind.PRESENTATION;
            }
            if (k.startsWith("draw")) {
                return Kind.DRAWING;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static long estimate(Path file) throws IOException {
        long xml = BoundedZip.inflatedSize(file, List.of("content.xml", "styles.xml"), MAX_XML_BYTES);
        return (64L << 20) + xml * 10 + SourceFile.size(file) * 2;
    }

    public static String extension(Kind kind) {
        return switch (kind) {
            case TEXT -> "odt";
            case SPREADSHEET -> "ods";
            case PRESENTATION -> "odp";
            case DRAWING -> "odg";
        };
    }

    public static void write(Path source, Kind kind, OutputStream out) throws IOException {
        String body = switch (kind) {
            case TEXT -> "text";
            case SPREADSHEET -> "spreadsheet";
            case PRESENTATION -> "presentation";
            case DRAWING -> "drawing";
        };
        String mime = "application/vnd.oasis.opendocument." + switch (kind) {
            case TEXT -> "text";
            case SPREADSHEET -> "spreadsheet";
            case PRESENTATION -> "presentation";
            case DRAWING -> "graphics";
        };
        try (BoundedZip zip = BoundedZip.open(source); ZipOutputStream z = new ZipOutputStream(new KeepOpen(out))) {
            zip.checkEntries();
            byte[] m = mime.getBytes(StandardCharsets.US_ASCII);
            ZipEntry me = new ZipEntry("mimetype");
            me.setMethod(ZipEntry.STORED);
            me.setSize(m.length);
            CRC32 crc = new CRC32();
            crc.update(m);
            me.setCrc(crc.getValue());
            z.putNextEntry(me);
            z.write(m);
            z.closeEntry();
            for (String part : new String[] {"content.xml", "styles.xml", "meta.xml"}) {
                ZipEntry e = zip.entry(part);
                if (e == null) {
                    continue;
                }
                Document doc = SecureXml.parse(new java.io.ByteArrayInputStream(withoutDoctype(zip.read(e,
                        MAX_XML_BYTES))));
                Upgrade.apply(doc, part.equals("content.xml") ? body : null);
                z.putNextEntry(new ZipEntry(part));
                z.write(XmlOut.write(doc).getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            for (ZipEntry e : zip.entries()) {
                String n = e.getName();
                boolean media = n.startsWith("Pictures/") || n.startsWith("ObjectReplacements/");
                if (e.isDirectory() || !media || n.contains("..") || e.getSize() > MAX_PICTURE_BYTES) {
                    continue;
                }
                byte[] data = zip.read(e, MAX_PICTURE_BYTES);
                z.putNextEntry(new ZipEntry(n));
                z.write(data);
                z.closeEntry();
            }
            z.finish();
        }
    }

    private static final java.util.regex.Pattern DOCTYPE = java.util.regex.Pattern.compile(
            "<!DOCTYPE\\s+[\\w:.-]+\\s+PUBLIC\\s+\"-//OpenOffice\\.org//DTD OfficeDocument 1\\.0//EN\"\\s+\"[^\"<>\\[\\]]*\"\\s*>");

    static byte[] withoutDoctype(byte[] xml) {
        int scan = Math.min(xml.length, 4096);
        String head = new String(xml, 0, scan, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = DOCTYPE.matcher(head);
        if (!m.find() || head.lastIndexOf('<', m.start() - 1) > head.indexOf("?>")) {
            return xml;
        }
        byte[] out = new byte[xml.length - (m.end() - m.start())];
        System.arraycopy(xml, 0, out, 0, m.start());
        System.arraycopy(xml, m.end(), out, m.start(), xml.length - m.end());
        return out;
    }

    private static final class KeepOpen extends java.io.FilterOutputStream {
        KeepOpen(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
