package stirling.software.officeconvert.jpx;

import java.util.ArrayList;
import java.util.List;

final class Codestream {

    final byte[] data;

    final Siz siz;

    final MarkerSet main;

    final TileStream[] tiles;

    private Codestream(byte[] data, Siz siz, MarkerSet main, TileStream[] tiles) {
        this.data = data;
        this.siz = siz;
        this.main = main;
        this.tiles = tiles;
    }

    static Siz header(byte[] data, int start, int end) throws JpxException {
        Bytes b = new Bytes(data, start, end);
        if (b.u16() != HeaderParser.SOC || b.u16() != HeaderParser.SIZ) {
            throw new JpxException("Not a JPEG 2000 codestream");
        }
        return Siz.read(b);
    }

    static Codestream parse(byte[] data, int start, int end) throws JpxException {
        Bytes b = new Bytes(data, start, end);
        if (b.u16() != HeaderParser.SOC || b.u16() != HeaderParser.SIZ) {
            throw new JpxException("Not a JPEG 2000 codestream");
        }
        Siz siz = Siz.read(b);
        MarkerSet main = new MarkerSet();
        HeaderParser.read(b, siz.components(), main, true);
        if (main.cod == null || main.qcd == null) {
            throw new JpxException("JPEG 2000 codestream has no coding or quantization defaults");
        }
        List<byte[]> ppm = main.packed.isEmpty() ? null : ppmChunks(main.packedHeaders());
        TileStream[] tiles = new TileStream[siz.tiles()];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new TileStream();
        }
        int part = 0;
        while (b.remaining() >= 12 && b.peekU16() == HeaderParser.SOT) {
            int sot = b.pos();
            b.skip(2);
            int length = b.u16();
            int index = b.u16();
            long psot = b.u32();
            b.skip(2);
            if (length != 10 || index >= tiles.length) {
                throw new JpxException("Invalid JPEG 2000 tile-part header");
            }
            MarkerSet markers = new MarkerSet();
            try {
                HeaderParser.read(b, siz.components(), markers, false);
            } catch (JpxException e) {
                break;
            }
            int bodyStart = b.pos();
            int bodyEnd = psot == 0 ? streamEnd(data, bodyStart, end) : (int) Math.min(sot + psot, end);
            if (bodyEnd < bodyStart) {
                break;
            }
            byte[] chunk = ppm == null ? null : part < ppm.size() ? ppm.get(part) : new byte[0];
            tiles[index].add(markers, bodyStart, bodyEnd, chunk);
            part++;
            b.seek(bodyEnd);
        }
        return new Codestream(data, siz, main, tiles);
    }

    private static int streamEnd(byte[] data, int from, int end) {
        if (end - 2 >= from && (data[end - 2] & 0xFF) == 0xFF && (data[end - 1] & 0xFF) == 0xD9) {
            return end - 2;
        }
        return end;
    }

    private static List<byte[]> ppmChunks(byte[] all) throws JpxException {
        List<byte[]> chunks = new ArrayList<>();
        Bytes b = new Bytes(all, 0, all.length);
        while (b.remaining() >= 4) {
            long n = b.u32();
            int take = (int) Math.min(n, b.remaining());
            byte[] chunk = new byte[take];
            System.arraycopy(all, b.pos(), chunk, 0, take);
            b.skip(take);
            chunks.add(chunk);
        }
        return chunks;
    }
}
