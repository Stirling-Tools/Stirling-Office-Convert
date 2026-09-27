package stirling.software.officeconvert.extract;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

final class Jbig2Size {

    private static final int PAGE_INFORMATION = 48;
    private static final int END_OF_STRIPE = 50;
    private static final int MAX_SEGMENTS = 100_000;

    private Jbig2Size() {}

    static long largestBitmap(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        long largest = 0;
        long pageWidth = 0;
        long pageBottom = 0;
        try {
            for (int s = 0; s < MAX_SEGMENTS; s++) {
                long number = in.readInt() & 0xFFFFFFFFL;
                int flags = in.readUnsignedByte();
                int type = flags & 0x3F;
                int first = in.readUnsignedByte();
                long refs = first >>> 5;
                if (refs == 7) {
                    refs = (long) (first & 0x1F) << 24 | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8
                            | in.readUnsignedByte();
                    in.skipNBytes((refs + 8) / 8);
                } else if (refs > 4) {
                    throw new IOException("Invalid JBIG2 segment header");
                }
                in.skipNBytes(refs * (number <= 256 ? 1 : number <= 65536 ? 2 : 4));
                in.skipNBytes((flags & 0x40) != 0 ? 4 : 1);
                long length = in.readInt() & 0xFFFFFFFFL;
                long read = 0;
                if (type == PAGE_INFORMATION) {
                    pageWidth = in.readInt() & 0xFFFFFFFFL;
                    long height = in.readInt() & 0xFFFFFFFFL;
                    pageBottom = height == 0xFFFFFFFFL ? 0 : height;
                    read = 8;
                } else if (type == END_OF_STRIPE) {
                    pageBottom = Math.max(pageBottom, (in.readInt() & 0xFFFFFFFFL) + 1);
                    read = 4;
                } else if (region(type)) {
                    long width = in.readInt() & 0xFFFFFFFFL;
                    long height = in.readInt() & 0xFFFFFFFFL;
                    largest = Math.max(largest, bytes(width, height));
                    read = 8;
                }
                largest = Math.max(largest, bytes(pageWidth, pageBottom));
                if (length == 0xFFFFFFFFL) {
                    break;
                }
                in.skipNBytes(length - read);
            }
        } catch (EOFException end) {
        }
        return largest;
    }

    private static boolean region(int type) {
        return switch (type) {
            case 4, 6, 7, 20, 22, 23, 36, 38, 39, 40, 42, 43 -> true;
            default -> false;
        };
    }

    private static long bytes(long width, long height) {
        return (width + 7) / 8 * height;
    }
}
