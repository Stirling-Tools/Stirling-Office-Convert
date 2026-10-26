package stirling.software.officeconvert.photosplit;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

final class SyntheticScan {

    enum Style {
        GRADIENT,
        TEXTURE,
        BLOBS,
        PALE,
        DARK,
        PRODUCT
    }

    enum Edge {
        STRAIGHT,
        ROUNDED,
        TORN,
        PERFORATED,
        POLAROID
    }

    record Placed(BufferedImage content, double cx, double cy, double angle, boolean photo) {

        List<Point2D.Double> corners() {
            double w = content.getWidth() / 2.0;
            double h = content.getHeight() / 2.0;
            double c = Math.cos(Math.toRadians(angle));
            double s = Math.sin(Math.toRadians(angle));
            List<Point2D.Double> out = new ArrayList<>();
            double[][] local = {{-w, -h}, {w, -h}, {w, h}, {-w, h}};
            for (double[] p : local) {
                out.add(new Point2D.Double(cx + p[0] * c - p[1] * s, cy + p[0] * s + p[1] * c));
            }
            return out;
        }
    }

    record Scene(BufferedImage scan, List<Placed> photos) {}

    private final int width;
    private final int height;
    private final SplittableRandom random;
    private Color bed = new Color(246, 246, 243);
    private double noise = 3;
    private double shading;
    private int lidShadow;
    private int specks;
    private int shadow = 3;
    private final List<Placed> photos = new ArrayList<>();

    SyntheticScan(int width, int height, long seed) {
        this.width = width;
        this.height = height;
        this.random = new SplittableRandom(seed);
    }

    SyntheticScan bed(Color color) {
        bed = color;
        return this;
    }

    SyntheticScan noise(double sigma) {
        noise = sigma;
        return this;
    }

    SyntheticScan shading(double strength) {
        shading = strength;
        return this;
    }

    SyntheticScan lidShadow(int pixels) {
        lidShadow = pixels;
        return this;
    }

    SyntheticScan specks(int count) {
        specks = count;
        return this;
    }

    SyntheticScan shadow(int pixels) {
        shadow = pixels;
        return this;
    }

    SyntheticScan photo(int w, int h, double cx, double cy, double angle, Style style, int printBorder) {
        photos.add(new Placed(content(w, h, style, printBorder), cx, cy, angle, true));
        return this;
    }

    SyntheticScan photo(int w, int h, double cx, double cy, double angle, Style style, int printBorder, Edge edge) {
        BufferedImage img = edge == Edge.POLAROID ? polaroid(w, h, style, printBorder) : content(w, h, style, printBorder);
        photos.add(new Placed(shape(img, edge), cx, cy, angle, true));
        return this;
    }

    SyntheticScan photo(BufferedImage content, double cx, double cy, double angle) {
        photos.add(new Placed(content, cx, cy, angle, true));
        return this;
    }

    SyntheticScan document(int w, int h, double cx, double cy, double angle) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(250, 250, 247));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(40, 40, 46));
        int line = Math.max(8, h / 40);
        for (int y = h / 10; y < h - h / 10; y += line * 2) {
            int x = w / 10;
            while (x < w - w / 10) {
                int word = random.nextInt(line, line * 5);
                g.fillRect(x, y, Math.min(word, w - w / 10 - x), line);
                x += word + line;
            }
        }
        g.dispose();
        photos.add(new Placed(img, cx, cy, angle, false));
        return this;
    }

    private BufferedImage polaroid(int w, int h, Style style, int border) {
        BufferedImage inner = content(w - 2 * border, h - 2 * border - (int) (2.5 * border), style, 0);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(248, 247, 242));
        g.fillRect(0, 0, w, h);
        g.drawImage(inner, border, border, null);
        g.dispose();
        return img;
    }

    private BufferedImage shape(BufferedImage src, Edge edge) {
        if (edge == Edge.STRAIGHT || edge == Edge.POLAROID) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        double radius = Math.min(w, h) * 0.08;
        double[] tear = new double[2 * (w + h)];
        double v = 0;
        for (int i = 0; i < tear.length; i++) {
            v = Math.clamp(v + random.nextDouble(-1.5, 1.5), 0, 9);
            tear[i] = v;
        }
        int hole = Math.max(4, Math.min(w, h) / 40);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean in = switch (edge) {
                    case ROUNDED -> {
                        double dx = Math.max(0, Math.max(radius - x, x - (w - 1 - radius)));
                        double dy = Math.max(0, Math.max(radius - y, y - (h - 1 - radius)));
                        yield Math.hypot(dx, dy) <= radius;
                    }
                    case TORN -> x >= tear[y] && w - 1 - x >= tear[h + y] && y >= tear[2 * h + x % w] && h - 1 - y >= tear[2 * h + w + x % w];
                    default -> {
                        double px = (x % (2 * hole)) - hole;
                        double py = (y % (2 * hole)) - hole;
                        boolean left = Math.hypot(x, py) < hole * 0.6 || Math.hypot(w - 1 - x, py) < hole * 0.6;
                        boolean top = Math.hypot(px, y) < hole * 0.6 || Math.hypot(px, h - 1 - y) < hole * 0.6;
                        yield !left && !top;
                    }
                };
                out.setRGB(x, y, in ? 0xFF000000 | src.getRGB(x, y) : 0);
            }
        }
        return out;
    }

    Scene render() {
        BufferedImage scan = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) scan.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double f = 1 - shading * ((double) x / width * 0.6 + (double) y / height * 0.4);
                if (lidShadow > 0 && x > width - lidShadow) {
                    f *= 0.55 + 0.45 * (width - x) / (double) lidShadow;
                }
                px[y * width + x] = rgb(bed.getRed() * f, bed.getGreen() * f, bed.getBlue() * f);
            }
        }
        if (shadow > 0) {
            for (Placed p : photos) {
                darken(px, p.corners(), shadow * 0.6, shadow * 0.8);
            }
        }
        Graphics2D g = scan.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.SrcOver);
        for (Placed p : photos) {
            AffineTransform t = new AffineTransform();
            t.translate(p.cx(), p.cy());
            t.rotate(Math.toRadians(p.angle()));
            t.translate(-p.content().getWidth() / 2.0, -p.content().getHeight() / 2.0);
            g.drawImage(p.content(), t, null);
        }
        g.dispose();
        for (int i = 0; i < specks; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int r = 1 + random.nextInt(3);
            int v = random.nextInt(40, 120);
            for (int yy = Math.max(0, y - r); yy < Math.min(height, y + r); yy++) {
                for (int xx = Math.max(0, x - r); xx < Math.min(width, x + r); xx++) {
                    px[yy * width + xx] = rgb(v, v, v);
                }
            }
        }
        if (noise > 0) {
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                px[i] = rgb((c >> 16 & 0xFF) + random.nextGaussian() * noise, (c >> 8 & 0xFF) + random.nextGaussian() * noise,
                        (c & 0xFF) + random.nextGaussian() * noise);
            }
        }
        return new Scene(scan, List.copyOf(photos));
    }

    private BufferedImage content(int w, int h, Style style, int printBorder) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        double[] a = {random.nextDouble(40, 220), random.nextDouble(40, 220), random.nextDouble(40, 220)};
        double[] b = {random.nextDouble(20, 235), random.nextDouble(20, 235), random.nextDouble(20, 235)};
        double fx = random.nextDouble(0.005, 0.04);
        double fy = random.nextDouble(0.005, 0.04);
        double[][] blobs = new double[12][];
        for (int i = 0; i < blobs.length; i++) {
            blobs[i] = new double[] {random.nextDouble(w), random.nextDouble(h), random.nextDouble(15, Math.max(16, w / 4.0)),
                random.nextDouble(-90, 90)};
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double t = (x / (double) w + y / (double) h) / 2;
                double[] c = new double[3];
                for (int k = 0; k < 3; k++) {
                    c[k] = a[k] * (1 - t) + b[k] * t;
                }
                switch (style) {
                    case TEXTURE -> {
                        double v = 45 * Math.sin(x * fx * 6) * Math.cos(y * fy * 6) + 25 * Math.sin((x + y) * 0.21);
                        c[0] += v;
                        c[1] += v * 0.7;
                        c[2] -= v * 0.5;
                    }
                    case BLOBS -> {
                        for (double[] bl : blobs) {
                            double d = Math.hypot(x - bl[0], y - bl[1]) / bl[2];
                            if (d < 1) {
                                double v = bl[3] * (1 - d * d);
                                c[0] += v;
                                c[1] -= v * 0.4;
                                c[2] += v * 0.8;
                            }
                        }
                    }
                    case DARK -> {
                        double v = 18 * Math.sin(x * fx * 4) * Math.cos(y * fy * 4);
                        c[0] = 30 + a[0] * 0.15 + v;
                        c[1] = 28 + a[1] * 0.15 + v;
                        c[2] = 32 + a[2] * 0.15 + v;
                    }
                    case PRODUCT -> {
                        double dx = (x - w / 2.0) / (w * 0.22);
                        double dy = (y - h / 2.0) / (h * 0.3);
                        boolean object = Math.abs(dx) < 1 && Math.abs(dy) < 1;
                        c[0] = object ? 60 + 40 * dx : 244;
                        c[1] = object ? 70 + 30 * dy : 244;
                        c[2] = object ? 90 : 242;
                    }
                    case PALE -> {
                        double v = 10 * Math.sin(x * fx * 3) + 6 * Math.cos(y * fy * 3);
                        c[0] = 236 + v;
                        c[1] = 233 + v * 0.8;
                        c[2] = 226 + v * 0.5;
                    }
                    default -> {
                        double v = 30 * Math.sin(x * fx) * Math.sin(y * fy);
                        c[0] += v;
                        c[1] += v;
                        c[2] += v;
                    }
                }
                boolean frame = x < printBorder || y < printBorder || x >= w - printBorder || y >= h - printBorder;
                px[y * w + x] = frame ? rgb(251, 251, 249) : rgb(c[0], c[1], c[2]);
            }
        }
        return img;
    }

    private void darken(int[] px, List<Point2D.Double> corners, double dx, double dy) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Point2D.Double p : corners) {
            minX = Math.min(minX, p.x + dx);
            minY = Math.min(minY, p.y + dy);
            maxX = Math.max(maxX, p.x + dx);
            maxY = Math.max(maxY, p.y + dy);
        }
        int pad = shadow + 2;
        int x0 = Math.max(0, (int) Math.floor(minX) - pad);
        int y0 = Math.max(0, (int) Math.floor(minY) - pad);
        int x1 = Math.min(width, (int) Math.ceil(maxX) + pad);
        int y1 = Math.min(height, (int) Math.ceil(maxY) + pad);
        int bw = x1 - x0;
        int bh = y1 - y0;
        if (bw <= 0 || bh <= 0) {
            return;
        }
        BufferedImage m = new BufferedImage(bw, bh, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = m.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < corners.size(); i++) {
            Point2D.Double p = corners.get(i);
            if (i == 0) {
                path.moveTo(p.x + dx - x0, p.y + dy - y0);
            } else {
                path.lineTo(p.x + dx - x0, p.y + dy - y0);
            }
        }
        path.closePath();
        g.fill(path);
        g.dispose();
        byte[] data = ((DataBufferByte) m.getRaster().getDataBuffer()).getData();
        float[] dark = new float[bw * bh];
        for (int i = 0; i < dark.length; i++) {
            dark[i] = (data[i] & 0xFF) / 255f;
        }
        dark = blur(dark, bw, bh, shadow);
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                double f = 1 - 0.35 * Math.min(1, dark[y * bw + x]);
                int i = (y + y0) * width + x + x0;
                int c = px[i];
                px[i] = rgb((c >> 16 & 0xFF) * f, (c >> 8 & 0xFF) * f, (c & 0xFF) * f);
            }
        }
    }

    private static float[] blur(float[] in, int w, int h, int radius) {
        float[] tmp = new float[in.length];
        float[] out = new float[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, x - radius); k <= Math.min(w - 1, x + radius); k++) {
                    s += in[y * w + k];
                    n++;
                }
                tmp[y * w + x] = s / n;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, y - radius); k <= Math.min(h - 1, y + radius); k++) {
                    s += tmp[k * w + x];
                    n++;
                }
                out[y * w + x] = s / n;
            }
        }
        return out;
    }

    private static int rgb(double r, double g, double b) {
        return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
