package stirling.software.officeconvert.topdf.biff5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Biff4Upgrade {

    private static final int FORMAT_BASE = 200;

    private static final int MAX_XFS = 4000;

    private final byte[] in;

    private final int version;

    private final Records globals = new Records();

    private final Records sheet = new Records();

    private final Map<Integer, Integer> biff2Xfs = new LinkedHashMap<>();

    private int formats;

    private int xfs;

    private int fonts;

    private byte[] lastFont;

    private Biff4Upgrade(byte[] in) {
        this.in = in;
        int bof = u16(0);
        this.version = bof == 0x0009 ? 2 : bof == 0x0209 ? 3 : 4;
    }

    public static byte[] upgrade(byte[] stream) throws IOException {
        Biff4Upgrade u = new Biff4Upgrade(stream);
        int type = u.u16(6);
        if (u.version == 4 && type == 0x0100) {
            throw new IOException("The file is an Excel 4.0 workbook (several sheets in one file), which is not"
                    + " supported; save it as .xlsx");
        }
        if (type == 0x0020 || type == 0x0040) {
            throw new IOException("The file is an Excel 4.0 or older chart or macro sheet, which is not supported");
        }
        u.read();
        return u.assemble();
    }

    private void read() throws IOException {
        int at = 0;
        int count = 0;
        while (at + 4 <= in.length) {
            if ((++count & 4095) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            int op = u16(at);
            int len = u16(at + 2);
            int body = at + 4;
            if (body + len > in.length) {
                break;
            }
            if (op == 0x000A && count > 1) {
                break;
            }
            if (count > 1 || op != (version == 2 ? 0x0009 : version == 3 ? 0x0209 : 0x0409)) {
                record(op, body, len);
            }
            at = body + len;
        }
        if (xfs == 0 && version != 2) {
            globals.add(0x00E0, xf(0, 0, 0xFFF5, 0x20, 0xCE00, 0));
            xfs++;
        }
    }

    private void record(int op, int at, int len) {
        switch (op) {
            case 0x002F, 0x0042, 0x0022, 0x0092 -> globals.copy(op, in, at, len);
            case 0x0031, 0x0231 -> font(at, len);
            case 0x0045 -> fontColor(at, len);
            case 0x001E, 0x041E -> format(at, len);
            case 0x0043, 0x0243, 0x0443 -> cellFormat(op, at, len);
            case 0x0014, 0x0015, 0x0026, 0x0027, 0x0028, 0x0029, 0x002A, 0x002B, 0x0083, 0x0084, 0x00A1, 0x0055,
                    0x0099, 0x001A, 0x001B, 0x0201, 0x0203, 0x0204, 0x0205, 0x0207, 0x0208, 0x027E, 0x007D, 0x0225,
                    0x0406 -> sheet.copy(op, in, at, len);
            case 0x0206 -> sheet.copy(0x0006, in, at, len);
            case 0x0025 -> {
                if (len >= 2) {
                    sheet.add(0x0225, bytes(0, 0, in[at], in[at + 1]));
                }
            }
            case 0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006 -> biff2Cell(op, at, len);
            case 0x0007 -> {
                if (len >= 1) {
                    int n = Math.min(in[at] & 0xFF, len - 1);
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    le16(b, n);
                    b.write(in, at + 1, n);
                    sheet.add(0x0207, b.toByteArray());
                }
            }
            case 0x0008 -> biff2Row(at, len);
            case 0x0024 -> {
                if (len >= 4) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    le16(b, in[at] & 0xFF);
                    le16(b, in[at + 1] & 0xFF);
                    b.write(in[at + 2]);
                    b.write(in[at + 3]);
                    le16(b, 15);
                    le16(b, 0);
                    le16(b, 0);
                    sheet.add(0x007D, b.toByteArray());
                }
            }
            default -> {
            }
        }
    }

    private void font(int at, int len) {
        if (len < 5) {
            return;
        }
        int height = u16(at);
        int options = u16(at + 2);
        int color = 0x7FFF;
        int nameAt = at + 4;
        if (version > 2) {
            if (len < 7) {
                return;
            }
            color = u16(at + 4);
            nameAt = at + 6;
        }
        int n = Math.min(in[nameAt] & 0xFF, at + len - nameAt - 1);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le16(b, height);
        le16(b, options & 0x0A);
        le16(b, color);
        le16(b, (options & 0x01) != 0 ? 700 : 400);
        le16(b, 0);
        b.write((options & 0x04) != 0 ? 1 : 0);
        b.write(0);
        b.write(0);
        b.write(0);
        b.write(Math.max(0, n));
        if (n > 0) {
            b.write(in, nameAt + 1, n);
        }
        lastFont = b.toByteArray();
        globals.add(0x0031, lastFont);
        fonts++;
    }

    private void fontColor(int at, int len) {
        if (lastFont != null && len >= 2) {
            lastFont[4] = in[at];
            lastFont[5] = in[at + 1];
        }
    }

    private void format(int at, int len) {
        int from = version == 4 ? at + 2 : at;
        if (from >= at + len) {
            return;
        }
        int n = Math.min(in[from] & 0xFF, at + len - from - 1);
        String code = n <= 0 ? "" : new String(in, from + 1, n, StandardCharsets.ISO_8859_1);
        int id = formats++;
        if (code.equalsIgnoreCase("General")) {
            return;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le16(b, FORMAT_BASE + id);
        b.write(Math.max(0, n));
        if (n > 0) {
            b.write(in, from + 1, n);
        }
        globals.add(0x041E, b.toByteArray());
    }

    private int formatId(int index) {
        return index == 0 ? 0 : FORMAT_BASE + index;
    }

    private void cellFormat(int op, int at, int len) {
        if (xfs >= MAX_XFS) {
            return;
        }
        if (op == 0x0043) {
            return;
        }
        if (len < 12) {
            return;
        }
        int font = in[at] & 0xFF;
        int format = formatId(in[at + 1] & 0xFF);
        int typeProt;
        int align;
        if (op == 0x0443) {
            typeProt = u16(at + 2);
            int a = in[at + 4] & 0xFF;
            align = (a & 0x0F) | ((a >> 4) & 0x03) << 4 | ((a >> 6) & 0x03) << 8;
        } else {
            int prot = in[at + 2] & 0x07;
            int parentAlign = u16(at + 4);
            typeProt = prot | (parentAlign >> 4) << 4;
            align = (parentAlign & 0x0F) | 2 << 4;
        }
        globals.add(0x00E0, xf(font, format, typeProt, align, u16(at + 6), (long) i32(at + 8) & 0xFFFFFFFFL));
        xfs++;
    }

    private static int color(int c) {
        return c == 24 ? 64 : c == 25 ? 65 : c;
    }

    private static byte[] xf(int font, int format, int typeProt, int align, int area, long border) {
        int pattern = area & 0x3F;
        int fg = color(area >> 6 & 0x1F);
        int bg = color(area >> 11 & 0x1F);
        int topStyle = (int) (border & 0x07);
        int topColor = color((int) (border >> 3 & 0x1F));
        int leftStyle = (int) (border >> 8 & 0x07);
        int leftColor = color((int) (border >> 11 & 0x1F));
        int bottomStyle = (int) (border >> 16 & 0x07);
        int bottomColor = color((int) (border >> 19 & 0x1F));
        int rightStyle = (int) (border >> 24 & 0x07);
        int rightColor = color((int) (border >> 27 & 0x1F));
        long area5 = fg & 0x7F | (long) (bg & 0x7F) << 7 | (long) pattern << 16 | (long) bottomStyle << 22
                | (long) (bottomColor & 0x7F) << 25;
        long border5 = topStyle | (long) leftStyle << 3 | (long) rightStyle << 6 | (long) (topColor & 0x7F) << 9
                | (long) (leftColor & 0x7F) << 16 | (long) (rightColor & 0x7F) << 23;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le16(b, font);
        le16(b, format);
        le16(b, typeProt);
        le16(b, align);
        le32(b, (int) area5);
        le32(b, (int) border5);
        return b.toByteArray();
    }

    private int biff2Xf(int attrAt) {
        int b1 = in[attrAt + 1] & 0xFF;
        int b2 = in[attrAt + 2] & 0xFF;
        int key = b1 << 8 | b2;
        Integer known = biff2Xfs.get(key);
        if (known != null) {
            return known;
        }
        if (biff2Xfs.isEmpty()) {
            globals.add(0x00E0, xf(0, 0, 0xFFF5, 0x20, 0xCE00, 0));
            xfs++;
        }
        if (xfs >= MAX_XFS) {
            return 0;
        }
        long border = 0;
        int auto = 24;
        if ((b2 & 0x08) != 0) {
            border |= 1L << 8 | (long) auto << 11;
        }
        if ((b2 & 0x10) != 0) {
            border |= 1L << 24 | (long) auto << 27;
        }
        if ((b2 & 0x20) != 0) {
            border |= 1 | auto << 3;
        }
        if ((b2 & 0x40) != 0) {
            border |= 1L << 16 | (long) auto << 19;
        }
        int area = (b2 & 0x80) != 0 ? 17 | 24 << 6 | 25 << 11 : 24 << 6 | 25 << 11;
        int font = b1 >> 6 & 0x03;
        globals.add(0x00E0, xf(font, formatId(b1 & 0x3F), 0x0001, (b2 & 0x07) | 2 << 4, area,
                border));
        int index = xfs++;
        biff2Xfs.put(key, index);
        return index;
    }

    private void biff2Cell(int op, int at, int len) {
        if (len < 7) {
            return;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(in, at, 4);
        le16(b, biff2Xf(at + 4));
        int v = at + 7;
        switch (op) {
            case 0x0001 -> sheet.add(0x0201, b.toByteArray());
            case 0x0002 -> {
                if (len >= 9) {
                    le64(b, Double.doubleToLongBits(u16(v)));
                    sheet.add(0x0203, b.toByteArray());
                }
            }
            case 0x0003 -> {
                if (len >= 15) {
                    b.write(in, v, 8);
                    sheet.add(0x0203, b.toByteArray());
                }
            }
            case 0x0004 -> {
                if (len >= 8) {
                    int n = Math.min(in[v] & 0xFF, at + len - v - 1);
                    le16(b, Math.max(0, n));
                    if (n > 0) {
                        b.write(in, v + 1, n);
                    }
                    sheet.add(0x0204, b.toByteArray());
                }
            }
            case 0x0005 -> {
                if (len >= 9) {
                    b.write(in[v]);
                    b.write(in[v + 1]);
                    sheet.add(0x0205, b.toByteArray());
                }
            }
            default -> {
                if (len >= 15) {
                    b.write(in, v, 8);
                    b.writeBytes(new byte[8]);
                    sheet.add(0x0006, b.toByteArray());
                }
            }
        }
    }

    private void biff2Row(int at, int len) {
        if (len < 8) {
            return;
        }
        int height = u16(at + 6);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(in, at, 6);
        le16(b, height & 0x7FFF);
        le16(b, 0);
        le16(b, 0);
        le16(b, (height & 0x8000) != 0 ? 0x0100 : 0x0140);
        le16(b, 15);
        sheet.add(0x0208, b.toByteArray());
    }

    private byte[] assemble() {
        Records head = new Records();
        head.add(0x0809, bytes(0x00, 0x05, 0x05, 0x00, 0, 0, 0, 0));
        Records tail = new Records();
        byte[] name = "Sheet1".getBytes(StandardCharsets.ISO_8859_1);
        int boundSize = 4 + 7 + name.length;
        int sheetAt = head.size() + globals.size() + boundSize + 4;
        ByteArrayOutputStream bound = new ByteArrayOutputStream();
        le32(bound, sheetAt);
        bound.write(0);
        bound.write(0);
        bound.write(name.length);
        bound.writeBytes(name);
        tail.add(0x0085, bound.toByteArray());
        tail.add(0x000A, new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.bytes());
        out.writeBytes(globals.bytes());
        out.writeBytes(tail.bytes());
        Records s = new Records();
        s.add(0x0809, bytes(0x00, 0x05, 0x10, 0x00, 0, 0, 0, 0));
        out.writeBytes(s.bytes());
        out.writeBytes(sheet.bytes());
        Records end = new Records();
        end.add(0x000A, new byte[0]);
        out.writeBytes(end.bytes());
        return out.toByteArray();
    }

    private static byte[] bytes(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private int u16(int at) {
        return at + 1 < in.length ? (in[at] & 0xFF) | (in[at + 1] & 0xFF) << 8 : 0;
    }

    private int i32(int at) {
        return u16(at) | u16(at + 2) << 16;
    }

    private static void le16(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write(v >> 8 & 0xFF);
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        le16(b, v & 0xFFFF);
        le16(b, v >>> 16);
    }

    private static void le64(ByteArrayOutputStream b, long v) {
        le32(b, (int) v);
        le32(b, (int) (v >>> 32));
    }

    private static final class Records {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void add(int op, byte[] data) {
            int n = Math.min(data.length, 0xFFFF);
            le16(out, op);
            le16(out, n);
            out.write(data, 0, n);
        }

        void copy(int op, byte[] src, int at, int len) {
            le16(out, op);
            le16(out, len);
            out.write(src, at, len);
        }

        int size() {
            return out.size();
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
