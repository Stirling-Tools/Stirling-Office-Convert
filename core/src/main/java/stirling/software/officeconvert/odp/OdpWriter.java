package stirling.software.officeconvert.odp;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.sink.SpillFile;
import stirling.software.officeconvert.sink.ZipParts;
import stirling.software.officeconvert.slides.Slide;
import stirling.software.officeconvert.slides.SlideSink;

public final class OdpWriter implements SlideSink {

    private static final String MIME = "application/vnd.oasis.opendocument.presentation";

    private static final long MEMORY_LIMIT = 4L * 1024 * 1024;

    private final ZipOutputStream zip;
    private final OdpStyles styles = new OdpStyles();
    private final Map<Object, Picture.MediaRef> mediaByKey = new HashMap<>();
    private final Map<String, Object> mediaData = new LinkedHashMap<>();
    private final Body body = new Body();
    private final Set<Integer> backgrounds = new TreeSet<>();
    private final SpillFile spill = new SpillFile("office-convert-odp");
    private final boolean lossless;
    private long mediaBytes;
    private float width;
    private float height;
    private int firstPage;
    private int expected;
    private int slides;

    public OdpWriter(OutputStream target) throws IOException {
        this(target, Pictures.COMPACT);
    }

    public OdpWriter(OutputStream target, Pictures pictures) throws IOException {
        this.lossless = pictures == Pictures.LOSSLESS;
        this.zip = new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
        ZipParts.stored(zip, "mimetype", MIME.getBytes(StandardCharsets.US_ASCII));
        zip.setLevel(6);
    }

    @Override
    public void begin(float slideWidth, float slideHeight, int firstPage, int slideCount) {
        this.width = slideWidth;
        this.height = slideHeight;
        this.firstPage = firstPage;
        this.expected = slideCount;
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return mediaByKey.get(key);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) throws IOException {
        Picture.MediaRef existing = mediaByKey.get(key);
        if (existing != null) {
            return existing;
        }
        String name = "image" + (mediaData.size() + 1) + "." + ("jpeg".equals(ext) ? "jpg" : ext);
        keep(name, bytes);
        Picture.MediaRef ref = new Picture.MediaRef(name, "jpeg".equals(ext) ? "image/jpeg" : "image/png", pixelWidth,
                pixelHeight);
        mediaByKey.put(key, ref);
        return ref;
    }

    private void keep(String name, byte[] bytes) throws IOException {
        if (mediaBytes + bytes.length > MEMORY_LIMIT) {
            mediaData.put(name, spill.append(bytes));
        } else {
            mediaBytes += bytes.length;
            mediaData.put(name, bytes);
        }
    }

    private String mediaName(Picture pic) throws IOException {
        if (pic.cropLeft <= 0 && pic.cropTop <= 0 && pic.cropRight <= 0 && pic.cropBottom <= 0) {
            return pic.media.name();
        }
        Object key = List.of("crop", pic.media.name(), pic.cropLeft, pic.cropTop, pic.cropRight, pic.cropBottom);
        Picture.MediaRef ref = mediaByKey.get(key);
        if (ref != null) {
            return ref.name();
        }
        Object data = mediaData.get(pic.media.name());
        byte[] bytes = data instanceof byte[] b ? b : spill.read((SpillFile.Block) data);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            return pic.media.name();
        }
        int x = Math.round(pic.cropLeft * img.getWidth());
        int y = Math.round(pic.cropTop * img.getHeight());
        int w = Math.max(1, Math.round((1 - pic.cropLeft - pic.cropRight) * img.getWidth()));
        int h = Math.max(1, Math.round((1 - pic.cropTop - pic.cropBottom) * img.getHeight()));
        w = Math.min(w, img.getWidth() - x);
        h = Math.min(h, img.getHeight() - y);
        if (w <= 0 || h <= 0) {
            return pic.media.name();
        }
        BufferedImage part = img.getSubimage(x, y, w, h);
        boolean jpeg = !lossless && pic.media.contentType().equals("image/jpeg") && !part.getColorModel().hasAlpha();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(part, jpeg ? "jpeg" : "png", out)) {
            return pic.media.name();
        }
        return media(out.toByteArray(), jpeg ? "jpeg" : "png", w, h, key).name();
    }

    @Override
    public void slide(Slide slide) throws IOException {
        slides++;
        StringBuilder sb = new StringBuilder(8192);
        OdpText text = new OdpText(styles, this::slideOf);
        new OdpSlideXml(styles, text, this::mediaName).page(sb, slide, slides);
        body.write(sb.toString());
        if (slide.background() >= 0) {
            backgrounds.add(slide.background() & 0xFFFFFF);
        }
    }

    private int slideOf(int page) {
        int n = page - firstPage + 1;
        return n >= 1 && n <= expected ? n : -1;
    }

    @Override
    public void finish(String title, String author) throws IOException {
        zip.putNextEntry(new ZipEntry("content.xml"));
        Writer w = new BufferedWriter(new OutputStreamWriter(new KeepOpen(zip), StandardCharsets.UTF_8), 1 << 16);
        w.write(Odf.HEADER + "<office:document-content" + Odf.NAMESPACES + ">");
        w.write(styles.fontDecls());
        w.write("<office:automatic-styles>" + styles.automaticStyles() + "</office:automatic-styles>");
        w.write("<office:body><office:presentation>");
        w.flush();
        body.copyTo(zip);
        w.write("</office:presentation></office:body></office:document-content>");
        w.flush();
        zip.closeEntry();
        entry("styles.xml", OdpParts.styles(width, height, styles.fontDecls(), mostUsedFont(), backgrounds));
        entry("meta.xml", OdpParts.meta(title, author, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        List<String[]> manifest = new ArrayList<>();
        for (Map.Entry<String, Object> m : mediaData.entrySet()) {
            ZipParts.picture(zip, "Pictures/" + m.getKey(),
                    m.getValue() instanceof byte[] b ? b : spill.read((SpillFile.Block) m.getValue()));
            manifest.add(new String[] {"Pictures/" + m.getKey(), m.getKey().endsWith(".jpg") ? "image/jpeg" : "image/png"});
        }
        entry("META-INF/manifest.xml", OdpParts.manifest(manifest));
        zip.finish();
    }

    private String mostUsedFont() {
        return styles.fonts().isEmpty() ? "Liberation Sans" : styles.fonts().iterator().next();
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            try {
                body.delete();
            } finally {
                spill.close();
            }
        }
    }

    private static final class Body {
        private ByteArrayOutputStream memory = new ByteArrayOutputStream();
        private Path file;
        private OutputStream fileOut;

        void write(String xml) throws IOException {
            byte[] b = xml.getBytes(StandardCharsets.UTF_8);
            if (file == null && memory.size() + b.length > MEMORY_LIMIT) {
                file = Files.createTempFile("office-convert-odp", ".xml");
                fileOut = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
                memory.writeTo(fileOut);
                memory = null;
            }
            if (file != null) {
                fileOut.write(b);
            } else {
                memory.write(b);
            }
        }

        void copyTo(OutputStream out) throws IOException {
            if (file == null) {
                memory.writeTo(out);
                return;
            }
            fileOut.close();
            fileOut = null;
            Files.copy(file, out);
        }

        void delete() throws IOException {
            if (fileOut != null) {
                fileOut.close();
            }
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
