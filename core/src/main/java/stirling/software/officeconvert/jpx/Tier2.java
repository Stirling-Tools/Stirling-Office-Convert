package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Tier2 {

    private final TileCoder[] tiles;

    private final int layers;

    private final int order;

    private final int levels;

    private final HeaderWriter header = new HeaderWriter();

    private final List<List<int[]>> packets = new ArrayList<>();

    private final int[] dx;

    private final int[] dy;

    Tier2(TileCoder[] tiles, int layers, int order, int levels, Quantiser q, int[] dx, int[] dy) {
        this.tiles = tiles;
        this.dx = dx;
        this.dy = dy;
        this.layers = layers;
        this.order = order;
        this.levels = levels;
        for (int t = 0; t < tiles.length; t++) {
            TileCoder tile = tiles[t];
            for (int c = 0; c < tile.components.length; c++) {
                TileCoder.Resolution[] res = tile.components[c].resolutions;
                for (int r = 0; r < res.length; r++) {
                    for (TileCoder.Blocks[] precinct : res[r].precincts) {
                        for (TileCoder.Blocks b : precinct) {
                            int n = b.coded.length;
                            if (n == 0) {
                                continue;
                            }
                            int mb = q.magnitudeBits(c, r, res[r].orients[b.band]);
                            int[] zeros = new int[n];
                            for (int i = 0; i < n; i++) {
                                EncodedBlock e = b.coded[i];
                                e.zeroPlanes = e.planes == 0 ? mb : mb - e.planes;
                                e.layerPasses = new int[layers];
                                zeros[i] = e.zeroPlanes;
                            }
                            b.inclusion = new TagTreeEncoder(b.gw, b.gh);
                            b.zeros = new TagTreeEncoder(b.gw, b.gh);
                            b.zeros.set(zeros);
                            b.lblock = new int[n];
                            b.sent = new int[n];
                        }
                    }
                }
            }
            packets.add(order(tile));
        }
    }

    private List<int[]> order(TileCoder tile) {
        List<int[]> list = new ArrayList<>();
        int comps = tile.components.length;
        if (order == Progression.LRCP || order == Progression.RLCP) {
            int outer = order == Progression.LRCP ? layers : levels + 1;
            int inner = order == Progression.LRCP ? levels + 1 : layers;
            for (int a = 0; a < outer; a++) {
                for (int b = 0; b < inner; b++) {
                    int l = order == Progression.LRCP ? a : b;
                    int r = order == Progression.LRCP ? b : a;
                    for (int c = 0; c < comps; c++) {
                        int n = tile.components[c].resolutions[r].precincts.length;
                        for (int k = 0; k < n; k++) {
                            list.add(new int[] {l, r, c, k});
                        }
                    }
                }
            }
            return list;
        }
        List<long[]> cursors = new ArrayList<>();
        for (int c = 0; c < comps; c++) {
            TileCoder.Resolution[] res = tile.components[c].resolutions;
            for (int r = 0; r <= levels; r++) {
                TileCoder.Resolution s = res[r];
                for (int k = 0; k < s.precincts.length; k++) {
                    int i = k % s.wide;
                    int j = k / s.wide;
                    long y = trigger(j, s.y0, (long) dy[c] << (levels - r), tile.y0);
                    long x = trigger(i, s.x0, (long) dx[c] << (levels - r), tile.x0);
                    cursors.add(new long[] {c, r, k, y, x});
                }
            }
        }
        Comparator<long[]> cmp = switch (order) {
            case Progression.RPCL -> Comparator.<long[]>comparingLong(e -> e[1]).thenComparingLong(e -> e[3])
                    .thenComparingLong(e -> e[4]).thenComparingLong(e -> e[0]);
            case Progression.PCRL -> Comparator.<long[]>comparingLong(e -> e[3]).thenComparingLong(e -> e[4])
                    .thenComparingLong(e -> e[0]).thenComparingLong(e -> e[1]);
            default -> Comparator.<long[]>comparingLong(e -> e[0]).thenComparingLong(e -> e[3])
                    .thenComparingLong(e -> e[4]).thenComparingLong(e -> e[1]);
        };
        cursors.sort(cmp.thenComparingLong(e -> e[2]));
        for (long[] e : cursors) {
            for (int l = 0; l < layers; l++) {
                list.add(new int[] {l, (int) e[1], (int) e[0], (int) e[2]});
            }
        }
        return list;
    }

    private static long trigger(int index, int r0, long scale, long tileOrigin) {
        long first = (long) (r0 >> TileCoder.PRECINCT) << TileCoder.PRECINCT;
        if (index == 0) {
            return first == r0 ? r0 * scale : tileOrigin;
        }
        return (first + ((long) index << TileCoder.PRECINCT)) * scale;
    }

    long tileSize(int t) throws InterruptedIOException {
        return write(t, null);
    }

    byte[] tile(int t) throws InterruptedIOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(t, out);
        return out.toByteArray();
    }

    private long write(int t, ByteArrayOutputStream out) throws InterruptedIOException {
        TileCoder tile = tiles[t];
        for (TileCoder.Component comp : tile.components) {
            for (TileCoder.Resolution res : comp.resolutions) {
                for (TileCoder.Blocks[] precinct : res.precincts) {
                    for (TileCoder.Blocks b : precinct) {
                        int n = b.coded.length;
                        if (n == 0) {
                            continue;
                        }
                        int[] first = new int[n];
                        for (int i = 0; i < n; i++) {
                            int[] lp = b.coded[i].layerPasses;
                            int f = layers;
                            for (int l = 0; l < layers; l++) {
                                if (lp[l] > 0) {
                                    f = l;
                                    break;
                                }
                            }
                            first[i] = f;
                            b.lblock[i] = 3;
                            b.sent[i] = 0;
                        }
                        b.inclusion.set(first);
                        b.zeros.restart();
                    }
                }
            }
        }
        long total = 0;
        int count = 0;
        for (int[] p : packets.get(t)) {
            if ((++count & 0xFF) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("JPEG 2000 encoding interrupted");
            }
            total += packet(tile, p[0], p[1], p[2], p[3], out);
        }
        return total;
    }

    private long packet(TileCoder tile, int l, int r, int c, int k, ByteArrayOutputStream out) {
        TileCoder.Blocks[] precinct = tile.components[c].resolutions[r].precincts[k];
        header.reset();
        boolean empty = true;
        for (TileCoder.Blocks b : precinct) {
            for (int i = 0; i < b.coded.length && empty; i++) {
                empty = b.coded[i].layerPasses[l] == b.sent[i];
            }
        }
        long body = 0;
        if (empty) {
            header.bit(0);
        } else {
            header.bit(1);
            for (TileCoder.Blocks b : precinct) {
                for (int i = 0; i < b.coded.length; i++) {
                    EncodedBlock e = b.coded[i];
                    int now = e.layerPasses[l];
                    int added = now - b.sent[i];
                    if (b.sent[i] == 0) {
                        b.inclusion.encode(i, l + 1, header);
                        if (added == 0) {
                            continue;
                        }
                        b.zeros.encode(i, e.zeroPlanes + 1, header);
                    } else {
                        header.bit(added > 0 ? 1 : 0);
                        if (added == 0) {
                            continue;
                        }
                    }
                    passes(added);
                    int bytes = e.length(now) - e.length(b.sent[i]);
                    int needed = 32 - Integer.numberOfLeadingZeros(bytes);
                    int bits = b.lblock[i] + 31 - Integer.numberOfLeadingZeros(added);
                    while (needed > bits) {
                        header.bit(1);
                        b.lblock[i]++;
                        bits++;
                    }
                    header.bit(0);
                    header.bits(bytes, bits);
                    body += bytes;
                }
            }
        }
        header.flush();
        if (out != null) {
            out.write(header.bytes(), 0, header.size());
            if (!empty) {
                for (TileCoder.Blocks b : precinct) {
                    for (int i = 0; i < b.coded.length; i++) {
                        EncodedBlock e = b.coded[i];
                        int from = e.length(b.sent[i]);
                        int to = e.length(e.layerPasses[l]);
                        out.write(e.data, from, to - from);
                    }
                }
            }
        }
        if (!empty) {
            for (TileCoder.Blocks b : precinct) {
                for (int i = 0; i < b.coded.length; i++) {
                    b.sent[i] = b.coded[i].layerPasses[l];
                }
            }
        }
        return header.size() + body;
    }

    private void passes(int n) {
        if (n == 1) {
            header.bit(0);
        } else if (n == 2) {
            header.bits(2, 2);
        } else if (n <= 5) {
            header.bits(3, 2);
            header.bits(n - 3, 2);
        } else if (n <= 36) {
            header.bits(15, 4);
            header.bits(n - 6, 5);
        } else {
            header.bits(511, 9);
            header.bits(n - 37, 7);
        }
    }
}
