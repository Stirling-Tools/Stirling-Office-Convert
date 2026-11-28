package stirling.software.officeconvert.topdf.xps;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;

final class XpsOps {

    private final OutputStream out;

    final PDResources resources;

    final Rectangle2D bounds;

    private final byte[] buf = new byte[1 << 15];

    private int len;

    private final Map<COSBase, COSName> names = new IdentityHashMap<>();

    private int counter;

    AffineTransform ctm = new AffineTransform();

    private final Deque<AffineTransform> saved = new ArrayDeque<>();

    private boolean shared;

    private int pending;

    private boolean quiet;

    private boolean pathOpen;

    private final double[] coords = new double[6];

    private final float[] fillRgb = new float[3];

    private final float[] strokeRgb = new float[3];

    private float[] fill;

    private COSName fillSpace;

    private COSName strokeSpace;

    private float[] strokeColor;

    private long bytes;

    static final int LEVEL = 1;

    static OutputStream flate(COSStream stream) throws IOException {
        stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        Deflater deflater = new Deflater(LEVEL);
        return new DeflaterOutputStream(stream.createRawOutputStream(), deflater, 1 << 16) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    deflater.end();
                }
            }
        };
    }

    XpsOps(OutputStream out, PDResources resources, Rectangle2D bounds) {
        this.out = out;
        this.resources = resources;
        this.bounds = bounds;
    }

    long written() {
        return bytes + len;
    }

    int depth() {
        return saved.size();
    }

    void q() throws IOException {
        saved.push(ctm);
        shared = true;
        pending++;
    }

    void Q() throws IOException {
        if (saved.isEmpty()) {
            return;
        }
        ctm = saved.pop();
        shared = true;
        if (pending > 0) {
            pending--;
            return;
        }
        fill = null;
        strokeColor = null;
        op("Q\n");
    }

    void begin() throws IOException {
        if (pending == 0) {
            return;
        }
        if (pathOpen) {
            throw new IllegalStateException("A graphics state was saved inside a path");
        }
        int n = pending;
        pending = 0;
        for (int i = 0; i < n; i++) {
            put('q');
            put('\n');
        }
    }

    private void guard() throws IOException {
        if (pending > 0 && !quiet) {
            begin();
        }
    }

    void cm(AffineTransform t) throws IOException {
        if (t == null || t.isIdentity()) {
            return;
        }
        precise(t.getScaleX()).precise(t.getShearY()).precise(t.getShearX()).precise(t.getScaleY())
                .precise(t.getTranslateX()).precise(t.getTranslateY()).op("cm\n");
        if (shared) {
            ctm = new AffineTransform(ctm);
            shared = false;
        }
        ctm.concatenate(t);
    }

    XpsOps num(double v) throws IOException {
        return num(v, 10_000, 4);
    }

    XpsOps precise(double v) throws IOException {
        return num(v, 10_000_000, 7);
    }

    private XpsOps num(double v, int unit, int places) throws IOException {
        guard();
        if (!Double.isFinite(v)) {
            v = 0;
        }
        v = Math.max(-1e9, Math.min(1e9, v));
        long scaled = Math.round(v * unit);
        if (len + 32 > buf.length) {
            flush();
        }
        byte[] b = buf;
        int at = len;
        if (scaled < 0) {
            b[at++] = '-';
            scaled = -scaled;
        }
        long whole = scaled / unit;
        int frac = (int) (scaled % unit);
        at = digits(b, at, whole);
        if (frac != 0) {
            b[at++] = '.';
            for (int div = unit / 10; div > 0 && frac != 0; div /= 10) {
                b[at++] = (byte) ('0' + frac / div);
                frac %= div;
            }
        }
        b[at++] = ' ';
        len = at;
        return this;
    }

    XpsOps integer(long v) throws IOException {
        guard();
        if (len + 24 > buf.length) {
            flush();
        }
        int at = len;
        if (v < 0) {
            buf[at++] = '-';
            v = -v;
        }
        at = digits(buf, at, v);
        buf[at++] = ' ';
        len = at;
        return this;
    }

    private static int digits(byte[] b, int at, long v) {
        if (v < 10) {
            b[at] = (byte) ('0' + v);
            return at + 1;
        }
        int n = 0;
        for (long t = v; t > 0; t /= 10) {
            n++;
        }
        int end = at + n;
        for (int i = end - 1; i >= at; i--) {
            b[i] = (byte) ('0' + v % 10);
            v /= 10;
        }
        return end;
    }

    XpsOps op(String s) throws IOException {
        boolean paint = paints(s);
        boolean was = quiet;
        quiet |= paint;
        try {
            guard();
            for (int i = 0; i < s.length(); i++) {
                put(s.charAt(i));
            }
        } finally {
            quiet = was;
        }
        if (paint) {
            pathOpen = false;
        }
        return this;
    }

    private static boolean paints(String s) {
        return switch (s) {
            case "f\n", "f*\n", "S\n", "B\n", "B*\n", "b\n", "b*\n", "s\n", "n\n" -> true;
            default -> false;
        };
    }

    XpsOps name(COSName n) throws IOException {
        guard();
        put('/');
        op(n.getName());
        put(' ');
        return this;
    }

    void hex4(int v) throws IOException {
        guard();
        final String digits = "0123456789ABCDEF";
        put(digits.charAt((v >> 12) & 0xF));
        put(digits.charAt((v >> 8) & 0xF));
        put(digits.charAt((v >> 4) & 0xF));
        put(digits.charAt(v & 0xF));
    }

    private void put(char c) throws IOException {
        if (len == buf.length) {
            flush();
        }
        buf[len++] = (byte) c;
    }

    void flush() throws IOException {
        out.write(buf, 0, len);
        bytes += len;
        len = 0;
    }

    void close() throws IOException {
        try {
            while (!saved.isEmpty()) {
                Q();
            }
            flush();
        } finally {
            out.close();
        }
    }

    boolean path(Shape shape, AffineTransform extra) throws IOException {
        boolean was = quiet;
        quiet = true;
        try {
            boolean any = trace(shape, extra);
            if (any && pending > 0) {
                pathOpen = true;
            }
            return any;
        } finally {
            quiet = was;
        }
    }

    private boolean trace(Shape shape, AffineTransform extra) throws IOException {
        PathIterator it = shape.getPathIterator(extra);
        double[] c = coords;
        boolean any = false;
        double x = 0;
        double y = 0;
        double sx = 0;
        double sy = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    num(c[0]).num(c[1]).op("m\n");
                    x = sx = c[0];
                    y = sy = c[1];
                    any = true;
                }
                case PathIterator.SEG_LINETO -> {
                    if (c[0] == x && c[1] == y) {
                        it.next();
                        continue;
                    }
                    num(c[0]).num(c[1]).op("l\n");
                    x = c[0];
                    y = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    num(x + 2.0 / 3 * (c[0] - x)).num(y + 2.0 / 3 * (c[1] - y)).num(c[2] + 2.0 / 3 * (c[0] - c[2]))
                            .num(c[3] + 2.0 / 3 * (c[1] - c[3])).num(c[2]).num(c[3]).op("c\n");
                    x = c[2];
                    y = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    num(c[0]).num(c[1]).num(c[2]).num(c[3]).num(c[4]).num(c[5]).op("c\n");
                    x = c[4];
                    y = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    op("h\n");
                    x = sx;
                    y = sy;
                }
                default -> {}
            }
            it.next();
        }
        return any;
    }

    void rect(Rectangle2D r) throws IOException {
        num(r.getX()).num(r.getY()).num(r.getWidth()).num(r.getHeight()).op("re\n");
    }

    void fillColor(float[] rgb) throws IOException {
        if (fill != null && fillSpace == null && fill[0] == rgb[0] && fill[1] == rgb[1] && fill[2] == rgb[2]) {
            return;
        }
        boolean was = quiet;
        quiet = true;
        try {
            num(rgb[0]).num(rgb[1]).num(rgb[2]).op("rg\n");
        } finally {
            quiet = was;
        }
        System.arraycopy(rgb, 0, fillRgb, 0, 3);
        fill = fillRgb;
        fillSpace = null;
    }

    void strokeColor(float[] rgb) throws IOException {
        if (strokeColor != null && strokeSpace == null && strokeColor[0] == rgb[0] && strokeColor[1] == rgb[1]
                && strokeColor[2] == rgb[2]) {
            return;
        }
        num(rgb[0]).num(rgb[1]).num(rgb[2]).op("RG\n");
        System.arraycopy(rgb, 0, strokeRgb, 0, 3);
        strokeColor = strokeRgb;
        strokeSpace = null;
    }

    void fillColor(COSName space, float[] c) throws IOException {
        if (fill != null && space.equals(fillSpace) && java.util.Arrays.equals(fill, c)) {
            return;
        }
        name(space).op("cs ");
        for (float v : c) {
            num(v);
        }
        op("scn\n");
        fill = c.clone();
        fillSpace = space;
    }

    void strokeColor(COSName space, float[] c) throws IOException {
        if (strokeColor != null && space.equals(strokeSpace) && java.util.Arrays.equals(strokeColor, c)) {
            return;
        }
        name(space).op("CS ");
        for (float v : c) {
            num(v);
        }
        op("SCN\n");
        strokeColor = c.clone();
        strokeSpace = space;
    }

    void forgetColors() {
        fill = null;
        strokeColor = null;
    }

    COSName resource(COSName kind, String prefix, COSBase object) {
        COSName known = names.get(object);
        if (known != null) {
            return known;
        }
        COSDictionary all = resources.getCOSObject();
        COSDictionary dict = all.getCOSDictionary(kind);
        if (dict == null) {
            dict = new COSDictionary();
            all.setItem(kind, dict);
        }
        COSName n;
        do {
            n = COSName.getPDFName(prefix + (++counter));
        } while (dict.containsKey(n));
        dict.setItem(n, object);
        names.put(object, n);
        return n;
    }
}
