package stirling.software.officeconvert.topdf.pptx;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class Pictures {

    private static final long RECOLOR_PIXELS = 2_000_000;

    private final RenderJob job;

    private final Map<String, Relationships> rels = new HashMap<>();

    private final Map<String, DecodedPicture> decoded = new HashMap<>();

    private final Set<String> failed = new HashSet<>();

    Pictures(RenderJob job) {
        this.job = job;
    }

    Relationship relationship(String sourcePart, String id) throws IOException {
        if (sourcePart == null || id == null || id.isEmpty()) {
            return null;
        }
        Relationships r = rels.get(sourcePart);
        if (r == null) {
            try {
                r = job.zip().relationships(sourcePart);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                job.warn("The relationships of " + sourcePart + " could not be read: " + e.getMessage());
                r = null;
            }
            if (r == null) {
                return null;
            }
            rels.put(sourcePart, r);
        }
        return r.get(id);
    }

    DecodedPicture picture(String sourcePart, String id) throws IOException {
        Relationship r = relationship(sourcePart, id);
        if (!ActiveContent.mayFollow(r) || ActiveContent.ofPart(job.zip(), r.part()) != null) {
            return null;
        }
        String part = r.part();
        DecodedPicture known = decoded.get(part);
        if (known != null || failed.contains(part)) {
            return known;
        }
        job.checkpoint();
        try {
            DecodedPicture p = PictureDecoder.decode(job.document(), job.zip().read(r));
            decoded.put(part, p);
            return p;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failed.add(part);
            if (PictureDecoder.Kind.SVG != sniff(r)) {
                job.warn("A picture could not be drawn (" + part + "): " + e.getMessage());
            }
            return null;
        }
    }

    record Step(String key, BiFunction<BufferedImage, PictureDecoder.Kind, BufferedImage> op) {}

    DecodedPicture recoloured(String sourcePart, String id, List<Step> steps) throws IOException {
        StringBuilder key = new StringBuilder();
        for (Step s : steps) {
            key.append(s.key()).append(';');
        }
        return recoloured(sourcePart, id, key.toString(), (src, kind) -> {
            BufferedImage img = src;
            for (Step s : steps) {
                img = s.op().apply(img, kind);
            }
            return img;
        });
    }

    DecodedPicture reflection(String sourcePart, String id, float[] crop, float stA, float stPos, float endA,
            float endPos) throws IOException {
        String key = "reflect|" + java.util.Arrays.toString(crop) + "|" + stA + "|" + stPos + "|" + endA + "|" + endPos;
        return recoloured(sourcePart, id, key,
                (src, kind) -> Recolor.reflect(src, crop[0], crop[1], crop[2], crop[3], stA, stPos, endA, endPos));
    }

    private DecodedPicture recoloured(String sourcePart, String id, String effect,
            BiFunction<BufferedImage, PictureDecoder.Kind, BufferedImage> recolour) throws IOException {
        Relationship r = relationship(sourcePart, id);
        if (!ActiveContent.mayFollow(r) || ActiveContent.ofPart(job.zip(), r.part()) != null) {
            return null;
        }
        String key = r.part() + "|" + effect;
        DecodedPicture known = decoded.get(key);
        if (known != null || failed.contains(key)) {
            return known;
        }
        job.checkpoint();
        try {
            byte[] bytes = job.zip().read(r);
            PictureDecoder.Kind kind = PictureDecoder.sniff(bytes);
            if (kind == PictureDecoder.Kind.EMF || kind == PictureDecoder.Kind.WMF) {
                return picture(sourcePart, id);
            }
            BufferedImage src = PictureDecoder.readRaster(bytes, RECOLOR_PIXELS);
            DecodedPicture p = PictureDecoder.fromImage(job.document(), recolour.apply(src, kind));
            decoded.put(key, p);
            return p;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            failed.add(key);
            return picture(sourcePart, id);
        }
    }

    private PictureDecoder.Kind sniff(Relationship r) {
        try {
            return PictureDecoder.sniff(job.zip().read(r));
        } catch (IOException | RuntimeException e) {
            return PictureDecoder.Kind.UNKNOWN;
        }
    }
}
