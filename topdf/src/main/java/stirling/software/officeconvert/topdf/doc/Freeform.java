package stirling.software.officeconvert.topdf.doc;

import org.apache.poi.ddf.EscherContainerRecord;

final class Freeform {

    static final int MAX_POINTS = 20000;

    private Freeform() {}

    static String geometry(EscherContainerRecord sp) {
        int[][] pts = vertices(Shapes.complex(sp, 0x0145));
        if (pts == null || pts.length < 2) {
            return null;
        }
        long left = Shapes.prop(sp, 0x0140, 0);
        long top = Shapes.prop(sp, 0x0141, 0);
        long w = Math.max(1, Shapes.prop(sp, 0x0142, 21600) - left);
        long h = Math.max(1, Shapes.prop(sp, 0x0143, 21600) - top);
        StringBuilder path = new StringBuilder();
        byte[] seg = Shapes.complex(sp, 0x0146);
        int used = seg == null || seg.length < 6 ? polyline(pts, left, top, path)
                : segments(seg, pts, left, top, path);
        if (used < 2) {
            return null;
        }
        return "<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/>"
                + "<a:pathLst><a:path w=\"" + w + "\" h=\"" + h + "\">" + path + "</a:path></a:pathLst></a:custGeom>";
    }

    private static int[][] vertices(byte[] d) {
        if (d == null || d.length < 6) {
            return null;
        }
        int n = Math.min(MAX_POINTS, Sprm.u16(d, 0));
        int cb = Sprm.u16(d, 4);
        int size = cb == 0xFFF0 ? 4 : cb;
        if (size != 4 && size != 8) {
            return null;
        }
        n = Math.min(n, (d.length - 6) / size);
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int at = 6 + i * size;
            out[i] = size == 4 ? new int[] {(short) Sprm.u16(d, at), (short) Sprm.u16(d, at + 2)}
                    : new int[] {Sprm.s32(d, at), Sprm.s32(d, at + 4)};
        }
        return out;
    }

    private static int polyline(int[][] pts, long left, long top, StringBuilder path) {
        for (int i = 0; i < pts.length; i++) {
            path.append(i == 0 ? "<a:moveTo>" : "<a:lnTo>");
            pt(path, pts[i], left, top);
            path.append(i == 0 ? "</a:moveTo>" : "</a:lnTo>");
        }
        return pts.length;
    }

    private static int segments(byte[] seg, int[][] pts, long left, long top, StringBuilder path) {
        int n = Sprm.u16(seg, 0);
        int cb = Sprm.u16(seg, 4);
        int size = cb == 0xFFF0 ? 2 : cb;
        if (size != 2 && size != 4) {
            return 0;
        }
        n = Math.min(n, (seg.length - 6) / size);
        int at = 0;
        int drawn = 0;
        for (int i = 0; i < n && at <= pts.length; i++) {
            int v = Sprm.u16(seg, 6 + i * size);
            int type = v >>> 13;
            int count = type == 5 ? v & 0xFF : v & 0x1FFF;
            switch (type) {
                case 0 -> {
                    for (int k = 0; k < Math.max(1, count) && at < pts.length; k++) {
                        path.append("<a:lnTo>");
                        pt(path, pts[at++], left, top);
                        path.append("</a:lnTo>");
                        drawn++;
                    }
                }
                case 1 -> {
                    for (int k = 0; k < Math.max(1, count) && at + 2 < pts.length; k++) {
                        path.append("<a:cubicBezTo>");
                        for (int j = 0; j < 3; j++) {
                            pt(path, pts[at++], left, top);
                        }
                        path.append("</a:cubicBezTo>");
                        drawn += 3;
                    }
                }
                case 2 -> {
                    if (at < pts.length) {
                        path.append("<a:moveTo>");
                        pt(path, pts[at++], left, top);
                        path.append("</a:moveTo>");
                        drawn++;
                    }
                }
                case 3 -> path.append("<a:close/>");
                case 4 -> i = n;
                case 5 -> at += count;
                default -> {
                }
            }
        }
        return drawn;
    }

    private static void pt(StringBuilder b, int[] p, long left, long top) {
        b.append("<a:pt x=\"").append(p[0] - left).append("\" y=\"").append(p[1] - top).append("\"/>");
    }
}
