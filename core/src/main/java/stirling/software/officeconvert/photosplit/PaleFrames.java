package stirling.software.officeconvert.photosplit;

final class PaleFrames {

    private PaleFrames() {}

    static void recover(byte[] mask, byte[] faint, byte[] photo, int w, int h, PhotoSplitter.Options options) {
        int disconnected = 0;
        int required = Math.max(64, options.minContourArea());
        for (int i = 0; i < mask.length && disconnected < required; i++) {
            if ((i & 0x3FFFF) == 0) {
                PhotoSplitter.checkpoint();
            }
            if (faint[i] != 0 && mask[i] == 0) {
                disconnected++;
            }
        }
        if (disconnected < required) {
            return;
        }
        PhotoSplitter.checkpoint();
        Morphology.dilate(faint, w, h, 2);
        Morphology.erode(faint, w, h, 2);
        Filters.fillHoles(faint, w, h);
        for (Region region : Region.label(faint, w, h)) {
            PhotoSplitter.checkpoint();
            if (region.minX <= 1 || region.minY <= 1 || region.maxX >= w - 2 || region.maxY >= h - 2
                    || region.count < options.minArea()) {
                continue;
            }
            RotatedRect rect = region.rect();
            if (region.count < 0.95 * rect.area()) {
                continue;
            }
            int supported = 0;
            int covered = 0;
            for (int y = 0; y < region.height(); y++) {
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    if (region.mask[y * region.width() + x] != 0) {
                        supported += photo[base + x];
                        covered += mask[base + x];
                    }
                }
            }
            if (supported < Math.max(options.minContourArea(), 0.02 * region.count) || covered >= 0.75 * region.count) {
                continue;
            }
            byte[] interior = new byte[region.mask.length];
            for (int y = 0; y < region.height(); y++) {
                PhotoSplitter.checkpoint();
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    int index = y * region.width() + x;
                    interior[index] = (byte) (photo[base + x] & region.mask[index]);
                }
            }
            int parts = 0;
            for (Region part : Region.label(interior, region.width(), region.height())) {
                if (part.count >= Math.max(options.minContourArea(), 0.02 * region.count)) {
                    parts++;
                }
            }
            if (parts != 1) {
                continue;
            }
            for (int y = 0; y < region.height(); y++) {
                PhotoSplitter.checkpoint();
                int base = (y + region.minY) * w + region.minX;
                for (int x = 0; x < region.width(); x++) {
                    mask[base + x] |= region.mask[y * region.width() + x];
                }
            }
        }
    }
}
