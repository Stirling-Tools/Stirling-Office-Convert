package stirling.software.officeconvert.jpx;

final class BlockBudget {

    static final long MAX_BLOCKS = 1 << 21;

    private long used;

    void take(long blocks) throws JpxException {
        used += blocks;
        if (used > MAX_BLOCKS) {
            throw new JpxException("JPEG 2000 tile has too many code-blocks");
        }
    }
}
