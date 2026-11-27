package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.UnsupportedFormatException;

final class PsdDocument {

    static final int BITMAP = 0;

    static final int GRAY = 1;

    static final int INDEXED = 2;

    static final int RGB = 3;

    static final int CMYK = 4;

    static final int MULTICHANNEL = 7;

    static final int DUOTONE = 8;

    static final int LAB = 9;

    static final int SIGNATURE = 0x38425053;

    static final int RESOURCE = 0x3842494D;

    static final int MAX_ICC_BYTES = 16 << 20;

    static final int MAX_COLOR_DATA = 1 << 20;

    record Ink(String name, float[] rgb, int kind, int solidity) {}

    final boolean big;

    final int channels;

    final int width;

    final int height;

    final int depth;

    final int mode;

    byte[] palette;

    int paletteCount = 256;

    int transparentIndex = -1;

    int globalAngle = 120;

    double xDpi = 72;

    double yDpi = 72;

    byte[] icc;

    boolean realMerged = true;

    boolean versionInfo;

    final List<String> channelNames = new ArrayList<>();

    final List<Ink> inks = new ArrayList<>();

    long layerStart;

    long layerEnd;

    long imageStart;

    private PsdDocument(boolean big, int channels, int width, int height, int depth, int mode) {
        this.big = big;
        this.channels = channels;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mode = mode;
    }

    static boolean is(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(6);
        }
        return head.length == 6 && head[0] == '8' && head[1] == 'B' && head[2] == 'P' && head[3] == 'S'
                && head[4] == 0 && (head[5] == 1 || head[5] == 2);
    }

    int colorChannels() {
        return switch (mode) {
            case BITMAP, GRAY, DUOTONE, INDEXED -> 1;
            case RGB, LAB -> 3;
            case CMYK -> 4;
            default -> channels;
        };
    }

    int rowBytes() {
        return (int) (((long) width * depth + 7) / 8);
    }

    static PsdDocument read(Source src) throws IOException {
        Source.Cursor c = src.cursor(0, src.size, 1 << 14);
        if (src.size < 26 || c.tag() != SIGNATURE) {
            throw new UnsupportedFormatException("The file is not a PSD document: it does not start with 8BPS");
        }
        int version = c.u16();
        if (version != 1 && version != 2) {
            throw new UnsupportedFormatException("The PSD document is version " + version
                    + ", which is not a PSD (1) or PSB (2) file");
        }
        c.skip(6);
        int channels = c.u16();
        long height = c.u32();
        long width = c.u32();
        int depth = c.u16();
        int mode = c.u16();
        long max = version == 2 ? 300_000 : 30_000;
        if (channels < 1 || channels > 56) {
            throw new IOException("The PSD document has " + channels + " channels; it can have 1 to 56");
        }
        if (width < 1 || height < 1 || width > max || height > max) {
            throw new IOException("The PSD document is " + width + " x " + height + " pixels; a "
                    + (version == 2 ? "PSB" : "PSD") + " file can be 1 to " + max + " pixels a side");
        }
        if (depth != 1 && depth != 8 && depth != 16 && depth != 32) {
            throw new IOException("The PSD document has " + depth + " bits a channel; it can have 1, 8, 16 or 32");
        }
        boolean known = switch (mode) {
            case BITMAP, GRAY, INDEXED, RGB, CMYK, MULTICHANNEL, DUOTONE, LAB -> true;
            default -> false;
        };
        if (!known) {
            throw new UnsupportedFormatException("The PSD document's color mode (" + mode + ") is not supported");
        }
        if (mode == BITMAP && depth != 1 || mode != BITMAP && depth == 1) {
            throw new IOException("The PSD document's color mode and depth do not match");
        }
        if (mode == INDEXED && depth != 8) {
            throw new IOException("An indexed PSD document must have 8 bits a channel");
        }
        PsdDocument doc = new PsdDocument(version == 2, channels, (int) width, (int) height, depth, mode);
        if (channels < doc.colorChannels()) {
            throw new IOException("The PSD document has too few channels for its color mode");
        }
        long colorLen = c.u32();
        long colorStart = c.position();
        if (mode == INDEXED && colorLen >= 768) {
            doc.palette = new byte[768];
            c.fully(doc.palette);
        }
        c.seek(colorStart);
        c.skip(colorLen);
        long resLen = c.u32();
        long resStart = c.position();
        doc.resources(src, resStart, resStart + resLen);
        c.seek(resStart);
        c.skip(resLen);
        long layerLen = c.length(doc.big);
        doc.layerStart = c.position();
        doc.layerEnd = doc.layerStart + layerLen;
        c.skip(layerLen);
        doc.imageStart = c.position();
        if (mode == INDEXED && doc.palette == null) {
            throw new IOException("The indexed PSD document has no color table");
        }
        return doc;
    }

    private void resources(Source src, long start, long end) throws IOException {
        Source.Cursor c = src.cursor(start, end, 1 << 14);
        int count = 0;
        while (c.remaining() >= 12 && count++ < 10_000) {
            int sig = c.tag();
            if (sig != RESOURCE && sig != 0x4D654453 && sig != 0x50485554 && sig != 0x41674850 && sig != 0x44434453) {
                break;
            }
            int id = c.u16();
            int nameLen = c.u8();
            c.skip(nameLen + ((nameLen + 1) % 2));
            long size = c.u32();
            long dataStart = c.position();
            if (size > c.remaining()) {
                break;
            }
            resource(c, id, (int) Math.min(size, Integer.MAX_VALUE));
            c.seek(Math.min(c.end(), dataStart + size + (size & 1)));
        }
    }

    private void resource(Source.Cursor c, int id, int size) throws IOException {
        switch (id) {
            case 0x03ED -> {
                if (size >= 16) {
                    double h = c.u32() / 65536.0;
                    int hu = c.u16();
                    c.u16();
                    double v = c.u32() / 65536.0;
                    int vu = c.u16();
                    xDpi = dpi(h, hu);
                    yDpi = dpi(v, vu);
                    if (xDpi <= 0 || yDpi <= 0) {
                        xDpi = yDpi = 72;
                    }
                }
            }
            case 0x040F -> {
                if (size >= 132 && size <= MAX_ICC_BYTES) {
                    byte[] p = new byte[size];
                    c.fully(p);
                    icc = p;
                }
            }
            case 0x0421 -> {
                if (size >= 5) {
                    c.u32();
                    realMerged = c.u8() != 0;
                    versionInfo = true;
                }
            }
            case 0x040D -> {
                if (size >= 4) {
                    globalAngle = c.i32();
                }
            }
            case 0x0416 -> {
                if (size >= 2) {
                    paletteCount = Math.max(1, Math.min(256, c.u16()));
                }
            }
            case 0x0417 -> {
                if (size >= 2) {
                    transparentIndex = c.u16();
                }
            }
            case 0x03EE -> {
                if (channelNames.isEmpty()) {
                    long end = c.position() + size;
                    while (c.position() < end && channelNames.size() < 64) {
                        int n = c.u8();
                        if (c.position() + n > end) {
                            break;
                        }
                        byte[] b = new byte[n];
                        c.fully(b);
                        channelNames.add(new String(b, StandardCharsets.ISO_8859_1));
                    }
                }
            }
            case 0x0415 -> {
                long end = c.position() + size;
                List<String> names = new ArrayList<>();
                while (end - c.position() >= 4 && names.size() < 64) {
                    long n = c.u32();
                    if (n * 2 > end - c.position()) {
                        break;
                    }
                    StringBuilder s = new StringBuilder();
                    for (long i = 0; i < n; i++) {
                        char ch = (char) c.u16();
                        if (ch != 0) {
                            s.append(ch);
                        }
                    }
                    names.add(s.toString());
                }
                if (!names.isEmpty()) {
                    channelNames.clear();
                    channelNames.addAll(names);
                }
            }
            case 0x0435 -> {
                if (size >= 4) {
                    inks.clear();
                    c.u32();
                    for (int i = 0; i < 64 && (i + 1) * 13 <= size - 4; i++) {
                        inks.add(ink(c, false));
                    }
                }
            }
            case 0x03EF -> {
                if (inks.isEmpty()) {
                    for (int i = 0; i < 64 && (i + 1) * 14 <= size; i++) {
                        inks.add(ink(c, true));
                    }
                }
            }
            default -> {}
        }
    }

    private static Ink ink(Source.Cursor c, boolean old) throws IOException {
        int space = c.u16();
        int[] v = {c.u16(), c.u16(), c.u16(), c.u16()};
        int solidity = c.u16();
        int kind = c.u8();
        if (old) {
            c.u8();
        }
        return new Ink(null, Colors.toRgb(space, v), kind, solidity);
    }

    private static double dpi(double v, int unit) {
        double d = unit == 2 ? v * 2.54 : v;
        return d >= 1 && d <= 100_000 && Double.isFinite(d) ? d : -1;
    }
}
