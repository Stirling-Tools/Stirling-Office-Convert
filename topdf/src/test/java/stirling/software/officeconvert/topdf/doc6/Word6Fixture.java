package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

final class Word6Fixture {

    private static final int TEXT = 0x300;

    private String first = "Hello Word 6";

    private String second = "Second paragraph";

    private Charset charset = Charset.forName("windows-1252");

    private int lid = 0x0409;

    private boolean landscape;

    private boolean drawing;

    Word6Fixture text(String a, String b) {
        first = a;
        second = b;
        return this;
    }

    Word6Fixture russian() {
        charset = Charset.forName("windows-1251");
        lid = 0x0419;
        return this;
    }

    Word6Fixture drawing() {
        drawing = true;
        return this;
    }

    Word6Fixture landscape() {
        landscape = true;
        return this;
    }

    byte[] build() {
        ByteBuffer b = ByteBuffer.allocate(0xC00).order(ByteOrder.LITTLE_ENDIAN);
        String box = "Boxed words\r";
        String main = (drawing ? "\u0008" : "") + first + "\r" + second + "\r";
        byte[] text = (main + (drawing ? box + "\r" : "")).getBytes(charset);
        int mainLength = main.length();
        int fcMac = TEXT + text.length;
        int split = TEXT + first.length() + 1;
        b.putShort(0, (short) 0xA5DC).putShort(2, (short) 0x65).putShort(6, (short) lid);
        b.putInt(0x18, TEXT).putInt(0x1C, fcMac).putInt(0x20, 0xC00).putInt(0x34, mainLength);
        if (drawing) {
            b.putInt(0x34 + 4 * 6, box.length() + 1);
        }
        b.position(TEXT);
        b.put(text);
        int chp = 0x400;
        b.putInt(chp, TEXT).putInt(chp + 4, TEXT + (drawing ? 1 : 5)).putInt(chp + 8, fcMac);
        b.put(chp + 12, (byte) (0x1F0 / 2)).put(chp + 13, (byte) 0);
        b.put(chp + 0x1F0, (byte) 2).put(chp + 0x1F1, (byte) (drawing ? 117 : 85)).put(chp + 0x1F2, (byte) 1);
        b.put(chp + 511, (byte) 2);
        int pap = 0x600;
        b.putInt(pap, TEXT).putInt(pap + 4, split).putInt(pap + 8, fcMac);
        b.put(pap + 12, (byte) (0x1E0 / 2)).put(pap + 19, (byte) (0x1F0 / 2));
        b.put(pap + 0x1E0, (byte) 2).putShort(pap + 0x1E1, (short) 0).put(pap + 0x1E3, (byte) 5).put(pap + 0x1E4, (byte) 1);
        b.put(pap + 0x1F0, (byte) 1).putShort(pap + 0x1F1, (short) 0);
        b.put(pap + 511, (byte) 2);
        int bte = 0x800;
        b.putInt(bte, TEXT).putInt(bte + 4, fcMac).putShort(bte + 8, (short) 2);
        b.putInt(bte + 16, TEXT).putInt(bte + 20, fcMac).putShort(bte + 24, (short) 3);
        int stsh = 0x840;
        b.position(stsh);
        b.putShort((short) 14).putShort((short) 1).putShort((short) 8).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        int stdStart = b.position() + 2;
        ByteBuffer std = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        std.putShort((short) 0).putShort((short) (1 | 0xFFF << 4)).putShort((short) 2).putShort((short) 0);
        std.put((byte) 6).put("Normal".getBytes(charset)).put((byte) 0);
        if ((std.position() & 1) != 0) {
            std.put((byte) 0);
        }
        std.putShort((short) 2).putShort((short) 0);
        std.putShort((short) 3).put((byte) 99).putShort((short) 24).put((byte) 0);
        b.putShort((short) std.position());
        b.put(std.array(), 0, std.position());
        int stshEnd = b.position();
        int ffn = 0x900;
        b.position(ffn + 2);
        byte[] name = "Times New Roman".getBytes(charset);
        b.put((byte) (6 + name.length)).put((byte) 0x16).putShort((short) 400).put((byte) (lid == 0x0419 ? 204 : 0))
                .put((byte) 0).put(name).put((byte) 0);
        int ffnEnd = b.position();
        b.putShort(ffn, (short) (ffnEnd - ffn));
        int sed = 0x960;
        int sepx = 0x990;
        b.putInt(sed, 0).putInt(sed + 4, mainLength).putShort(sed + 8, (short) 0).putInt(sed + 10, landscape ? sepx : -1)
                .putShort(sed + 14, (short) 0).putInt(sed + 16, 0);
        b.position(sepx);
        b.putShort((short) 8).put((byte) 162).put((byte) 2).put((byte) 164).putShort((short) 15840).put((byte) 165)
                .putShort((short) 12240);
        int dop = 0xA00;
        pair(b, 1, stsh, stshEnd - stsh);
        pair(b, 6, sed, 4 * 2 + 12);
        pair(b, 12, bte, 10);
        pair(b, 13, bte + 16, 10);
        pair(b, 15, ffn, ffnEnd - ffn);
        pair(b, 31, dop, 84);
        b.putShort(0x18E, (short) 1).putShort(0x190, (short) 1);
        if (drawing) {
            int doa = 0xA80;
            int obj = 0xAA0;
            b.putInt(doa, 0).putInt(doa + 4, 1).putInt(doa + 8, obj).putShort(doa + 12, (short) 1);
            b.position(obj);
            b.putShort((short) 0).putShort((short) (10 + 40 + 38)).put((byte) 2).put((byte) 2).putShort((short) 1)
                    .putShort((short) 0);
            b.putShort((short) 2).putShort((short) 40).putShort((short) 1440).putShort((short) 360)
                    .putShort((short) 2880).putShort((short) 720);
            b.putInt(0x0000FF).putShort((short) 20).putShort((short) 0).putInt(0xFFFFFF).putInt(0xFFFFFF)
                    .putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 72);
            b.putShort((short) 3).putShort((short) 38).putShort((short) 0).putShort((short) 1440)
                    .putShort((short) 4320).putShort((short) 360);
            b.putInt(0x00FF00).putShort((short) 40).putShort((short) 1).putInt(0).putInt(0).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
            int txbx = 0xB00;
            b.putInt(txbx, 0).putInt(txbx + 4, box.length()).putInt(txbx + 8, box.length() + 1);
            pair(b, 38, doa, 14);
            pair(b, 56, txbx, 12);
        }
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new ByteArrayInputStream(b.array()), "WordDocument");
            fs.writeFilesystem(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void pair(ByteBuffer b, int i, int fc, int lcb) {
        int at = i < 38 ? 0x58 + 8 * i : 0x192 + 8 * (i - 38);
        b.putInt(at, fc).putInt(at + 4, lcb);
    }
}
