package stirling.software.officeconvert.topdf.xps;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class XpsImages {

    static final long MAX_IMAGE_BYTES = 128L << 20;

    static final int MAX_IMAGES = 20_000;

    record Image(DecodedPicture picture, double width, double height) {}

    private final PDDocument document;

    private final XpsPackage pkg;

    private final Map<String, Image> decoded = new HashMap<>();

    private final Map<String, String> failed = new HashMap<>();

    private int hdPhotos;

    private int unreadable;

    XpsImages(PDDocument document, XpsPackage pkg) {
        this.document = document;
        this.pkg = pkg;
    }

    int hdPhotos() {
        return hdPhotos;
    }

    int unreadable() {
        return unreadable;
    }

    static String source(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.startsWith("{") && v.endsWith("}")) {
            String inner = v.substring(1, v.length() - 1).strip();
            if (inner.startsWith("ColorConvertedBitmap")) {
                String[] parts = inner.substring("ColorConvertedBitmap".length()).strip().split("\\s+");
                return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
            }
            return null;
        }
        return v;
    }

    Image image(String part) throws IOException {
        String key = part.toLowerCase(Locale.ROOT);
        Image known = decoded.get(key);
        if (known != null) {
            return known;
        }
        if (failed.containsKey(key)) {
            return null;
        }
        try {
            if (decoded.size() >= MAX_IMAGES) {
                throw new IOException("the document has more than " + MAX_IMAGES + " pictures");
            }
            byte[] data = pkg.read(part, MAX_IMAGE_BYTES);
            String type = pkg.contentType(part);
            if (hdPhoto(data, type, part)) {
                hdPhotos++;
                failed.put(key, "HD Photo");
                return null;
            }
            if (PictureDecoder.sniff(data) == PictureDecoder.Kind.JPEG && !jpegIntact(data)) {
                throw new IOException("the JPEG picture is damaged");
            }
            DecodedPicture picture = PictureDecoder.decode(document, data);
            if (picture.vector()) {
                throw new IOException("the picture is not a raster image");
            }
            double[] dpi = dpi(data);
            double w = picture.pixelWidth() * 96.0 / dpi[0];
            double h = picture.pixelHeight() * 96.0 / dpi[1];
            Image made = new Image(picture, w, h);
            decoded.put(key, made);
            return made;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (XpsToPdf.fatal(e)) {
                throw e;
            }
            unreadable++;
            failed.put(key, String.valueOf(e.getMessage()));
            return null;
        }
    }

    static boolean jpegIntact(byte[] d) {
        int p = 2;
        boolean frame = false;
        while (p + 4 <= d.length) {
            if ((d[p] & 0xFF) != 0xFF) {
                return false;
            }
            int marker = d[p + 1] & 0xFF;
            if (marker == 0xFF) {
                p++;
                continue;
            }
            if (marker == 0xD8 || marker >= 0xD0 && marker <= 0xD7 || marker == 0x01) {
                p += 2;
                continue;
            }
            if (marker == 0xDA) {
                return frame;
            }
            if (marker == 0xD9) {
                return false;
            }
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                frame = true;
            }
            int len = (d[p + 2] & 0xFF) << 8 | d[p + 3] & 0xFF;
            if (len < 2) {
                return false;
            }
            p += 2 + len;
        }
        return false;
    }

    private static boolean hdPhoto(byte[] data, String type, String part) {
        if (type != null && type.toLowerCase(Locale.ROOT).contains("ms-photo")) {
            return true;
        }
        if (data.length >= 4 && data[0] == 'I' && data[1] == 'I' && (data[2] & 0xFF) == 0xBC) {
            return true;
        }
        String p = part.toLowerCase(Locale.ROOT);
        return p.endsWith(".wdp") || p.endsWith(".hdp") || p.endsWith(".jxr");
    }

    static double[] dpi(byte[] d) {
        double[] out = {96, 96};
        try {
            if (d.length > 20 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
                jpeg(d, out);
            } else if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P') {
                png(d, out);
            } else if (d.length > 8 && (d[0] == 'I' && d[1] == 'I' || d[0] == 'M' && d[1] == 'M')) {
                tiff(d, out);
            }
        } catch (RuntimeException e) {
            out[0] = 96;
            out[1] = 96;
        }
        if (!(out[0] >= 1 && out[0] <= 100_000)) {
            out[0] = 96;
        }
        if (!(out[1] >= 1 && out[1] <= 100_000)) {
            out[1] = 96;
        }
        return out;
    }

    private static void jpeg(byte[] d, double[] out) {
        int p = 2;
        while (p + 4 < d.length) {
            if ((d[p] & 0xFF) != 0xFF) {
                return;
            }
            int marker = d[p + 1] & 0xFF;
            int len = (d[p + 2] & 0xFF) << 8 | d[p + 3] & 0xFF;
            if (marker == 0xE0 && len >= 14 && p + 4 + 12 <= d.length && d[p + 4] == 'J' && d[p + 5] == 'F'
                    && d[p + 6] == 'I' && d[p + 7] == 'F') {
                int units = d[p + 11] & 0xFF;
                int x = (d[p + 12] & 0xFF) << 8 | d[p + 13] & 0xFF;
                int y = (d[p + 14] & 0xFF) << 8 | d[p + 15] & 0xFF;
                if (x > 0 && y > 0) {
                    if (units == 1) {
                        out[0] = x;
                        out[1] = y;
                    } else if (units == 2) {
                        out[0] = x * 2.54;
                        out[1] = y * 2.54;
                    }
                }
                return;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return;
            }
            p += 2 + len;
        }
    }

    private static void png(byte[] d, double[] out) {
        int p = 8;
        while (p + 12 <= d.length) {
            int len = be32(d, p);
            String type = new String(d, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (len < 0 || p + 12 + (long) len > d.length) {
                return;
            }
            if (type.equals("pHYs") && len >= 9) {
                long x = be32(d, p + 8) & 0xFFFFFFFFL;
                long y = be32(d, p + 12) & 0xFFFFFFFFL;
                if (d[p + 16] == 1 && x > 0 && y > 0) {
                    out[0] = x * 0.0254;
                    out[1] = y * 0.0254;
                }
                return;
            }
            if (type.equals("IDAT")) {
                return;
            }
            p += 12 + len;
        }
    }

    private static void tiff(byte[] d, double[] out) {
        boolean le = d[0] == 'I';
        long ifd = u32(d, 4, le);
        if (ifd <= 0 || ifd + 2 > d.length) {
            return;
        }
        int n = u16(d, (int) ifd, le);
        double x = 0;
        double y = 0;
        int unit = 2;
        for (int i = 0; i < n && i < 1000; i++) {
            int e = (int) ifd + 2 + i * 12;
            if (e + 12 > d.length) {
                break;
            }
            int tag = u16(d, e, le);
            int type = u16(d, e + 2, le);
            if ((tag == 282 || tag == 283) && type == 5) {
                long off = u32(d, e + 8, le);
                if (off + 8 <= d.length) {
                    long num = u32(d, (int) off, le);
                    long den = u32(d, (int) off + 4, le);
                    double v = den == 0 ? 0 : (double) num / den;
                    if (tag == 282) {
                        x = v;
                    } else {
                        y = v;
                    }
                }
            } else if (tag == 296) {
                unit = u16(d, e + 8, le);
            }
        }
        if (unit == 3) {
            x *= 2.54;
            y *= 2.54;
        } else if (unit == 1) {
            return;
        }
        if (x > 0 && y > 0) {
            out[0] = x;
            out[1] = y;
        }
    }

    private static int be32(byte[] d, int p) {
        return (d[p] & 0xFF) << 24 | (d[p + 1] & 0xFF) << 16 | (d[p + 2] & 0xFF) << 8 | d[p + 3] & 0xFF;
    }

    private static int u16(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFF) | (d[p + 1] & 0xFF) << 8 : (d[p] & 0xFF) << 8 | d[p + 1] & 0xFF;
    }

    private static long u32(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFFL) | (d[p + 1] & 0xFFL) << 8 | (d[p + 2] & 0xFFL) << 16 | (d[p + 3] & 0xFFL) << 24
                : (d[p] & 0xFFL) << 24 | (d[p + 1] & 0xFFL) << 16 | (d[p + 2] & 0xFFL) << 8 | d[p + 3] & 0xFFL;
    }
}
