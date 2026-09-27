package stirling.software.officeconvert.odt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.sink.MediaStore;

final class OdtMedia {

    private final MediaStore store;
    private final boolean flat;
    private final Map<String, Picture.MediaRef> used = new LinkedHashMap<>();

    OdtMedia(MediaStore store, boolean flat) {
        this.store = store;
        this.flat = flat;
    }

    void image(StringBuilder sb, Picture pic) throws IOException {
        Picture.MediaRef ref = store.shaped(pic);
        if (flat) {
            sb.append("<draw:image draw:mime-type=\"").append(ref.contentType()).append("\"><office:binary-data>")
                    .append(Base64.getEncoder().encodeToString(store.bytes(ref))).append("</office:binary-data></draw:image>");
        } else {
            used.put(ref.name(), ref);
            sb.append("<draw:image xlink:href=\"Pictures/").append(ref.name())
                    .append("\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\" draw:mime-type=\"")
                    .append(ref.contentType()).append("\"/>");
        }
    }

    List<Picture.MediaRef> used() {
        return new ArrayList<>(used.values());
    }
}
