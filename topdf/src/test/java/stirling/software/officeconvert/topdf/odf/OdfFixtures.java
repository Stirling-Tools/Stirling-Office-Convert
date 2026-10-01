package stirling.software.officeconvert.topdf.odf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class OdfFixtures {

    static final String NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
            + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
            + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
            + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
            + " xmlns:number=\"urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0\""
            + " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\""
            + " xmlns:of=\"urn:oasis:names:tc:opendocument:xmlns:of:1.2\""
            + " xmlns:loext=\"urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0\"";

    static final String TEXT = "application/vnd.oasis.opendocument.text";

    static final String SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet";

    static final String PRESENTATION = "application/vnd.oasis.opendocument.presentation";

    private OdfFixtures() {}

    static String content(String automatic, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content " + NS + " office:version=\"1.3\">"
                + "<office:automatic-styles>" + automatic + "</office:automatic-styles><office:body>" + body
                + "</office:body></office:document-content>";
    }

    static String styles(String common, String automatic, String masters) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-styles " + NS + " office:version=\"1.3\">"
                + "<office:styles>" + common + "</office:styles><office:automatic-styles>" + automatic
                + "</office:automatic-styles><office:master-styles>" + masters
                + "</office:master-styles></office:document-styles>";
    }

    static String text(String body) {
        return "<office:text>" + body + "</office:text>";
    }

    static byte[] zip(String mimetype, Map<String, byte[]> parts) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (mimetype != null) {
                byte[] m = mimetype.getBytes(StandardCharsets.US_ASCII);
                ZipEntry e = new ZipEntry("mimetype");
                e.setMethod(ZipEntry.STORED);
                e.setSize(m.length);
                CRC32 crc = new CRC32();
                crc.update(m);
                e.setCrc(crc.getValue());
                zip.putNextEntry(e);
                zip.write(m);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> p : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(p.getKey()));
                zip.write(p.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] odf(String mimetype, String content, String styles) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("content.xml", content.getBytes(StandardCharsets.UTF_8));
        if (styles != null) {
            parts.put("styles.xml", styles.getBytes(StandardCharsets.UTF_8));
        }
        parts.put("META-INF/manifest.xml", ("<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:"
                + "xmlns:manifest:1.0\"><manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"" + mimetype
                + "\"/></manifest:manifest>").getBytes(StandardCharsets.UTF_8));
        return zip(mimetype, parts);
    }

    static Path write(Path dir, String name, byte[] data) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, data);
        return p;
    }

    static Map<String, String> rewrite(Path source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OdfPackage.write(source, out);
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
