package stirling.software.officeconvert.layout;

public record PageFrame(
        float textLeft,
        float textRight,
        float bodyTop,
        float bodyBottom,
        float headerTop,
        float headerBottom,
        float footerTop,
        float footerBottom,
        float firstLineTop,
        float headerBaseline,
        float headerSize,
        float footerBaseline,
        float footerSize) {

    public float flowBottom(float pageHeight) {
        if (!Float.isNaN(footerBaseline)) {
            return Math.min(pageHeight - 12, footerTop - 2);
        }
        return pageHeight - Math.max(4, (pageHeight - bodyBottom) * 0.5f);
    }
}
