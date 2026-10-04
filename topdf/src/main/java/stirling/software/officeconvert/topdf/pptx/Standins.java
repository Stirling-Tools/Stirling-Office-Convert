package stirling.software.officeconvert.topdf.pptx;

import stirling.software.officeconvert.topdf.font.CloudFonts;
import stirling.software.officeconvert.topdf.font.FontFace;

// A missing Office face as PowerPoint text draws it: the font layer's stand-in with the missing face's advances
final class Standins {

    private final CloudFonts cloud;

    Standins(CloudFonts cloud) {
        this.cloud = cloud;
    }

    record Emulation(FontFace face, float scale, float[] vertical, String note, Advances metrics) {}

    Emulation emulate(String family, boolean bold, boolean italic) {
        CloudFonts.Emulation e = cloud.emulate(family, bold, italic);
        return new Emulation(e.face(), e.scale(), e.vertical(), e.note(),
                e.metrics() == null ? null : e.metrics()::advance);
    }
}
