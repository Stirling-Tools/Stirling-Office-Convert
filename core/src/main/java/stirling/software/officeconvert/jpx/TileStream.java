package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class TileStream {

    final MarkerSet markers = new MarkerSet();

    private final List<int[]> bodies = new ArrayList<>();

    private final ByteArrayOutputStream headers = new ByteArrayOutputStream();

    private boolean separateHeaders;

    void add(MarkerSet part, int bodyStart, int bodyEnd, byte[] ppm) {
        if (part.cod != null) {
            markers.cod = part.cod;
        }
        markers.coc.putAll(part.coc);
        if (part.qcd != null) {
            markers.qcd = part.qcd;
        }
        markers.qcc.putAll(part.qcc);
        markers.roi.putAll(part.roi);
        markers.pocs.addAll(part.pocs);
        byte[] ppt = part.packedHeaders();
        if (ppm != null || ppt.length > 0) {
            separateHeaders = true;
            headers.writeBytes(ppm != null ? ppm : ppt);
        }
        bodies.add(new int[] {bodyStart, bodyEnd});
    }

    boolean present() {
        return !bodies.isEmpty();
    }

    boolean separateHeaders() {
        return separateHeaders;
    }

    byte[] headers() {
        return headers.toByteArray();
    }

    int[] body(byte[] data, byte[][] out) {
        if (bodies.size() == 1) {
            out[0] = data;
            return bodies.get(0).clone();
        }
        int total = 0;
        for (int[] b : bodies) {
            total += b[1] - b[0];
        }
        byte[] all = new byte[total];
        int at = 0;
        for (int[] b : bodies) {
            System.arraycopy(data, b[0], all, at, b[1] - b[0]);
            at += b[1] - b[0];
        }
        out[0] = all;
        return new int[] {0, total};
    }
}
