package stirling.software.officeconvert.topdf.ofd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class OfdJbig2 {

    static final int MAX_SEGMENTS = 10_000;

    static final long MAX_PIXELS = PictureDecoder.MAX_PIXELS;

    private record Segment(int type, long page, byte[] header, int dataFrom, int dataLength, int pageAt, boolean longPage) {}

    private OfdJbig2() {}

    static boolean is(byte[] b, String format) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x97 && b[1] == 'J' && b[2] == 'B' && b[3] == '2') {
            return true;
        }
        String f = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        return f.equals("JB2") || f.equals("JBIG2") || f.equals("GBIG2");
    }

    static PDImageXObject image(PDDocument doc, byte[] b) throws IOException {
        int at = 0;
        boolean sequential = true;
        if (b.length >= 9 && (b[0] & 0xFF) == 0x97 && b[1] == 'J' && b[2] == 'B' && b[3] == '2') {
            int flags = b[8] & 0xFF;
            sequential = (flags & 1) != 0;
            at = 9 + ((flags & 2) == 0 ? 4 : 0);
        }
        List<Segment> segments = new ArrayList<>();
        while (at < b.length && segments.size() < MAX_SEGMENTS) {
            int start = at;
            if (at + 6 > b.length) {
                break;
            }
            long number = u32(b, at);
            int flags = b[at + 4] & 0xFF;
            int type = flags & 0x3F;
            boolean longPage = (flags & 0x40) != 0;
            at += 5;
            int first = b[at] & 0xFF;
            int count = first >>> 5;
            if (count == 7) {
                if (at + 4 > b.length) {
                    break;
                }
                count = (int) (u32(b, at) & 0x1FFFFFFF);
                if (count > 100_000) {
                    throw new IOException("A JBIG2 picture refers to too many segments");
                }
                at += 4 + (count + 8) / 8;
            } else {
                at += 1;
            }
            int refSize = number <= 256 ? 1 : number <= 65536 ? 2 : 4;
            at += count * refSize;
            if (at + (longPage ? 4 : 1) + 4 > b.length) {
                break;
            }
            int pageAt = at - start;
            long page = longPage ? u32(b, at) : b[at] & 0xFF;
            at += longPage ? 4 : 1;
            long length = u32(b, at);
            at += 4;
            if (length == 0xFFFFFFFFL) {
                throw new IOException("A JBIG2 picture has a segment of unknown length");
            }
            byte[] header = Arrays.copyOfRange(b, start, at);
            if (sequential) {
                if (length > b.length - at) {
                    break;
                }
                segments.add(new Segment(type, page, header, at, (int) length, pageAt, longPage));
                at += (int) length;
            } else {
                segments.add(new Segment(type, page, header, -1, (int) Math.min(length, Integer.MAX_VALUE), pageAt,
                        longPage));
            }
            if (type == 51) {
                break;
            }
        }
        if (!sequential) {
            List<Segment> placed = new ArrayList<>();
            for (Segment s : segments) {
                if (s.dataLength() > b.length - at) {
                    break;
                }
                placed.add(new Segment(s.type(), s.page(), s.header(), at, s.dataLength(), s.pageAt(), s.longPage()));
                at += s.dataLength();
            }
            segments = placed;
        }
        long width = -1;
        long height = -1;
        long pageNumber = -1;
        for (Segment s : segments) {
            if (s.type() == 48 && s.dataLength() >= 8) {
                width = u32(b, s.dataFrom());
                height = u32(b, s.dataFrom() + 4);
                pageNumber = s.page();
                break;
            }
        }
        if (width <= 0 || height <= 0 || height == 0xFFFFFFFFL || width * height > MAX_PIXELS) {
            throw new IOException("The JBIG2 picture has no usable page size");
        }
        ByteArrayOutputStream globals = new ByteArrayOutputStream();
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        for (Segment s : segments) {
            if (s.type() == 49 || s.type() == 51) {
                continue;
            }
            if (s.page() == 0) {
                globals.write(s.header());
                globals.write(b, s.dataFrom(), s.dataLength());
            } else if (s.page() == pageNumber) {
                page.write(firstPage(s));
                page.write(b, s.dataFrom(), s.dataLength());
            }
        }
        COSStream stream = doc.getDocument().createCOSStream();
        try (OutputStream os = stream.createRawOutputStream()) {
            page.writeTo(os);
        }
        stream.setItem(COSName.TYPE, COSName.XOBJECT);
        stream.setItem(COSName.SUBTYPE, COSName.IMAGE);
        stream.setInt(COSName.WIDTH, (int) width);
        stream.setInt(COSName.HEIGHT, (int) height);
        stream.setInt(COSName.BITS_PER_COMPONENT, 1);
        stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        stream.setItem(COSName.FILTER, COSName.JBIG2_DECODE);
        if (globals.size() > 0) {
            COSStream g = doc.getDocument().createCOSStream();
            try (OutputStream os = g.createRawOutputStream()) {
                globals.writeTo(os);
            }
            COSDictionary parms = new COSDictionary();
            parms.setItem(COSName.JBIG2_GLOBALS, g);
            stream.setItem(COSName.DECODE_PARMS, parms);
        }
        return new PDImageXObject(new PDStream(stream), null);
    }

    private static byte[] firstPage(Segment s) {
        byte[] h = s.header().clone();
        if (s.longPage()) {
            h[s.pageAt()] = 0;
            h[s.pageAt() + 1] = 0;
            h[s.pageAt() + 2] = 0;
            h[s.pageAt() + 3] = 1;
        } else {
            h[s.pageAt()] = 1;
        }
        return h;
    }

    private static long u32(byte[] b, int at) {
        return (long) (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}
