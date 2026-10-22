package stirling.software.officeconvert.topdf.thumbnail;

import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import org.apache.poi.hpsf.PropertySet;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.LegacyOffice;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

public final class OleThumbnail {

    public enum Kind {
        PUBLISHER,
        VISIO
    }

    public enum Outcome {
        DRAWN,
        NO_PREVIEW,
        TOO_SMALL
    }

    static final int MAX_SUMMARY_BYTES = 8 << 20;

    private static final int WINDOWS_FORMAT = -1;

    private static final int CF_METAFILEPICT = 3;

    private static final int CF_DIB = 8;

    private static final int CF_ENHMETAFILE = 14;

    private static final int MM_ISOTROPIC = 7;

    private static final int MM_ANISOTROPIC = 8;

    private static final float POINTS_PER_HIMETRIC = 72f / 2540f;

    private static final int EMR_HEADER = 1;

    private static final int EMR_EOF = 14;

    private static final int EMR_GDICOMMENT = 70;

    private static final int EMR_BITBLT = 76;

    private static final int EMR_STRETCHBLT = 77;

    private static final int EMR_STRETCHDIBITS = 81;

    private static final int EMR_EXTTEXTOUTW = 84;

    private static final int ETO_GLYPH_INDEX = 0x10;

    private static final int FIRST_STANDARD_GLYPH = 3;

    private static final int LAST_STANDARD_GLYPH = 97;

    private static final int STANDARD_GLYPH_TO_ASCII = 29;

    private static final int EMF_PLUS = 0x2B464D45;

    private static final int MAX_SOLE_BITMAP_RECORDS = 64;

    private static final int MIN_BITMAP_SIDE = 600;

    private static final int PLACEABLE_WMF = 0x9AC6CDD7;

    private static final int META_EOF = 0x0000;

    private static final int META_DIBBITBLT = 0x0940;

    private static final int META_DIBSTRETCHBLT = 0x0B41;

    private static final int META_STRETCHDIB = 0x0F43;

    private static final Set<Integer> WMF_STATE = Set.of(0x001E, 0x0102, 0x0103, 0x0107, 0x0127, 0x012D, 0x01F0,
            0x0201, 0x0209, 0x020B, 0x020C, 0x020D, 0x020E, 0x02FC, 0x061D, 0x0626);

    private static final float MIN_PAGE_SIDE = 144;

    private static final float MIN_FRAMED_SIDE = 36;

    private static final float MAX_PAGE_SIDE = 14_400;

    private static final float PAPER_TOLERANCE = 0.015f;

    private static final float[][] PAPERS = {{612, 792}, {595.28f, 841.89f}, {612, 1008}, {792, 1224},
        {841.89f, 1190.55f}, {419.53f, 595.28f}};

    private OleThumbnail() {}

    public static Kind kind(Path file) throws IOException {
        if (!LegacyOffice.ole2(file)) {
            return null;
        }
        try (POIFSFileSystem fs = LegacyOffice.open(file)) {
            DirectoryNode root = fs.getRoot();
            if (root.hasEntryCaseInsensitive("Quill")) {
                return Kind.PUBLISHER;
            }
            return root.hasEntryCaseInsensitive("VisioDocument") ? Kind.VISIO : null;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static long summaryBytes(Path file) throws IOException {
        try (POIFSFileSystem fs = LegacyOffice.open(file)) {
            DocumentEntry e = summary(fs.getRoot());
            return e == null ? 0 : Math.min(e.getSize(), MAX_SUMMARY_BYTES);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public static Outcome draw(Path file, PdfOutput out) throws IOException {
        Preview preview = preview(file);
        if (preview == null) {
            return Outcome.NO_PREVIEW;
        }
        int side = bitmapSide(preview.data());
        if (side >= 0 && side < MIN_BITMAP_SIDE) {
            return Outcome.TOO_SMALL;
        }
        DecodedPicture picture;
        try {
            picture = PictureDecoder.decodePage(out.document(), preview.data());
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return Outcome.NO_PREVIEW;
        }
        if (picture.image() != null) {
            picture.image().setInterpolate(true);
        }
        float[] page = pageSize(preview, picture);
        try (PdfCanvas c = out.newPage(page[0], page[1])) {
            c.image(picture, 0, 0, page[0], page[1]);
        }
        return Outcome.DRAWN;
    }

    record Preview(byte[] data, float widthPoints, float heightPoints) {}

    static int bitmapSide(byte[] data) {
        PictureDecoder.Kind kind = PictureDecoder.sniff(data);
        if (kind == PictureDecoder.Kind.WMF) {
            return wmfBitmapSide(data);
        }
        if (kind == PictureDecoder.Kind.EMF) {
            return -1;
        }
        try {
            Dimension d = PictureDecoder.pixelSize(data);
            return Math.max(d.width, d.height);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    static int wmfBitmapSide(byte[] wmf) {
        ByteBuffer b = ByteBuffer.wrap(wmf).order(ByteOrder.LITTLE_ENDIAN);
        int at = wmf.length >= 22 && b.getInt(0) == PLACEABLE_WMF ? 22 : 0;
        if (at + 18 > wmf.length) {
            return -1;
        }
        at += 2 * (b.getShort(at + 2) & 0xFFFF);
        int side = -1;
        for (int records = 0; at + 6 <= wmf.length && records < MAX_SOLE_BITMAP_RECORDS; records++) {
            long size = 2 * (b.getInt(at) & 0xFFFFFFFFL);
            int function = b.getShort(at + 4) & 0xFFFF;
            if (size < 6 || size > wmf.length - at) {
                return -1;
            }
            int dib = switch (function) {
                case META_DIBBITBLT -> at + 6 + 16;
                case META_DIBSTRETCHBLT -> at + 6 + 20;
                case META_STRETCHDIB -> at + 6 + 22;
                default -> -1;
            };
            if (function == META_EOF) {
                return side;
            }
            if (dib >= 0) {
                if (side >= 0 || dib + 12 > at + size) {
                    return -1;
                }
                side = Math.max(Math.abs(b.getInt(dib + 4)), Math.abs(b.getInt(dib + 8)));
            } else if (!WMF_STATE.contains(function)) {
                return -1;
            }
            at += (int) size;
        }
        return -1;
    }

    static Preview preview(Path file) throws IOException {
        byte[] stream;
        try (POIFSFileSystem fs = LegacyOffice.open(file)) {
            DocumentEntry e = summary(fs.getRoot());
            if (e == null || e.getSize() <= 0 || e.getSize() > MAX_SUMMARY_BYTES) {
                return null;
            }
            try (InputStream in = new DocumentInputStream(e)) {
                stream = in.readNBytes(e.getSize());
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        byte[] clip;
        try {
            PropertySet set = new PropertySet(new ByteArrayInputStream(stream));
            if (!set.isSummaryInformation()) {
                return null;
            }
            clip = new SummaryInformation(set).getThumbnail();
        } catch (InterruptedIOException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
        return clip == null ? null : clipboard(clip);
    }

    static Preview clipboard(byte[] clip) {
        if (clip.length < 12) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN);
        int tag = b.getInt(4);
        Preview p;
        if (tag == WINDOWS_FORMAT) {
            p = windows(b.getInt(8), clip);
        } else if (tag > 0 && tag < clip.length - 8) {
            p = new Preview(Arrays.copyOfRange(clip, 8 + tag, clip.length), 0, 0);
        } else {
            return null;
        }
        if (p == null || !drawable(PictureDecoder.sniff(p.data())) || blank(p.data())) {
            return null;
        }
        return p;
    }

    private static Preview windows(int format, byte[] clip) {
        ByteBuffer b = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN);
        return switch (format) {
            case CF_METAFILEPICT -> {
                if (clip.length < 20) {
                    yield null;
                }
                int mode = b.getShort(12);
                int x = b.getShort(14);
                int y = b.getShort(16);
                boolean sized = (mode == MM_ANISOTROPIC || mode == MM_ISOTROPIC) && x > 0 && y > 0;
                yield new Preview(Arrays.copyOfRange(clip, 20, clip.length), sized ? x * POINTS_PER_HIMETRIC : 0,
                        sized ? y * POINTS_PER_HIMETRIC : 0);
            }
            case CF_ENHMETAFILE -> enhanced(Arrays.copyOfRange(clip, 12, clip.length));
            case CF_DIB -> {
                byte[] bmp = dib(clip, 12, clip.length - 12);
                yield bmp == null ? null : new Preview(bmp, 0, 0);
            }
            default -> new Preview(Arrays.copyOfRange(clip, 12, clip.length), 0, 0);
        };
    }

    private static Preview enhanced(byte[] emf) {
        byte[] raster = soleBitmap(emf);
        if (raster == null) {
            return new Preview(characters(emf), 0, 0);
        }
        ByteBuffer b = ByteBuffer.wrap(emf).order(ByteOrder.LITTLE_ENDIAN);
        long w = (long) b.getInt(32) - b.getInt(24);
        long h = (long) b.getInt(36) - b.getInt(28);
        return new Preview(raster, w > 0 && h > 0 ? w * POINTS_PER_HIMETRIC : 0,
                w > 0 && h > 0 ? h * POINTS_PER_HIMETRIC : 0);
    }

    static byte[] characters(byte[] emf) {
        if (PictureDecoder.sniff(emf) != PictureDecoder.Kind.EMF) {
            return emf;
        }
        byte[] out = emf.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        int at = 0;
        while (at + 8 <= out.length) {
            int type = b.getInt(at);
            int size = b.getInt(at + 4);
            if (size < 8 || size > out.length - at || type == EMR_EOF) {
                break;
            }
            if (type == EMR_EXTTEXTOUTW && size >= 76 && (b.getInt(at + 52) & ETO_GLYPH_INDEX) != 0) {
                long chars = b.getInt(at + 44) & 0xFFFFFFFFL;
                long text = b.getInt(at + 48) & 0xFFFFFFFFL;
                if (text + 2 * chars > size) {
                    b.putInt(at + 44, 0);
                } else {
                    boolean mapped = true;
                    for (int i = 0; i < chars; i++) {
                        int g = b.getShort(at + (int) text + 2 * i) & 0xFFFF;
                        mapped &= g >= FIRST_STANDARD_GLYPH && g <= LAST_STANDARD_GLYPH;
                    }
                    for (int i = 0; mapped && i < chars; i++) {
                        int g = b.getShort(at + (int) text + 2 * i) & 0xFFFF;
                        b.putShort(at + (int) text + 2 * i, (short) (g + STANDARD_GLYPH_TO_ASCII));
                    }
                    b.putInt(at + 52, b.getInt(at + 52) & ~ETO_GLYPH_INDEX);
                    if (!mapped) {
                        b.putInt(at + 44, 0);
                    }
                }
            }
            at += size;
        }
        return out;
    }

    static byte[] soleBitmap(byte[] emf) {
        if (PictureDecoder.sniff(emf) != PictureDecoder.Kind.EMF) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(emf).order(ByteOrder.LITTLE_ENDIAN);
        byte[] found = null;
        int at = 0;
        for (int records = 0; at + 8 <= emf.length && records < MAX_SOLE_BITMAP_RECORDS; records++) {
            int type = b.getInt(at);
            int size = b.getInt(at + 4);
            if (size < 8 || size > emf.length - at) {
                return null;
            }
            switch (type) {
                case EMR_HEADER -> {
                    if (at != 0) {
                        return null;
                    }
                }
                case EMR_GDICOMMENT -> {
                    if (size >= 16 && b.getInt(at + 12) == EMF_PLUS) {
                        return null;
                    }
                }
                case EMR_BITBLT, EMR_STRETCHBLT, EMR_STRETCHDIBITS -> {
                    if (found != null) {
                        return null;
                    }
                    found = bitmapRecord(b, at, size, type);
                    if (found == null) {
                        return null;
                    }
                }
                case EMR_EOF -> {
                    return found;
                }
                default -> {
                    return null;
                }
            }
            at += size;
        }
        return null;
    }

    private static byte[] bitmapRecord(ByteBuffer b, int at, int size, int type) {
        int fields = type == EMR_STRETCHDIBITS ? 48 : 84;
        if (size < fields + 16) {
            return null;
        }
        long info = b.getInt(at + fields) & 0xFFFFFFFFL;
        long infoBytes = b.getInt(at + fields + 4) & 0xFFFFFFFFL;
        long bits = b.getInt(at + fields + 8) & 0xFFFFFFFFL;
        long bitsBytes = b.getInt(at + fields + 12) & 0xFFFFFFFFL;
        if (infoBytes < 40 || bitsBytes == 0 || info + infoBytes > size || bits + bitsBytes > size) {
            return null;
        }
        byte[] header = new byte[(int) infoBytes];
        b.get(at + (int) info, header);
        byte[] pixels = new byte[(int) bitsBytes];
        b.get(at + (int) bits, pixels);
        return bitmap(header, pixels);
    }

    private static byte[] dib(byte[] clip, int at, int n) {
        if (n < 40) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(clip).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getInt(at);
        if (headerSize < 40 || headerSize > n) {
            return null;
        }
        int bits = b.getShort(at + 14) & 0xFFFF;
        int compression = b.getInt(at + 16);
        long used = b.getInt(at + 32) & 0xFFFFFFFFL;
        long colours = used != 0 ? used : bits <= 8 ? 1L << bits : 0;
        long masks = headerSize == 40 && (compression == 3 || compression == 6) ? (compression == 3 ? 12 : 16) : 0;
        long info = headerSize + colours * 4 + masks;
        if (info >= n) {
            return null;
        }
        return bitmap(Arrays.copyOfRange(clip, at, at + (int) info), Arrays.copyOfRange(clip, at + (int) info, at + n));
    }

    private static byte[] bitmap(byte[] info, byte[] pixels) {
        int length = 14 + info.length + pixels.length;
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).put((byte) 'B').put((byte) 'M')
                .putInt(length).putInt(0).putInt(14 + info.length).put(info).put(pixels).array();
    }

    private static boolean drawable(PictureDecoder.Kind kind) {
        return switch (kind) {
            case PNG, JPEG, GIF, BMP, EMF, WMF -> true;
            default -> false;
        };
    }

    static boolean blank(byte[] data) {
        if (PictureDecoder.sniff(data) != PictureDecoder.Kind.EMF) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return b.getInt(16) < b.getInt(8) || b.getInt(20) < b.getInt(12);
    }

    static float[] pageSize(Preview preview, DecodedPicture picture) {
        float w = preview.widthPoints() > 0 ? preview.widthPoints() : picture.naturalWidth();
        float h = preview.heightPoints() > 0 ? preview.heightPoints() : picture.naturalHeight();
        if (!(w > 0 && h > 0) && picture.pixelWidth() > 0 && picture.pixelHeight() > 0) {
            w = picture.pixelWidth();
            h = picture.pixelHeight();
        }
        if (!(w > 0 && h > 0)) {
            return PAPERS[0].clone();
        }
        float aspect = w / h;
        for (float[] paper : PAPERS) {
            if (Math.abs(aspect * paper[1] / paper[0] - 1) <= PAPER_TOLERANCE) {
                return paper.clone();
            }
            if (Math.abs(aspect * paper[0] / paper[1] - 1) <= PAPER_TOLERANCE) {
                return new float[] {paper[1], paper[0]};
            }
        }
        boolean framed = picture.vector() && picture.kind() == PictureDecoder.Kind.EMF && preview.widthPoints() <= 0;
        if (Math.min(w, h) >= (framed ? MIN_FRAMED_SIDE : MIN_PAGE_SIDE) && Math.max(w, h) <= MAX_PAGE_SIDE) {
            return new float[] {w, h};
        }
        float shortSide = PAPERS[0][0];
        float longSide = PAPERS[0][1];
        float scale = Math.min(shortSide / Math.min(w, h), longSide / Math.max(w, h));
        return new float[] {Math.min(MAX_PAGE_SIDE, Math.max(1, w * scale)),
            Math.min(MAX_PAGE_SIDE, Math.max(1, h * scale))};
    }

    private static DocumentEntry summary(DirectoryNode root) {
        for (Entry e : root) {
            if (e instanceof DocumentEntry d && SummaryInformation.DEFAULT_STREAM_NAME.equals(e.getName())) {
                return d;
            }
        }
        return null;
    }
}
