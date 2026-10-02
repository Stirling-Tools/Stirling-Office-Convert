package stirling.software.officeconvert.topdf.io;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;

final class Pict {

    static final int MAX_ROW_BYTES = 0x3FFF;

    static final int MAX_OPCODES = 500_000;

    static final int MAX_POLY_POINTS = 16_000;

    private final byte[] data;

    private final int start;

    private final boolean v2;

    private final Rectangle2D space;

    private final Rectangle2D frame;

    private Pict(byte[] data, int start, boolean v2, Rectangle2D space, Rectangle2D frame) {
        this.data = data;
        this.start = start;
        this.v2 = v2;
        this.space = space;
        this.frame = frame;
    }

    static boolean sniff(byte[] d) {
        return header(d) >= 0;
    }

    private static int header(byte[] d) {
        for (int at : new int[] {512, 0}) {
            if (d.length >= at + 14 && (version(d, at + 10) != 0) && height(d, at) > 0) {
                return at;
            }
        }
        return -1;
    }

    private static int version(byte[] d, int at) {
        if ((d[at] & 0xFF) == 0x11 && (d[at + 1] & 0xFF) == 0x01) {
            return 1;
        }
        if (d.length >= at + 4 && d[at] == 0 && (d[at + 1] & 0xFF) == 0x11 && (d[at + 2] & 0xFF) == 0x02
                && (d[at + 3] & 0xFF) == 0xFF) {
            return 2;
        }
        return 0;
    }

    private static int height(byte[] d, int at) {
        int top = (short) ((d[at + 2] & 0xFF) << 8 | d[at + 3] & 0xFF);
        int left = (short) ((d[at + 4] & 0xFF) << 8 | d[at + 5] & 0xFF);
        int bottom = (short) ((d[at + 6] & 0xFF) << 8 | d[at + 7] & 0xFF);
        int right = (short) ((d[at + 8] & 0xFF) << 8 | d[at + 9] & 0xFF);
        return bottom > top && right > left ? bottom - top : -1;
    }

    static Pict read(byte[] d) throws IOException {
        int at = header(d);
        if (at < 0) {
            throw new IOException("The picture is not a PICT");
        }
        try {
            PictReader in = new PictReader(d, at + 2);
            Rectangle2D frame = rect(in);
            boolean v2 = version(d, at + 10) == 2;
            Rectangle2D space = frame;
            if (v2) {
                in.skip(4);
                if (in.u16() == 0x0C00) {
                    int kind = in.s16();
                    in.skip(2);
                    if (kind == -2) {
                        in.skip(8);
                        Rectangle2D src = rect(in);
                        if (src.getWidth() > 0 && src.getHeight() > 0) {
                            space = src;
                        }
                    }
                }
            }
            return new Pict(d, at + (v2 ? 14 : 12), v2, space, frame);
        } catch (IllegalStateException e) {
            throw new IOException("The PICT header cannot be read", e);
        }
    }

    Rectangle2D bounds() {
        return new Rectangle2D.Double(0, 0, frame.getWidth(), frame.getHeight());
    }

    void draw(Graphics2D g, Rectangle2D target) {
        AffineTransform saved = g.getTransform();
        Shape clip = g.getClip();
        try {
            g.translate(target.getX(), target.getY());
            g.scale(target.getWidth() / space.getWidth(), target.getHeight() / space.getHeight());
            g.translate(-space.getX(), -space.getY());
            g.clip(space);
            new State(g).run(new PictReader(data, start));
        } finally {
            g.setTransform(saved);
            g.setClip(clip);
        }
    }

    private static Rectangle2D rect(PictReader in) {
        int top = in.s16();
        int left = in.s16();
        int bottom = in.s16();
        int right = in.s16();
        return new Rectangle2D.Double(left, top, right - left, bottom - top);
    }

    private final class State {

        private final Graphics2D g;

        private Color fg = Color.BLACK;

        private Color bg = Color.WHITE;

        private Color pen = Color.BLACK;

        private Color fill = Color.BLACK;

        private Color back = Color.WHITE;

        private double penW = 1;

        private double penH = 1;

        private double x;

        private double y;

        private double textX;

        private double textY;

        private int fontId;

        private String fontName;

        private int size = 12;

        private int face;

        private double ovW;

        private double ovH;

        private long pixels = PictureDecoder.DECODE_PIXELS;

        private Rectangle2D lastRect = new Rectangle2D.Double();

        private Rectangle2D lastRRect = new Rectangle2D.Double();

        private Rectangle2D lastOval = new Rectangle2D.Double();

        private Arc2D lastArc = new Arc2D.Double();

        private Path2D lastPoly = new Path2D.Double();

        private Path2D lastOpenPoly = new Path2D.Double();

        State(Graphics2D g) {
            this.g = g;
        }

        void run(PictReader in) {
            try {
                for (int n = 0; n < MAX_OPCODES && in.more(); n++) {
                    if (v2) {
                        in.align();
                    }
                    int op = v2 ? in.u16() : in.u8();
                    if (op == 0xFF || op == 0xFFFF) {
                        return;
                    }
                    if (n % 1024 == 0 && Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    opcode(in, op);
                }
            } catch (IllegalStateException | IndexOutOfBoundsException | ArithmeticException e) {
                return;
            }
        }

        private void opcode(PictReader in, int op) {
            if (op >= 0x30 && op <= 0x8C) {
                shape(in, op);
                return;
            }
            switch (op) {
                case 0x00, 0x1C, 0x1E, 0x17, 0x18, 0x19 -> {
                }
                case 0x01 -> clip(in);
                case 0x02 -> back = pattern(in);
                case 0x03 -> fontId = in.u16();
                case 0x04 -> face = in.u8();
                case 0x05, 0x08, 0x0D, 0x15, 0x16 -> setWord(op, in.u16());
                case 0x06 -> in.skip(4);
                case 0x07 -> {
                    penH = Math.max(0, in.s16());
                    penW = Math.max(0, in.s16());
                }
                case 0x09 -> pen = pattern(in);
                case 0x0A -> fill = pattern(in);
                case 0x0B -> {
                    ovH = in.s16();
                    ovW = in.s16();
                }
                case 0x0C -> {
                    int dv = in.s16();
                    int dh = in.s16();
                    g.translate(-dh, -dv);
                }
                case 0x0E -> fg = qdColor(in.u32());
                case 0x0F -> bg = qdColor(in.u32());
                case 0x10 -> in.skip(8);
                case 0x11 -> in.skip(v2 ? 2 : 1);
                case 0x12 -> back = pixPattern(in);
                case 0x13 -> pen = pixPattern(in);
                case 0x14 -> fill = pixPattern(in);
                case 0x1A -> {
                    fg = rgb(in);
                    pen = fg;
                    fill = fg;
                }
                case 0x1B -> {
                    bg = rgb(in);
                    back = bg;
                }
                case 0x1D, 0x1F -> in.skip(6);
                case 0x20 -> {
                    double v0 = in.s16();
                    double h0 = in.s16();
                    double v1 = in.s16();
                    double h1 = in.s16();
                    line(h0, v0, h1, v1);
                }
                case 0x21 -> {
                    double v1 = in.s16();
                    double h1 = in.s16();
                    line(x, y, h1, v1);
                }
                case 0x22 -> {
                    double v0 = in.s16();
                    double h0 = in.s16();
                    int dh = (byte) in.u8();
                    int dv = (byte) in.u8();
                    line(h0, v0, h0 + dh, v0 + dv);
                }
                case 0x23 -> {
                    int dh = (byte) in.u8();
                    int dv = (byte) in.u8();
                    line(x, y, x + dh, y + dv);
                }
                case 0x28 -> {
                    textY = in.s16();
                    textX = in.s16();
                    text(in);
                }
                case 0x29 -> {
                    textX += in.u8();
                    text(in);
                }
                case 0x2A -> {
                    textY += in.u8();
                    text(in);
                }
                case 0x2B -> {
                    textX += in.u8();
                    textY += in.u8();
                    text(in);
                }
                case 0x2C -> fontName(in);
                case 0x90, 0x91, 0x98, 0x99, 0x9A, 0x9B -> bits(in, op);
                case 0xA0 -> in.skip(2);
                case 0xA1 -> {
                    in.skip(2);
                    in.skip(in.u16());
                }
                case 0x0C00 -> in.skip(24);
                case 0x8200 -> quickTime(in);
                default -> reserved(in, op);
            }
        }

        private void setWord(int op, int v) {
            if (op == 0x0D) {
                size = v > 0 && v < 2000 ? v : 12;
            }
        }

        private void reserved(PictReader in, int op) {
            if (op >= 0x24 && op <= 0x2F || op >= 0x92 && op <= 0x97 || op >= 0x9C && op <= 0x9F
                    || op >= 0xA2 && op <= 0xAF) {
                in.skip(in.u16());
            } else if (op >= 0xB0 && op <= 0xCF || op >= 0x8000 && op <= 0x80FF) {
                return;
            } else if (op >= 0xD0 && op <= 0xFE || op >= 0x8100) {
                in.skip(in.u32());
            } else if (op >= 0x100 && op <= 0x7FFF) {
                in.skip((op >> 8) * 2L);
            } else {
                throw new IllegalStateException("unknown opcode " + op);
            }
        }

        private void shape(PictReader in, int op) {
            int kind = op & 0xF8;
            int verb = op & 0x07;
            boolean same = (op & 0x08) != 0;
            if (verb > 4) {
                switch (kind & 0xF0) {
                    case 0x30, 0x40, 0x50 -> in.skip(same ? 0 : 8);
                    case 0x60 -> in.skip(same ? 4 : 12);
                    default -> in.skip(same ? 0 : in.u16() - 2);
                }
                return;
            }
            Shape s;
            switch (kind & 0xF0) {
                case 0x30 -> s = lastRect = same ? lastRect : rect(in);
                case 0x40 -> {
                    lastRRect = same ? lastRRect : rect(in);
                    s = new RoundRectangle2D.Double(lastRRect.getX(), lastRRect.getY(), lastRRect.getWidth(),
                            lastRRect.getHeight(), ovW, ovH);
                }
                case 0x50 -> {
                    lastOval = same ? lastOval : rect(in);
                    s = new Ellipse2D.Double(lastOval.getX(), lastOval.getY(), lastOval.getWidth(),
                            lastOval.getHeight());
                }
                case 0x60 -> {
                    Rectangle2D r = same ? lastArc.getFrame() : rect(in);
                    int startAngle = in.s16();
                    int arc = in.s16();
                    lastArc = new Arc2D.Double(r, 90 - startAngle, -arc, verb == 0 ? Arc2D.OPEN : Arc2D.PIE);
                    s = lastArc;
                }
                case 0x70 -> {
                    if (!same) {
                        polygon(in);
                    }
                    s = verb == 0 ? lastOpenPoly : lastPoly;
                }
                default -> {
                    s = same ? lastRect : region(in);
                }
            }
            paint(s, verb);
        }

        private Shape region(PictReader in) {
            int size = in.u16();
            Rectangle2D r = rect(in);
            in.skip(Math.max(0, size - 10));
            lastRect = r;
            return size <= 10 ? r : new Rectangle2D.Double();
        }

        private void polygon(PictReader in) {
            int size = in.u16();
            in.skip(8);
            int n = Math.min(MAX_POLY_POINTS, Math.max(0, (size - 10) / 4));
            Path2D open = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                int v = in.s16();
                int h = in.s16();
                if (i == 0) {
                    open.moveTo(h, v);
                } else {
                    open.lineTo(h, v);
                }
            }
            in.skip(Math.max(0, size - 10 - 4L * n));
            lastOpenPoly = open;
            lastPoly = new Path2D.Double(open);
            if (n > 0) {
                lastPoly.closePath();
            }
        }

        private void paint(Shape s, int verb) {
            switch (verb) {
                case 0 -> {
                    g.setColor(pen);
                    g.setStroke(new BasicStroke((float) Math.max(0.5, Math.max(penW, penH))));
                    if (s instanceof Rectangle2D r) {
                        double w = Math.max(penW, penH) / 2;
                        g.draw(new Rectangle2D.Double(r.getX() + w, r.getY() + w, Math.max(0, r.getWidth() - 2 * w),
                                Math.max(0, r.getHeight() - 2 * w)));
                    } else {
                        g.draw(s);
                    }
                }
                case 1 -> fill(s, pen);
                case 2 -> fill(s, back);
                case 4 -> fill(s, fill);
                default -> {
                }
            }
        }

        private void fill(Shape s, Color c) {
            g.setColor(c);
            g.fill(s);
        }

        private void line(double h0, double v0, double h1, double v1) {
            x = h1;
            y = v1;
            if (penW <= 0 || penH <= 0) {
                return;
            }
            g.setColor(pen);
            g.setStroke(new BasicStroke((float) Math.max(penW, penH), BasicStroke.CAP_SQUARE,
                    BasicStroke.JOIN_MITER));
            g.draw(new Line2D.Double(h0 + penW / 2, v0 + penH / 2, h1 + penW / 2, v1 + penH / 2));
        }

        private void text(PictReader in) {
            int n = in.u8();
            String s = PictText.decode(in.bytes(n));
            if (s.isBlank()) {
                return;
            }
            g.setColor(fg);
            g.setFont(new Font(PictText.family(fontId, fontName), PictText.style(face), 1).deriveFont((float) size));
            g.drawString(s, (float) textX, (float) textY);
        }

        private void fontName(PictReader in) {
            int len = in.u16();
            int end = in.position() + len;
            fontId = in.u16();
            int n = in.u8();
            fontName = PictText.decode(in.bytes(Math.min(n, Math.max(0, len - 3))));
            in.seek(end);
        }

        private void clip(PictReader in) {
            int size = in.u16();
            Rectangle2D r = rect(in);
            in.skip(Math.max(0, size - 10));
            if (size <= 10 && r.getWidth() > 0 && r.getHeight() > 0) {
                g.setClip(space);
                g.clip(r);
            }
        }

        private void bits(PictReader in, int op) {
            PictBits.Image b = PictBits.read(in, op, fg, bg, pixels);
            pixels -= (long) b.image().getWidth() * b.image().getHeight();
            int sw = b.srcRight() - b.srcLeft();
            int sh = b.srcBottom() - b.srcTop();
            BufferedImage img = b.image();
            int x0 = Math.max(0, Math.min(img.getWidth() - 1, b.srcLeft()));
            int y0 = Math.max(0, Math.min(img.getHeight() - 1, b.srcTop()));
            int w = Math.max(1, Math.min(img.getWidth() - x0, sw));
            int h = Math.max(1, Math.min(img.getHeight() - y0, sh));
            if (w != img.getWidth() || h != img.getHeight()) {
                img = img.getSubimage(x0, y0, w, h);
            }
            int dw = b.dstRight() - b.dstLeft();
            int dh = b.dstBottom() - b.dstTop();
            if (dw > 0 && dh > 0) {
                g.drawImage(img, b.dstLeft(), b.dstTop(), dw, dh, null);
            }
        }

        private void quickTime(PictReader in) {
            long size = in.u32();
            int end = (int) Math.min(Integer.MAX_VALUE, in.position() + size);
            int at = in.position();
            in.skip(2);
            double[] m = new double[9];
            for (int i = 0; i < 9; i++) {
                long v = in.u32();
                m[i] = (i % 3 == 2 ? (int) v / (double) (1 << 30) : (int) v / 65536.0);
            }
            long matte = in.u32();
            in.skip(8);
            in.skip(2);
            Rectangle2D src = rect(in);
            in.seek(at);
            byte[] payload = in.bytes(end - at);
            int jpeg = jpegStart(payload, 50 + (int) Math.min(matte, payload.length));
            if (jpeg >= 0 && src.getWidth() > 0 && src.getHeight() > 0) {
                try {
                    byte[] j = Arrays.copyOfRange(payload, jpeg, payload.length);
                    BufferedImage img = PictureDecoder.readRaster(j, PictureDecoder.DECODE_PIXELS);
                    double dx = src.getX() * m[0] + m[6];
                    double dy = src.getY() * m[4] + m[7];
                    double dw = src.getWidth() * m[0];
                    double dh = src.getHeight() * m[4];
                    if (dw > 0 && dh > 0) {
                        g.drawImage(img, (int) Math.round(dx), (int) Math.round(dy), (int) Math.round(dw),
                                (int) Math.round(dh), null);
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }

        private Color pattern(PictReader in) {
            int ones = 0;
            for (int i = 0; i < 8; i++) {
                ones += Integer.bitCount(in.u8());
            }
            return mix(ones / 64.0);
        }

        private Color pixPattern(PictReader in) {
            int type = in.u16();
            Color plain = pattern(in);
            if (type == 2) {
                return rgb(in);
            }
            if (type != 1) {
                throw new IllegalStateException("pattern type " + type);
            }
            BufferedImage img = PictBits.pattern(in, fg, bg, pixels);
            pixels -= (long) img.getWidth() * img.getHeight();
            long r = 0;
            long gr = 0;
            long b = 0;
            int n = img.getWidth() * img.getHeight();
            for (int yy = 0; yy < img.getHeight(); yy++) {
                for (int xx = 0; xx < img.getWidth(); xx++) {
                    int p = img.getRGB(xx, yy);
                    r += p >> 16 & 0xFF;
                    gr += p >> 8 & 0xFF;
                    b += p & 0xFF;
                }
            }
            return n == 0 ? plain : new Color((int) (r / n), (int) (gr / n), (int) (b / n));
        }

        private Color mix(double d) {
            return new Color((int) Math.round(bg.getRed() + (fg.getRed() - bg.getRed()) * d),
                    (int) Math.round(bg.getGreen() + (fg.getGreen() - bg.getGreen()) * d),
                    (int) Math.round(bg.getBlue() + (fg.getBlue() - bg.getBlue()) * d));
        }
    }

    private static int jpegStart(byte[] d, int from) {
        for (int i = Math.max(0, from); i + 2 < d.length; i++) {
            if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == 0xD8 && (d[i + 2] & 0xFF) == 0xFF) {
                return i;
            }
        }
        return -1;
    }

    private static Color rgb(PictReader in) {
        return new Color(in.u16() >> 8, in.u16() >> 8, in.u16() >> 8);
    }

    private static Color qdColor(long c) {
        return switch ((int) c) {
            case 30 -> Color.WHITE;
            case 205 -> Color.RED;
            case 341 -> Color.GREEN;
            case 409 -> Color.BLUE;
            case 273 -> Color.CYAN;
            case 137 -> Color.MAGENTA;
            case 69 -> Color.YELLOW;
            default -> Color.BLACK;
        };
    }
}
