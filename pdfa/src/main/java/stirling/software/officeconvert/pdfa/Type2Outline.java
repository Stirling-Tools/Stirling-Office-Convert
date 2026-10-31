package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;

import stirling.software.officeconvert.fonts.CffCodec;

final class Type2Outline {

    interface Accents {
        GeneralPath glyph(int standardCode) throws IOException;
    }

    private static final int MAX_DEPTH = 10;

    private static final int MAX_STACK = 513;

    private static final long MAX_OPERATIONS = 200_000;

    private final byte[][] globalSubrs;

    private final int[] regionCounts;

    private final Accents accents;

    private GeneralPath accent;


    private final GeneralPath path = new GeneralPath(GeneralPath.WIND_NON_ZERO);

    private final double[] stack = new double[MAX_STACK];

    private final byte[][] local;

    private int sp;

    private int stems;

    private int vsindex;

    private double x;

    private double y;

    private boolean open;

    private long operations;

    private boolean ended;

    private Type2Outline(byte[][] globalSubrs, byte[][] local, int[] regionCounts, int vsindex, Accents accents) {
        this.globalSubrs = globalSubrs == null ? new byte[0][] : globalSubrs;
        this.local = local == null ? new byte[0][] : local;
        this.regionCounts = regionCounts == null ? new int[0] : regionCounts;
        this.vsindex = vsindex;
        this.accents = accents;
    }

    static GeneralPath path(byte[] charString, byte[][] globalSubrs, byte[][] localSubrs, int[] regionCounts,
            int vsindex, Accents accents) throws IOException {
        Type2Outline in = new Type2Outline(globalSubrs, localSubrs, regionCounts, vsindex, accents);
        try {
            in.run(charString, 0);
        } catch (RuntimeException e) {
            throw new IOException("The charstring cannot be read: " + e.getMessage(), e);
        }
        in.close();
        if (in.accent != null) {
            in.path.append(in.accent, false);
        }
        return in.path;
    }

    void close() {
        if (open) {
            path.closePath();
            open = false;
        }
    }

    private void push(double v) {
        if (sp >= MAX_STACK) {
            throw new IllegalStateException("stack overflow");
        }
        stack[sp++] = v;
    }

    private void moveTo(double dx, double dy) {
        close();
        x += dx;
        y += dy;
        path.moveTo(x, y);
        open = true;
    }

    private void lineTo(double dx, double dy) {
        x += dx;
        y += dy;
        path.lineTo(x, y);
    }

    private void curveTo(double a, double b, double c, double d, double e, double f) {
        double x1 = x + a;
        double y1 = y + b;
        double x2 = x1 + c;
        double y2 = y1 + d;
        x = x2 + e;
        y = y2 + f;
        path.curveTo(x1, y1, x2, y2, x, y);
    }

    void run(byte[] cs, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("subroutines nest too deep");
        }
        int at = 0;
        while (at < cs.length) {
            if (++operations > MAX_OPERATIONS) {
                throw new IllegalStateException("too many operations");
            }
            int b0 = cs[at] & 0xFF;
            if (b0 >= 32 && b0 <= 246) {
                push(b0 - 139);
                at++;
                continue;
            }
            if (b0 >= 247 && b0 <= 254) {
                push(CffCodec.twoByteInt(b0, cs[at + 1] & 0xFF));
                at += 2;
                continue;
            }
            if (b0 == 28) {
                push((short) ((cs[at + 1] & 0xFF) << 8 | cs[at + 2] & 0xFF));
                at += 3;
                continue;
            }
            if (b0 == 255) {
                int v = (cs[at + 1] & 0xFF) << 24 | (cs[at + 2] & 0xFF) << 16 | (cs[at + 3] & 0xFF) << 8
                        | cs[at + 4] & 0xFF;
                push(v / 65536.0);
                at += 5;
                continue;
            }
            at++;
            switch (b0) {
                case 1, 3, 18, 23 -> {
                    stems += sp / 2;
                    sp = 0;
                }
                case 19, 20 -> {
                    stems += sp / 2;
                    sp = 0;
                    at += (stems + 7) / 8;
                }
                case 21 -> {
                    moveTo(stack[sp - 2], stack[sp - 1]);
                    sp = 0;
                }
                case 22 -> {
                    moveTo(stack[sp - 1], 0);
                    sp = 0;
                }
                case 4 -> {
                    moveTo(0, stack[sp - 1]);
                    sp = 0;
                }
                case 5 -> {
                    for (int i = 0; i + 1 < sp; i += 2) {
                        lineTo(stack[i], stack[i + 1]);
                    }
                    sp = 0;
                }
                case 6, 7 -> {
                    boolean horizontal = b0 == 6;
                    for (int i = 0; i < sp; i++) {
                        if (horizontal) {
                            lineTo(stack[i], 0);
                        } else {
                            lineTo(0, stack[i]);
                        }
                        horizontal = !horizontal;
                    }
                    sp = 0;
                }
                case 8 -> {
                    for (int i = 0; i + 5 < sp; i += 6) {
                        curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5]);
                    }
                    sp = 0;
                }
                case 24 -> {
                    int i = 0;
                    for (; i + 7 < sp; i += 6) {
                        curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5]);
                    }
                    if (i + 1 < sp) {
                        lineTo(stack[i], stack[i + 1]);
                    }
                    sp = 0;
                }
                case 25 -> {
                    int i = 0;
                    for (; i + 7 < sp; i += 2) {
                        lineTo(stack[i], stack[i + 1]);
                    }
                    if (i + 5 < sp) {
                        curveTo(stack[i], stack[i + 1], stack[i + 2], stack[i + 3], stack[i + 4], stack[i + 5]);
                    }
                    sp = 0;
                }
                case 26 -> {
                    int i = 0;
                    double dx1 = 0;
                    if (sp % 4 == 1) {
                        dx1 = stack[0];
                        i = 1;
                    }
                    for (; i + 3 < sp; i += 4) {
                        curveTo(dx1, stack[i], stack[i + 1], stack[i + 2], 0, stack[i + 3]);
                        dx1 = 0;
                    }
                    sp = 0;
                }
                case 27 -> {
                    int i = 0;
                    double dy1 = 0;
                    if (sp % 4 == 1) {
                        dy1 = stack[0];
                        i = 1;
                    }
                    for (; i + 3 < sp; i += 4) {
                        curveTo(stack[i], dy1, stack[i + 1], stack[i + 2], stack[i + 3], 0);
                        dy1 = 0;
                    }
                    sp = 0;
                }
                case 30, 31 -> {
                    boolean horizontal = b0 == 31;
                    int i = 0;
                    while (i + 3 < sp) {
                        double last = i + 4 == sp - 1 ? stack[i + 4] : 0;
                        if (horizontal) {
                            curveTo(stack[i], 0, stack[i + 1], stack[i + 2], last, stack[i + 3]);
                        } else {
                            curveTo(0, stack[i], stack[i + 1], stack[i + 2], stack[i + 3], last);
                        }
                        i += 4;
                        horizontal = !horizontal;
                    }
                    sp = 0;
                }
                case 10, 29 -> {
                    byte[][] subrs = b0 == 10 ? local : globalSubrs;
                    int n = (int) stack[--sp] + CffCodec.subrBias(subrs.length);
                    if (n < 0 || n >= subrs.length) {
                        throw new IllegalStateException("subroutine " + n + " missing");
                    }
                    run(subrs[n], depth + 1);
                    if (ended) {
                        return;
                    }
                }
                case 11 -> {
                    return;
                }
                case 14 -> {
                    if (sp >= 4 && accents != null) {
                        seac(stack[sp - 4], stack[sp - 3], (int) stack[sp - 2], (int) stack[sp - 1]);
                    }
                    sp = 0;
                    ended = true;
                    return;
                }
                case 15 -> {
                    vsindex = (int) stack[--sp];
                }
                case 16 -> {
                    int n = (int) stack[--sp];
                    int k = vsindex >= 0 && vsindex < regionCounts.length ? regionCounts[vsindex] : 0;
                    int base = sp - n * (k + 1);
                    if (n < 0 || base < 0) {
                        throw new IllegalStateException("blend operands missing");
                    }
                    sp = base + n;
                }
                case 12 -> {
                    int b1 = cs[at++] & 0xFF;
                    flex(b1);
                    sp = 0;
                }
                default -> sp = 0;
            }
        }
    }

    private void seac(double adx, double ady, int base, int accentCode) {
        try {
            GeneralPath b = accents.glyph(base);
            GeneralPath a = accents.glyph(accentCode);
            close();
            if (b != null) {
                path.append(b, false);
            }
            if (a != null) {
                a.transform(AffineTransform.getTranslateInstance(adx, ady));
                accent = a;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void flex(int op) {
        double[] s = stack;
        switch (op) {
            case 35 -> {
                curveTo(s[0], s[1], s[2], s[3], s[4], s[5]);
                curveTo(s[6], s[7], s[8], s[9], s[10], s[11]);
            }
            case 34 -> {
                double y0 = y;
                curveTo(s[0], 0, s[1], s[2], s[3], 0);
                curveTo(s[4], 0, s[5], y0 - y, s[6], 0);
            }
            case 36 -> {
                double y0 = y;
                curveTo(s[0], s[1], s[2], s[3], s[4], 0);
                curveTo(s[5], 0, s[6], s[7], s[8], y0 - y - s[7]);
            }
            case 37 -> {
                double x0 = x;
                double y0 = y;
                double dx = s[0] + s[2] + s[4] + s[6] + s[8];
                double dy = s[1] + s[3] + s[5] + s[7] + s[9];
                curveTo(s[0], s[1], s[2], s[3], s[4], s[5]);
                if (Math.abs(dx) > Math.abs(dy)) {
                    curveTo(s[6], s[7], s[8], s[9], s[10], y0 - y - s[7] - s[9]);
                } else {
                    curveTo(s[6], s[7], s[8], s[9], x0 - x - s[6] - s[8], s[10]);
                }
            }
            default -> {
            }
        }
    }
}
