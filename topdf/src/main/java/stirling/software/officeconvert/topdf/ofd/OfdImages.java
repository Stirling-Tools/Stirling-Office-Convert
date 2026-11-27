package stirling.software.officeconvert.topdf.ofd;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class OfdImages {

    static final long MAX_MASK_PIXELS = 4L << 20;

    private static final String MASKED = "\u0000mask\u0000";

    private final OfdPackage pkg;

    private final OfdResources res;

    private final PDDocument doc;

    private final Map<String, Optional<DecodedPicture>> cache;

    private final Set<String> notes;

    OfdImages(OfdPackage pkg, OfdResources res, PDDocument doc, Set<String> notes,
            Map<String, Optional<DecodedPicture>> cache) {
        this.pkg = pkg;
        this.res = res;
        this.doc = doc;
        this.notes = notes;
        this.cache = cache;
    }

    DecodedPicture picture(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return null;
        }
        OfdResources.Media m = res.media(id.trim());
        if (m == null || m.file() == null || !pkg.exists(m.file())) {
            return null;
        }
        Optional<DecodedPicture> known = cache.get(m.file());
        if (known != null) {
            return known.orElse(null);
        }
        byte[] data;
        try {
            data = pkg.read(m.file(), OfdPackage.MAX_MEDIA_BYTES);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            notes.add("A picture in the OFD document is too large or unreadable and was left out");
            cache.put(m.file(), Optional.empty());
            return null;
        }
        DecodedPicture made = decode(data, m.format());
        cache.put(m.file(), Optional.ofNullable(made));
        return made;
    }

    DecodedPicture masked(String id, String maskId) throws IOException {
        DecodedPicture base = picture(id);
        OfdResources.Media m = base == null || maskId == null ? null : res.media(maskId.trim());
        if (m == null || m.file() == null || base.image() == null) {
            return base;
        }
        String key = MASKED + res.media(id.trim()).file() + '\u0000' + m.file();
        Optional<DecodedPicture> known = cache.get(key);
        if (known != null) {
            return known.orElse(base);
        }
        DecodedPicture mask = picture(maskId);
        DecodedPicture made = null;
        if (mask != null && mask.image() != null) {
            try {
                made = combine(base, mask);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                notes.add("An image mask in the OFD document could not be applied; the image was drawn without it");
            }
        }
        cache.put(key, Optional.ofNullable(made));
        return made == null ? base : made;
    }

    private DecodedPicture combine(DecodedPicture base, DecodedPicture mask) throws IOException {
        int w = base.pixelWidth();
        int h = base.pixelHeight();
        int mw = mask.pixelWidth();
        int mh = mask.pixelHeight();
        if (w <= 0 || h <= 0 || mw <= 0 || mh <= 0 || (long) w * h > MAX_MASK_PIXELS
                || (long) mw * mh > MAX_MASK_PIXELS) {
            notes.add("An image mask in the OFD document is too large to apply; the image was drawn without it");
            return null;
        }
        BufferedImage picture = base.image().getImage();
        BufferedImage alpha = mask.image().getImage();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            if ((y & 63) == 0) {
                OfficeZip.checkNotInterrupted();
            }
            int my = (int) ((long) y * mh / h);
            for (int x = 0; x < w; x++) {
                int mx = (int) ((long) x * mw / w);
                int m = alpha.getRGB(mx, my);
                int level = (((m >> 16) & 255) * 299 + ((m >> 8) & 255) * 587 + (m & 255) * 114) / 1000;
                int a = level * ((m >>> 24) & 255) / 255;
                int p = picture.getRGB(x, y);
                out.setRGB(x, y, (p & 0x00FFFFFF) | (a * ((p >>> 24) & 255) / 255) << 24);
            }
        }
        return DecodedPicture.of(LosslessFactory.createFromImage(doc, out));
    }

    DecodedPicture decode(byte[] data, String format) throws IOException {
        try {
            if (OfdJbig2.is(data, format) && PictureDecoder.sniff(data) == PictureDecoder.Kind.UNKNOWN) {
                return DecodedPicture.of(OfdJbig2.image(doc, data));
            }
            return PictureDecoder.decode(doc, data);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            String f = format == null || format.isBlank() ? "" : " (" + format + ")";
            notes.add("A picture" + f + " in the OFD document could not be read and was left out");
            return null;
        }
    }
}
