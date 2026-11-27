package stirling.software.officeconvert.topdf.psd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;

final class Plan {

    static final int MAX_INKS = 32;

    final int components;

    final int bits;

    final int[] channels;

    final int alpha;

    final boolean invert;

    final List<String> warnings = new ArrayList<>();

    final String profile;

    private Plan(int components, int bits, int[] channels, int alpha, boolean invert, String profile) {
        this.components = components;
        this.bits = bits;
        this.channels = channels;
        this.alpha = alpha;
        this.invert = invert;
        this.profile = profile;
    }

    static Plan of(PsdDocument doc, int alpha) {
        int bits = doc.depth == 32 ? 16 : doc.depth;
        int cc = doc.colorChannels();
        if (doc.mode == PsdDocument.MULTICHANNEL) {
            cc = Math.min(doc.channels, MAX_INKS);
            alpha = -1;
        }
        int[] channels = new int[cc + (alpha >= 0 ? 1 : 0)];
        for (int i = 0; i < cc; i++) {
            channels[i] = i;
        }
        if (alpha >= 0) {
            channels[cc] = alpha;
        }
        boolean invert = doc.mode == PsdDocument.CMYK || doc.mode == PsdDocument.MULTICHANNEL
                || doc.mode == PsdDocument.BITMAP;
        int components = doc.mode == PsdDocument.INDEXED || doc.mode == PsdDocument.BITMAP ? 1 : cc;
        String profile = switch (doc.mode) {
            case PsdDocument.GRAY, PsdDocument.DUOTONE -> Spaces.profileFits(doc.icc, 1) ? "gray" : null;
            case PsdDocument.RGB, PsdDocument.INDEXED -> Spaces.profileFits(doc.icc, 3) ? "rgb" : null;
            case PsdDocument.CMYK -> Spaces.profileFits(doc.icc, 4) ? "cmyk" : null;
            default -> null;
        };
        if (doc.depth == 32 && profile != null && !Spaces.linear(doc.icc)) {
            profile = null;
        }
        Plan p = new Plan(components, bits, channels, alpha, invert, profile);
        if (doc.mode == PsdDocument.MULTICHANNEL && doc.channels > MAX_INKS) {
            p.warnings.add("The PSD document has " + doc.channels + " channels; only the first " + MAX_INKS
                    + " were drawn");
        }
        return p;
    }

    COSBase colorSpace(PDDocument pdf, PsdDocument doc) throws IOException {
        return switch (doc.mode) {
            case PsdDocument.BITMAP -> Spaces.device(1);
            case PsdDocument.GRAY, PsdDocument.DUOTONE -> profile != null ? Spaces.iccBased(pdf, doc.icc, 1)
                    : Spaces.device(1);
            case PsdDocument.RGB -> profile != null ? Spaces.iccBased(pdf, doc.icc, 3) : Spaces.device(3);
            case PsdDocument.CMYK -> profile != null ? Spaces.iccBased(pdf, doc.icc, 4) : Spaces.device(4);
            case PsdDocument.LAB -> Spaces.lab();
            case PsdDocument.INDEXED -> Spaces.indexed(profile != null ? Spaces.iccBased(pdf, doc.icc, 3)
                    : Spaces.device(3), doc.palette, doc.paletteCount);
            default -> {
                List<float[]> colors = new ArrayList<>();
                for (int i = 0; i < components; i++) {
                    colors.add(Spaces.inkColor(doc, i));
                }
                yield Spaces.deviceN(pdf, Spaces.inkNames(doc, 0, components), colors);
            }
        };
    }
}
