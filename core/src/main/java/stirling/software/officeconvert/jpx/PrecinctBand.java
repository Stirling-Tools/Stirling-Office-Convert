package stirling.software.officeconvert.jpx;

final class PrecinctBand {

    final int width;

    final int height;

    final CodeBlock[] blocks;

    private TagTree inclusion;

    private TagTree zeroPlanes;

    PrecinctBand(Band band, int px0, int py0, int px1, int py1, BlockBudget budget) throws JpxException {
        int bx0 = Math.max(px0, band.x0);
        int by0 = Math.max(py0, band.y0);
        int bx1 = Math.min(px1, band.x1);
        int by1 = Math.min(py1, band.y1);
        if (bx1 <= bx0 || by1 <= by0) {
            width = 0;
            height = 0;
            blocks = new CodeBlock[0];
            return;
        }
        int gx0 = Math.floorDiv(bx0, 1 << band.blockW);
        int gy0 = Math.floorDiv(by0, 1 << band.blockH);
        int gx1 = Math.ceilDiv(bx1, 1 << band.blockW);
        int gy1 = Math.ceilDiv(by1, 1 << band.blockH);
        width = gx1 - gx0;
        height = gy1 - gy0;
        budget.take((long) width * height);
        blocks = new CodeBlock[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int cx0 = Math.max(bx0, (gx0 + i) << band.blockW);
                int cy0 = Math.max(by0, (gy0 + j) << band.blockH);
                int cx1 = Math.min(bx1, (gx0 + i + 1) << band.blockW);
                int cy1 = Math.min(by1, (gy0 + j + 1) << band.blockH);
                blocks[j * width + i] = new CodeBlock(cx0, cy0, cx1, cy1);
            }
        }
    }

    TagTree inclusion() {
        if (inclusion == null) {
            inclusion = new TagTree(width, height);
        }
        return inclusion;
    }

    TagTree zeroPlanes() {
        if (zeroPlanes == null) {
            zeroPlanes = new TagTree(width, height);
        }
        return zeroPlanes;
    }
}
