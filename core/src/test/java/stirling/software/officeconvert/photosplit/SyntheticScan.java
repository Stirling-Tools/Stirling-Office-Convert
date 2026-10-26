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
        PALE
    }

    record Placed(BufferedImage content, double cx, double cy, double angle) {

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
        photos.add(new Placed(content(w, h, style, printBorder), cx, cy, angle));
        return this;
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
            float[] dark = new float[width * height];
            for (Placed p : photos) {
                fillPolygon(dark, p.corners(), shadow * 0.6, shadow * 0.8);
            }
            dark = blur(dark, shadow);
            for (int i = 0; i < px.length; i++) {
                double f = 1 - 0.35 * Math.min(1, dark[i]);
                int c = px[i];
                px[i] = rgb((c >> 16 & 0xFF) * f, (c >> 8 & 0xFF) * f, (c & 0xFF) * f);
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

    private void fillPolygon(float[] dark, List<Point2D.Double> corners, double dx, double dy) {
        BufferedImage m = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = m.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < corners.size(); i++) {
            Point2D.Double p = corners.get(i);
            if (i == 0) {
                path.moveTo(p.x + dx, p.y + dy);
            } else {
                path.lineTo(p.x + dx, p.y + dy);
            }
        }
        path.closePath();
        g.fill(path);
        g.dispose();
        byte[] data = ((DataBufferByte) m.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < dark.length; i++) {
            dark[i] = Math.max(dark[i], (data[i] & 0xFF) / 255f);
        }
    }

    private float[] blur(float[] in, int radius) {
        float[] tmp = new float[in.length];
        float[] out = new float[in.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, x - radius); k <= Math.min(width - 1, x + radius); k++) {
                    s += in[y * width + k];
                    n++;
                }
                tmp[y * width + x] = s / n;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float s = 0;
                int n = 0;
                for (int k = Math.max(0, y - radius); k <= Math.min(height - 1, y + radius); k++) {
                    s += tmp[k * width + x];
                    n++;
                }
                out[y * width + x] = s / n;
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
