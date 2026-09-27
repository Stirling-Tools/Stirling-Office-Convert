package stirling.software.officeconvert.sink;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.model.Picture;

public final class MediaStore implements Closeable {

    private static final long MEMORY_LIMIT = 4L * 1024 * 1024;

    private final Map<Object, Picture.MediaRef> byKey = new HashMap<>();
    private final Map<String, Object> data = new HashMap<>();
    private final List<Picture.MediaRef> order = new ArrayList<>();
    private final Map<String, Picture.MediaRef> shaped = new HashMap<>();
    private long memoryBytes;
    private final SpillFile spill = new SpillFile("office-convert-media");
    private final boolean lossless;

    public MediaStore() {
        this(Pictures.COMPACT);
    }

    public MediaStore(Pictures pictures) {
        this.lossless = pictures == Pictures.LOSSLESS;
    }

    public Picture.MediaRef get(Object key) {
        return byKey.get(key);
    }

    public Picture.MediaRef add(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) throws IOException {
        Picture.MediaRef existing = byKey.get(key);
        if (existing != null) {
            return existing;
        }
        Picture.MediaRef ref = store(bytes, ext, pixelWidth, pixelHeight);
        byKey.put(key, ref);
        return ref;
    }

    private Picture.MediaRef store(byte[] bytes, String ext, int pixelWidth, int pixelHeight) throws IOException {
        String name = "image" + (order.size() + 1) + "." + ext;
        Picture.MediaRef ref =
                new Picture.MediaRef(name, "jpeg".equals(ext) ? "image/jpeg" : "image/png", pixelWidth, pixelHeight);
        if (memoryBytes + bytes.length > MEMORY_LIMIT) {
            data.put(name, spill.append(bytes));
        } else {
            memoryBytes += bytes.length;
            data.put(name, bytes);
        }
        order.add(ref);
        return ref;
    }

    public byte[] bytes(Picture.MediaRef ref) throws IOException {
        Object d = data.get(ref.name());
        if (d instanceof byte[] b) {
            return b;
        }
        return d == null ? new byte[0] : spill.read((SpillFile.Block) d);
    }

    public List<Picture.MediaRef> all() {
        return List.copyOf(order);
    }

    public Picture.MediaRef shaped(Picture pic) throws IOException {
        if (!ImageShaping.needed(pic)) {
            return pic.media;
        }
        String key = ImageShaping.key(pic);
        Picture.MediaRef done = shaped.get(key);
        if (done != null) {
            return done;
        }
        ImageShaping.Result r = ImageShaping.apply(bytes(pic.media), pic, lossless);
        Picture.MediaRef ref = r == null ? pic.media : store(r.bytes(), r.ext(), r.width(), r.height());
        shaped.put(key, ref);
        return ref;
    }

    @Override
    public void close() throws IOException {
        spill.close();
    }
}
