package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

final class Tbs {

    private static final int TYPE = 8;

    private static final int NEGATIVE_TYPE = 5;

    private record Local(int index, long startOffset, long endOffset, boolean spans) {}

    private Tbs() {}

    static List<byte[]> sequences(List<long[]> entries, long textLength, int recordSize) {
        List<List<Local>> perRecord = new ArrayList<>();
        for (long recordStart = 0; recordStart < textLength; recordStart += recordSize) {
            long length = Math.min(recordSize, textLength - recordStart);
            long next = recordStart + length;
            List<Local> local = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                long start = entries.get(i)[0];
                long end = start + entries.get(i)[1];
                if (start >= next || end <= recordStart) {
                    continue;
                }
                long offset = start - recordStart;
                long endOffset = offset + entries.get(i)[1];
                local.add(new Local(i, offset, endOffset, offset < 0 && endOffset > length));
            }
            perRecord.add(local);
        }
        List<byte[]> out = new ArrayList<>();
        boolean negative = false;
        for (List<Local> local : perRecord) {
            if (encode(local, TYPE) == null) {
                negative = true;
                break;
            }
        }
        for (List<Local> local : perRecord) {
            out.add(encode(local, negative ? NEGATIVE_TYPE : TYPE));
        }
        return out;
    }

    private static byte[] encode(List<Local> local, int type) {
        List<List<Local>> strands = new ArrayList<>();
        for (Local l : local) {
            List<Local> current = strands.isEmpty() ? null : strands.get(strands.size() - 1);
            if (current != null && current.get(current.size() - 1).index() + 1 == l.index()) {
                current.add(l);
            } else {
                List<Local> strand = new ArrayList<>();
                strand.add(l);
                strands.add(strand);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Integer last = null;
        int flagSize = 3;
        int firstIndex = local.isEmpty() ? -1 : local.get(0).index();
        for (List<Local> strand : strands) {
            int flags = 0;
            long index = strand.get(0).index();
            if (last != null) {
                index = last - strand.get(0).index();
                if (index < 0) {
                    if (type != NEGATIVE_TYPE) {
                        return null;
                    }
                    index = -index;
                } else {
                    flags |= 0b1000;
                }
            }
            boolean spans = strand.get(strand.size() - 1).spans();
            if (spans) {
                flags |= 0b1;
            }
            if (strand.get(0).index() == firstIndex) {
                flags |= 0b10;
            }
            if (strand.size() > 1) {
                flags |= 0b100;
            }
            out.writeBytes(Kf8Index.varint(index << flagSize | flags));
            if ((flags & 0b10) != 0) {
                out.writeBytes(Kf8Index.varint(type));
            }
            if ((flags & 0b100) != 0) {
                out.write(strand.size());
            }
            if (spans) {
                out.writeBytes(Kf8Index.varint(0));
            }
            last = strand.get(strand.size() - 1).index();
            flagSize = 4;
        }
        return out.toByteArray();
    }

    static byte[] trailingEntry(byte[] data) {
        int sizeBytes = 1;
        while (true) {
            byte[] size = backward(data.length + sizeBytes);
            if (size.length == sizeBytes) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.writeBytes(data);
                out.writeBytes(size);
                return out.toByteArray();
            }
            sizeBytes++;
        }
    }

    private static byte[] backward(long v) {
        byte[] forward = Kf8Index.varint(v);
        forward[forward.length - 1] &= 0x7F;
        forward[0] |= (byte) 0x80;
        return forward;
    }
}
