package stirling.software.officeconvert.jpx;

import java.util.ArrayList;
import java.util.List;

final class Jp2Boxes {

    private static final int SIGNATURE = 0x6A502020;
    private static final int JP2H = 0x6A703268;
    private static final int JP2C = 0x6A703263;
    private static final int IHDR = 0x69686472;
    private static final int COLR = 0x636F6C72;
    private static final int PCLR = 0x70636C72;
    private static final int CMAP = 0x636D6170;
    private static final int CDEF = 0x63646566;

    int codestreamStart;

    int codestreamEnd;

    int channels = -1;

    final List<ColourSpec> colours = new ArrayList<>();

    Palette palette;

    int[][] mapping;

    int[][] definitions;

    static Jp2Boxes read(byte[] data) throws JpxException {
        Jp2Boxes boxes = new Jp2Boxes();
        if (data.length >= 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0x4F) {
            boxes.codestreamStart = 0;
            boxes.codestreamEnd = data.length;
            return boxes;
        }
        if (data.length < 12 || Bytes.u32(data, 4) != SIGNATURE) {
            int soc = findCodestream(data);
            if (soc < 0) {
                throw new JpxException("Not a JPEG 2000 image");
            }
            boxes.codestreamStart = soc;
            boxes.codestreamEnd = data.length;
            return boxes;
        }
        boxes.top(data);
        if (boxes.codestreamEnd == 0) {
            throw new JpxException("JPEG 2000 file has no codestream");
        }
        return boxes;
    }

    private static int findCodestream(byte[] data) {
        for (int i = 0; i + 3 < Math.min(data.length, 256); i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0x4F && (data[i + 2] & 0xFF) == 0xFF
                    && (data[i + 3] & 0xFF) == 0x51) {
                return i;
            }
        }
        return -1;
    }

    private void top(byte[] data) throws JpxException {
        Bytes b = new Bytes(data, 0, data.length);
        while (b.remaining() >= 8) {
            int[] box = box(b);
            if (box == null) {
                return;
            }
            int type = box[0];
            if (type == JP2H) {
                header(data, box[1], box[2]);
            } else if (type == JP2C && codestreamEnd == 0) {
                codestreamStart = box[1];
                codestreamEnd = box[2];
                return;
            }
            b.seek(box[2]);
        }
    }

    private static int[] box(Bytes b) throws JpxException {
        int start = b.pos();
        long length = b.u32();
        int type = (int) b.u32();
        int body = b.pos();
        if (length == 1) {
            if (b.remaining() < 8) {
                return null;
            }
            length = b.u64();
            body = b.pos();
        } else if (length == 0) {
            length = b.end() - start;
        }
        long end = start + length;
        if (length < body - start || end > b.end()) {
            end = b.end();
        }
        return new int[] {type, body, (int) end};
    }

    private void header(byte[] data, int start, int end) throws JpxException {
        Bytes b = new Bytes(data, start, end);
        while (b.remaining() >= 8) {
            int[] box = box(b);
            if (box == null) {
                return;
            }
            Bytes in = new Bytes(data, box[1], box[2]);
            try {
                switch (box[0]) {
                    case IHDR -> {
                        in.u32();
                        in.u32();
                        channels = in.u16();
                    }
                    case COLR -> colours.add(ColourSpec.read(in));
                    case PCLR -> palette = Palette.read(in);
                    case CMAP -> mapping = mapping(in);
                    case CDEF -> definitions = definitions(in);
                    default -> {
                    }
                }
            } catch (JpxException e) {
                if (box[0] == IHDR || box[0] == PCLR || box[0] == CMAP) {
                    throw e;
                }
            }
            b.seek(box[2]);
        }
    }

    private static int[][] mapping(Bytes in) throws JpxException {
        int n = in.remaining() / 4;
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[] {in.u16(), in.u8(), in.u8()};
        }
        return out;
    }

    private static int[][] definitions(Bytes in) throws JpxException {
        int n = in.u16();
        if (n > in.remaining() / 6) {
            throw new JpxException("Invalid JPEG 2000 channel definitions");
        }
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[] {in.u16(), in.u16(), in.u16()};
        }
        return out;
    }
}
