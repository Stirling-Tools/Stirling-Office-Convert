package stirling.software.officeconvert.jpx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;

final class PacketReader implements Progression.Visitor {

    static final int MAX_PASSES = 1024;

    static final int SEGMENT_PASSES = 109;

    private final TileComponent[] comps;

    private final byte[] body;

    private final int end;

    private int pos;

    private final HeaderBits bits = new HeaderBits();

    private final boolean separate;

    private final boolean sop;

    private final boolean eph;

    private Segment[] pendingSegments = new Segment[64];

    private int[] pendingLengths = new int[64];

    private int[] pendingCounts = new int[64];

    private int pending;

    PacketReader(TileComponent[] comps, byte[] body, int start, int end, byte[] headers, boolean sop, boolean eph) {
        this.comps = comps;
        this.body = body;
        this.pos = start;
        this.end = end;
        this.separate = headers != null;
        this.sop = sop;
        this.eph = eph;
        if (separate) {
            bits.reset(headers, 0, headers.length);
        }
    }

    @Override
    public boolean packet(int layer, int r, int c, int k) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("JPEG 2000 decoding interrupted");
        }
        if (pos >= end && !separate) {
            return false;
        }
        TileComponent tc = comps[c];
        Resolution res = tc.resolutions[r];
        boolean keep = r <= tc.top;
        Precinct precinct = res.precinct(k);
        if (sop && pos + 6 <= end && (body[pos] & 0xFF) == 0xFF && (body[pos + 1] & 0xFF) == 0x91) {
            pos += 6;
        }
        if (!separate) {
            bits.reset(body, pos, end);
        }
        pending = 0;
        try {
            if (bits.bit() != 0) {
                for (int b = 0; b < precinct.bands().length; b++) {
                    blocks(precinct.bands()[b], layer, tc.style, keep);
                }
            }
            bits.align();
        } catch (JpxException e) {
            for (int i = 0; i < pending; i++) {
                if (pendingSegments[i] != null) {
                    pendingSegments[i].passes -= pendingCounts[i];
                }
            }
            return false;
        }
        if (eph && bits.marker(HeaderParser.EPH)) {
            bits.skip(2);
        }
        if (!separate) {
            pos = bits.pos();
        }
        for (int i = 0; i < pending; i++) {
            int len = pendingLengths[i];
            boolean truncated = len > end - pos;
            if (truncated) {
                len = end - pos;
            }
            if (pendingSegments[i] != null) {
                pendingSegments[i].add(pos, len);
            }
            pos += len;
            if (truncated) {
                return false;
            }
        }
        return true;
    }

    private void blocks(PrecinctBand pb, int layer, ComponentStyle style, boolean keep)
            throws JpxException {
        for (int i = 0; i < pb.blocks.length; i++) {
            CodeBlock cb = pb.blocks[i];
            if (!cb.included) {
                if (!pb.inclusion().below(i, layer + 1, bits)) {
                    continue;
                }
                cb.zeroPlanes = pb.zeroPlanes().value(i, 64, bits);
                cb.included = true;
            } else if (bits.bit() == 0) {
                continue;
            }
            int n = passes();
            while (bits.bit() != 0) {
                if (++cb.lblock > 32) {
                    throw new JpxException("Invalid JPEG 2000 code-block length");
                }
            }
            int first = cb.passes;
            int total = first + n;
            if (total > MAX_PASSES) {
                throw new JpxException("Too many JPEG 2000 coding passes");
            }
            int p = first;
            while (p < total) {
                int id = segmentId(style, p);
                int q = p + 1;
                while (q < total && segmentId(style, q) == id) {
                    q++;
                }
                int count = q - p;
                int lengthBits = cb.lblock + 31 - Integer.numberOfLeadingZeros(count);
                if (lengthBits > 31) {
                    throw new JpxException("Invalid JPEG 2000 code-block length");
                }
                int len = bits.bits(lengthBits);
                Segment seg = null;
                if (keep) {
                    seg = cb.segment(id, p);
                    seg.passes += count;
                }
                addPending(seg, len, count);
                p = q;
            }
            cb.passes = total;
        }
    }

    private void addPending(Segment seg, int len, int count) {
        if (pending == pendingSegments.length) {
            pendingSegments = Arrays.copyOf(pendingSegments, pending * 2);
            pendingLengths = Arrays.copyOf(pendingLengths, pending * 2);
            pendingCounts = Arrays.copyOf(pendingCounts, pending * 2);
        }
        pendingSegments[pending] = seg;
        pendingCounts[pending] = count;
        pendingLengths[pending++] = len;
    }

    private int passes() throws JpxException {
        if (bits.bit() == 0) {
            return 1;
        }
        if (bits.bit() == 0) {
            return 2;
        }
        int two = bits.bits(2);
        if (two != 3) {
            return 3 + two;
        }
        int five = bits.bits(5);
        if (five != 31) {
            return 6 + five;
        }
        return 37 + bits.bits(7);
    }

    static int segmentId(ComponentStyle style, int pass) {
        if (style.has(ComponentStyle.TERMALL)) {
            return pass;
        }
        if (style.has(ComponentStyle.BYPASS)) {
            return pass < 10 ? 0 : 1 + 2 * ((pass - 10) / 3) + ((pass - 10) % 3 == 2 ? 1 : 0);
        }
        return pass / SEGMENT_PASSES;
    }
}
