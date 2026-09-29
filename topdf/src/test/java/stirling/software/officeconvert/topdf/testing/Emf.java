package stirling.software.officeconvert.topdf.testing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Emf {

    private static final int PLUS_VERSION = 0xDBC01002;

    private final ByteArrayOutputStream plus = new ByteArrayOutputStream();

    private final int width;

    private final int height;

    private Emf(int width, int height) {
        this.width = width;
        this.height = height;
        record(0x4001, 0, ints(PLUS_VERSION, 0, 96, 96));
    }

    public static Emf of(int width, int height) {
        return new Emf(width, height);
    }

    public static byte[] brushedSvg() {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\""
                + " height=\"10\"/></svg>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] inner = of(100, 100).brush(0, bitmapImage(10, 10, svg)).fillRect(0, 0, 0, 90, 90).bytes();
        return of(100, 100).brush(0, metafileImage(inner)).fillRect(0, 0, 0, 90, 90).bytes();
    }

    public static byte[] brushedWmfMagic() {
        byte[] magic = new byte[64];
        magic[0] = (byte) 0xD7;
        magic[1] = (byte) 0xCD;
        magic[2] = (byte) 0xC6;
        magic[3] = (byte) 0x9A;
        byte[] inner = of(100, 100).image(0, bitmapImage(10, 10, magic)).drawImage(0, 10, 10, 0, 0, 90, 90).bytes();
        return of(100, 100).brush(0, metafileImage(inner)).fillRect(0, 0, 0, 90, 90).bytes();
    }

    public static byte[] brushedHuge(int side) {
        byte[] inner = of(side, side).fillRect(0, 0, 0, 90, 90).bytes();
        return of(100, 100).brush(0, metafileImage(inner)).fillRect(0, 0, 0, 90, 90).bytes();
    }

    public Emf brush(int id, byte[] image) {
        return record(0x4008, 0x0100 | id, concat(ints(PLUS_VERSION, 2, 0, 0), image));
    }

    public Emf image(int id, byte[] image) {
        return record(0x4008, 0x0500 | id, image);
    }

    public Emf fillRect(int brushId, float x, float y, float w, float h) {
        ByteBuffer b = le(24).putInt(brushId).putInt(1).putFloat(x).putFloat(y).putFloat(w).putFloat(h);
        return record(0x400A, 0, b.array());
    }

    public Emf drawImage(int imageId, float srcW, float srcH, float x, float y, float w, float h) {
        ByteBuffer b = le(40).putInt(0xFFFFFFFF).putInt(2).putFloat(0).putFloat(0).putFloat(srcW).putFloat(srcH)
                .putFloat(x).putFloat(y).putFloat(w).putFloat(h);
        return record(0x401A, imageId, b.array());
    }

    public static byte[] bitmapImage(int w, int h, byte[] compressed) {
        return concat(ints(PLUS_VERSION, 1, w, h, 0, 0x26200A, 1), compressed);
    }

    public static byte[] metafileImage(byte[] emf) {
        return concat(ints(PLUS_VERSION, 2, 3, emf.length), emf);
    }

    public byte[] bytes() {
        byte[] records = plus.toByteArray();
        int commentSize = 16 + pad(records.length);
        int total = 88 + commentSize + 20;
        ByteBuffer b = le(total);
        b.putInt(1).putInt(88);
        b.putInt(0).putInt(0).putInt(width - 1).putInt(height - 1);
        b.putInt(0).putInt(0).putInt(Math.round(width * 26.458f)).putInt(Math.round(height * 26.458f));
        b.putInt(0x464D4520).putInt(0x10000).putInt(total).putInt(3).putShort((short) 1).putShort((short) 0);
        b.putInt(0).putInt(0).putInt(0).putInt(1024).putInt(768).putInt(320).putInt(240);
        b.putInt(70).putInt(commentSize).putInt(4 + records.length).putInt(0x2B464D45).put(records);
        for (int i = records.length; i < pad(records.length); i++) {
            b.put((byte) 0);
        }
        b.putInt(14).putInt(20).putInt(0).putInt(16).putInt(20);
        return b.array();
    }

    private Emf record(int type, int flags, byte[] data) {
        int size = 12 + pad(data.length);
        ByteBuffer b = le(size).putShort((short) type).putShort((short) flags).putInt(size).putInt(data.length).put(data);
        plus.writeBytes(b.array());
        return this;
    }

    private static int pad(int n) {
        return (n + 3) & ~3;
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] ints(int... v) {
        ByteBuffer b = le(v.length * 4);
        for (int i : v) {
            b.putInt(i);
        }
        return b.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
