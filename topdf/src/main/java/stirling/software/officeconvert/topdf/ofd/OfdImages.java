package stirling.software.officeconvert.topdf.ofd;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class OfdImages {

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
        DecodedPicture made = decode(pkg.read(m.file(), OfdPackage.MAX_MEDIA_BYTES), m.format());
        cache.put(m.file(), Optional.ofNullable(made));
        return made;
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
