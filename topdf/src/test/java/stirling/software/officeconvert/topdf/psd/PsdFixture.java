package stirling.software.officeconvert.topdf.psd;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

final class PsdFixture {

    int version = 1;

    int mode = PsdDocument.RGB;

    int depth = 8;

    int width;

    int height;

    int compression = Rows.RLE;

    final List<byte[]> planes = new ArrayList<>();

    final Map<Integer, byte[]> resources = new LinkedHashMap<>();

    byte[] colorData = new byte[0];

    final List<Layer> layers = new ArrayList<>();

    boolean mergedAlpha;

    boolean hasMerged = true;

    int layerCount = -1;

    long forcedRowCount = -1;

    static final class Layer {
        int top;
        int left;
        int bottom;
        int right;
        final Map<Integer, byte[]> channels = new LinkedHashMap<>();
        final Map<Integer, byte[]> encoded = new LinkedHashMap<>();
        String blend = "norm";
        int opacity = 255;
        boolean clipped;
        boolean hidden;
        int[] mask;
        int maskFill;
        int maskDensity = -1;
        final ByteArrayOutputStream blocks = new ByteArrayOutputStream();

        Layer rect(int top, int left, int bottom, int right) {
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            return this;
        }

        Layer fill(int channel, int value) {
            int n = Math.max(0, (right - left) * (bottom - top));
            byte[] b = new byte[n];
            java.util.Arrays.fill(b, (byte) value);
            channels.put(channel, b);
            return this;
        }

        Layer block(String key, byte[] data) {
            try {
                DataOutputStream d = new DataOutputStream(blocks);
                d.writeBytes("8BIM");
                d.writeBytes(key);
                d.writeInt(data.length);
                d.write(data);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        Layer section(int type) {
            return block("lsct", new byte[] {0, 0, 0, (byte) type});
        }
    }

    PsdFixture(int width, int height) {
        this.width = width;
        this.height = height;
    }

    int rowBytes() {
        return (width * depth + 7) / 8;
    }

    PsdFixture plane(int... values) {
        int rb = rowBytes();
        byte[] p = new byte[rb * height];
        for (int i = 0; i < values.length && i < p.length; i++) {
            p[i] = (byte) values[i];
        }
        planes.add(p);
        return this;
    }

    PsdFixture flat(int value) {
        byte[] p = new byte[rowBytes() * height];
        java.util.Arrays.fill(p, (byte) value);
        planes.add(p);
        return this;
    }

    PsdFixture raw(byte[] p) {
        planes.add(p);
        return this;
    }

    PsdFixture dpi(double dpi) {
        int fixed = (int) Math.round(dpi * 65536);
        resources.put(0x03ED, new byte[] {(byte) (fixed >> 24), (byte) (fixed >> 16), (byte) (fixed >> 8),
            (byte) fixed, 0, 1, 0, 1, (byte) (fixed >> 24), (byte) (fixed >> 16), (byte) (fixed >> 8), (byte) fixed, 0,
            1, 0, 1});
        return this;
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        boolean big = version == 2;
        o.writeBytes("8BPS");
        o.writeShort(version);
        o.write(new byte[6]);
        o.writeShort(planes.size());
        o.writeInt(height);
        o.writeInt(width);
        o.writeShort(depth);
        o.writeShort(mode);
        o.writeInt(colorData.length);
        o.write(colorData);
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        DataOutputStream r = new DataOutputStream(res);
        Map<Integer, byte[]> all = new LinkedHashMap<>(resources);
        all.putIfAbsent(0x0421, new byte[] {0, 0, 0, 1, (byte) (hasMerged ? 1 : 0), 0, 0, 0, 0});
        for (Map.Entry<Integer, byte[]> e : all.entrySet()) {
            r.writeBytes("8BIM");
            r.writeShort(e.getKey());
            r.writeShort(0);
            r.writeInt(e.getValue().length);
            r.write(e.getValue());
            if ((e.getValue().length & 1) != 0) {
                r.write(0);
            }
        }
        o.writeInt(res.size());
        o.write(res.toByteArray());
        byte[] layerInfo = layers.isEmpty() && layerCount < 0 ? new byte[0] : layerInfo(big);
        long section = layerInfo.length == 0 ? 0 : layerInfo.length + (big ? 8 : 4) + 4;
        length(o, section, big);
        if (section > 0) {
            length(o, layerInfo.length, big);
            o.write(layerInfo);
            o.writeInt(0);
        }
        o.writeShort(compression);
        int rb = rowBytes();
        if (compression == Rows.RAW) {
            for (byte[] p : planes) {
                o.write(p);
            }
        } else if (compression == Rows.RLE) {
            List<byte[]> rows = new ArrayList<>();
            for (byte[] p : planes) {
                for (int y = 0; y < height; y++) {
                    rows.add(packBits(p, y * rb, rb));
                }
            }
            for (byte[] row : rows) {
                long n = forcedRowCount >= 0 ? forcedRowCount : row.length;
                if (big) {
                    o.writeInt((int) n);
                } else {
                    o.writeShort((int) n);
                }
            }
            for (byte[] row : rows) {
                o.write(row);
            }
        } else {
            ByteArrayOutputStream all2 = new ByteArrayOutputStream();
            for (byte[] p : planes) {
                all2.write(p);
            }
            o.write(deflate(all2.toByteArray()));
        }
        return bo.toByteArray();
    }

    private byte[] layerInfo(boolean big) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeShort(layerCount >= 0 ? layerCount : mergedAlpha ? -layers.size() : layers.size());
        List<byte[]> data = new ArrayList<>();
        for (Layer l : layers) {
            o.writeInt(l.top);
            o.writeInt(l.left);
            o.writeInt(l.bottom);
            o.writeInt(l.right);
            o.writeShort(l.channels.size());
            for (Map.Entry<Integer, byte[]> e : l.channels.entrySet()) {
                boolean mask = e.getKey() == -2;
                int w = mask ? l.mask[3] - l.mask[1] : l.right - l.left;
                int h = mask ? l.mask[2] - l.mask[0] : l.bottom - l.top;
                byte[] enc = l.encoded.containsKey(e.getKey()) ? l.encoded.get(e.getKey()) : channel(e.getValue(), w, h, big);
                data.add(enc);
                o.writeShort(e.getKey());
                length(o, enc.length, big);
            }
            o.writeBytes("8BIM");
            o.writeBytes(l.blend);
            o.write(l.opacity);
            o.write(l.clipped ? 1 : 0);
            o.write(l.hidden ? 2 : 0);
            o.write(0);
            ByteArrayOutputStream ex = new ByteArrayOutputStream();
            DataOutputStream e = new DataOutputStream(ex);
            if (l.mask != null && l.maskDensity >= 0) {
                e.writeInt(20);
                for (int v : l.mask) {
                    e.writeInt(v);
                }
                e.write(l.maskFill);
                e.write(16);
                e.write(1);
                e.write(l.maskDensity);
            } else if (l.mask != null) {
                e.writeInt(20);
                for (int v : l.mask) {
                    e.writeInt(v);
                }
                e.write(l.maskFill);
                e.write(0);
                e.writeShort(0);
            } else {
                e.writeInt(0);
            }
            e.writeInt(0);
            byte[] name = "L".getBytes(StandardCharsets.ISO_8859_1);
            e.write(name.length);
            e.write(name);
            e.write(new byte[(4 - (name.length + 1) % 4) % 4]);
            e.write(l.blocks.toByteArray());
            o.writeInt(ex.size());
            o.write(ex.toByteArray());
        }
        for (byte[] d : data) {
            o.write(d);
        }
        return bo.toByteArray();
    }

    private static byte[] channel(byte[] plane, int w, int h, boolean big) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        if (plane.length == 0) {
            o.writeShort(Rows.RAW);
            return bo.toByteArray();
        }
        o.writeShort(Rows.RLE);
        List<byte[]> rows = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            rows.add(packBits(plane, y * w, w));
        }
        for (byte[] r : rows) {
            if (big) {
                o.writeInt(r.length);
            } else {
                o.writeShort(r.length);
            }
        }
        for (byte[] r : rows) {
            o.write(r);
        }
        return bo.toByteArray();
    }

    private static void length(DataOutputStream o, long v, boolean big) throws IOException {
        if (big) {
            o.writeLong(v);
        } else {
            o.writeInt((int) v);
        }
    }

    static byte[] packBits(byte[] src, int off, int len) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int i = 0;
        while (i < len) {
            int run = 1;
            while (i + run < len && run < 128 && src[off + i + run] == src[off + i]) {
                run++;
            }
            if (run > 1) {
                o.write(257 - run);
                o.write(src[off + i]);
                i += run;
            } else {
                int start = i;
                int n = 0;
                while (i < len && n < 128 && (i + 1 >= len || src[off + i] != src[off + i + 1])) {
                    i++;
                    n++;
                }
                if (n == 0) {
                    i++;
                    n = 1;
                }
                o.write(n - 1);
                o.write(src, off + start, n);
            }
        }
        return o.toByteArray();
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        while (!d.finished()) {
            o.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return o.toByteArray();
    }
}
