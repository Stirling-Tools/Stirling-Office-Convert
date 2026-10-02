package stirling.software.officeconvert.jpx;

import java.util.Comparator;

final class PrecinctCursor {

    final int c;

    final int r;

    private final Resolution res;

    private final long dx;

    private final long dy;

    private final long[] tile;

    private int i;

    private int j;

    long y;

    long x;

    PrecinctCursor(int c, int r, Resolution res, long dx, long dy, long[] tile) {
        this.c = c;
        this.r = r;
        this.res = res;
        this.dx = dx;
        this.dy = dy;
        this.tile = tile;
        y = trigger(0, res.y0, res.ppy, dy, tile[1]);
        x = trigger(0, res.x0, res.ppx, dx, tile[0]);
    }

    int precinct() {
        return j * res.precinctsWide + i;
    }

    boolean advance() {
        if (++i == res.precinctsWide) {
            i = 0;
            if (++j == res.precinctsHigh) {
                return false;
            }
            y = trigger(j, res.y0, res.ppy, dy, tile[1]);
        }
        x = trigger(i, res.x0, res.ppx, dx, tile[0]);
        return true;
    }

    static Comparator<PrecinctCursor> order(int order) {
        return switch (order) {
            case Progression.RPCL -> Comparator.<PrecinctCursor>comparingInt(e -> e.r).thenComparingLong(e -> e.y)
                    .thenComparingLong(e -> e.x).thenComparingInt(e -> e.c);
            case Progression.PCRL -> Comparator.<PrecinctCursor>comparingLong(e -> e.y).thenComparingLong(e -> e.x)
                    .thenComparingInt(e -> e.c).thenComparingInt(e -> e.r);
            default -> Comparator.<PrecinctCursor>comparingInt(e -> e.c).thenComparingLong(e -> e.y)
                    .thenComparingLong(e -> e.x).thenComparingInt(e -> e.r);
        };
    }

    private static long trigger(int index, int r0, int pp, long scale, long tileOrigin) {
        long first = (long) (r0 >> pp) << pp;
        if (index == 0) {
            return first == r0 ? r0 * scale : tileOrigin;
        }
        return (first + ((long) index << pp)) * scale;
    }
}
