package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class HeaderParser {

    static final int SOC = 0xFF4F;
    static final int SIZ = 0xFF51;
    static final int COD = 0xFF52;
    static final int COC = 0xFF53;
    static final int TLM = 0xFF55;
    static final int PLM = 0xFF57;
    static final int PLT = 0xFF58;
    static final int QCD = 0xFF5C;
    static final int QCC = 0xFF5D;
    static final int RGN = 0xFF5E;
    static final int POC = 0xFF5F;
    static final int PPM = 0xFF60;
    static final int PPT = 0xFF61;
    static final int SOT = 0xFF90;
    static final int SOP = 0xFF91;
    static final int EPH = 0xFF92;
    static final int SOD = 0xFF93;
    static final int EOC = 0xFFD9;

    private HeaderParser() {}

    static void read(Bytes b, int components, MarkerSet into, boolean main) throws JpxException {
        int stop = main ? SOT : SOD;
        while (true) {
            int marker = b.peekU16();
            if (marker == stop) {
                if (!main) {
                    b.skip(2);
                }
                return;
            }
            if (marker < 0xFF00) {
                throw new JpxException("Invalid JPEG 2000 marker " + Integer.toHexString(marker));
            }
            b.skip(2);
            if (marker >= 0xFF30 && marker <= 0xFF3F) {
                continue;
            }
            int length = b.u16();
            if (length < 2) {
                throw new JpxException("Invalid JPEG 2000 marker length");
            }
            int start = b.pos();
            int end = start + length - 2;
            if (end > b.end()) {
                throw new JpxException("JPEG 2000 marker runs past the data");
            }
            segment(b, marker, length, components, into, main);
            b.seek(end);
        }
    }

    private static void segment(Bytes b, int marker, int length, int components, MarkerSet into, boolean main)
            throws JpxException {
        int indexBytes = components < 257 ? 1 : 2;
        switch (marker) {
            case COD -> into.cod = CodingDefaults.read(b);
            case COC -> {
                int c = component(b, indexBytes, components);
                int flags = b.u8();
                into.coc.put(c, ComponentStyle.read(b, (flags & 1) != 0));
            }
            case QCD -> into.qcd = Quant.read(b, length - 2);
            case QCC -> {
                int c = component(b, indexBytes, components);
                into.qcc.put(c, Quant.read(b, length - 2 - indexBytes));
            }
            case RGN -> {
                int c = component(b, indexBytes, components);
                if (b.u8() == 0) {
                    into.roi.put(c, b.u8());
                }
            }
            case POC -> poc(b, length, indexBytes, into);
            case PPM -> packed(b, length, into, main);
            case PPT -> packed(b, length, into, !main);
            default -> {
            }
        }
    }

    private static int component(Bytes b, int indexBytes, int components) throws JpxException {
        int c = indexBytes == 1 ? b.u8() : b.u16();
        if (c >= components) {
            throw new JpxException("JPEG 2000 marker names a missing component");
        }
        return c;
    }

    private static void poc(Bytes b, int length, int indexBytes, MarkerSet into) throws JpxException {
        int entry = 5 + 2 * indexBytes;
        int count = (length - 2) / entry;
        for (int i = 0; i < count; i++) {
            int rs = b.u8();
            int cs = indexBytes == 1 ? b.u8() : b.u16();
            int lye = b.u16();
            int re = b.u8();
            int ce = indexBytes == 1 ? b.u8() : b.u16();
            if (ce == 0) {
                ce = indexBytes == 1 ? 256 : 16384;
            }
            int order = b.u8();
            if (order > 4) {
                throw new JpxException("Invalid JPEG 2000 progression order change");
            }
            into.pocs.add(new Poc(rs, cs, lye, re, ce, order));
        }
    }

    private static void packed(Bytes b, int length, MarkerSet into, boolean allowed) throws JpxException {
        if (!allowed || length < 3) {
            throw new JpxException("Misplaced JPEG 2000 packed packet headers");
        }
        int index = b.u8();
        byte[] data = new byte[length - 3];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) b.u8();
        }
        byte[] old = into.packed.get(index);
        if (old != null) {
            data = concat(old, data);
        }
        into.packed.put(index, data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
