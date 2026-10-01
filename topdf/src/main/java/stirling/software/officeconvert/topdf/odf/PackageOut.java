package stirling.software.officeconvert.topdf.odf;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class PackageOut {

    static final long MAX_TOTAL_BYTES = 960L << 20;

    private final ZipOutputStream zip;

    private final Map<String, String> overrides = new LinkedHashMap<>();

    private final Map<String, String> media = new LinkedHashMap<>();

    private int mediaCount;

    private long total;

    PackageOut(OutputStream out) {
        this.zip = new ZipOutputStream(new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                out.flush();
            }
        });
        zip.setLevel(Deflater.BEST_SPEED);
    }

    void xml(String name, String contentType, CharSequence xml) throws IOException {
        byte[] data = xml.toString().getBytes(StandardCharsets.UTF_8);
        put(name, data);
        if (contentType != null) {
            overrides.put("/" + name, contentType);
        }
    }

    /** Stores a picture once per distinct content and returns its part name, or null when it is not a picture. */
    String picture(String dir, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return null;
        }
        String ext = switch (PictureDecoder.sniff(data)) {
            case PNG -> "png";
            case JPEG -> "jpeg";
            case GIF -> "gif";
            case BMP -> "bmp";
            case TIFF -> "tiff";
            case EMF -> "emf";
            case WMF -> "wmf";
            case SVG -> "svg";
            default -> null;
        };
        if (ext == null) {
            return null;
        }
        String key = ext + ":" + data.length + ":" + java.util.Arrays.hashCode(data);
        String existing = media.get(key);
        if (existing != null) {
            return existing;
        }
        String name = dir + "image" + (++mediaCount) + "." + ext;
        put(name, data);
        media.put(key, name);
        return name;
    }

    private int charts;

    /** Stores a chart part and returns its name. */
    String chart(String dir, String xml) throws IOException {
        String name = dir + "chart" + (++charts) + ".xml";
        xml(name, Xml.CT + "drawingml.chart+xml", xml);
        return name;
    }

    private void put(String name, byte[] data) throws IOException {
        total += data.length;
        if (total > MAX_TOTAL_BYTES) {
            throw new OfficeZip.Oversized("The document is too large to convert");
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    void finish() throws IOException {
        StringBuilder ct = new StringBuilder(Xml.HEAD).append(
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Default Extension=\"gif\" ContentType=\"image/gif\"/>"
                + "<Default Extension=\"bmp\" ContentType=\"image/bmp\"/>"
                + "<Default Extension=\"tiff\" ContentType=\"image/tiff\"/>"
                + "<Default Extension=\"emf\" ContentType=\"image/x-emf\"/>"
                + "<Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/>"
                + "<Default Extension=\"svg\" ContentType=\"image/svg+xml\"/>");
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            ct.append("<Override PartName=\"").append(Xml.esc(e.getKey())).append("\" ContentType=\"")
                    .append(e.getValue()).append("\"/>");
        }
        ct.append("</Types>");
        put("[Content_Types].xml", ct.toString().getBytes(StandardCharsets.UTF_8));
        zip.finish();
        zip.flush();
    }
}
