package stirling.software.officeconvert.html;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Tbs {

    private static final int TYPE = 8;

    private static final int NEGATIVE_TYPE = 5;

    private static final int SPANS = 0b1;

    private static final int TYPED = 0b10;

    private static final int COUNTED = 0b100;

    private static final int UPWARDS = 0b1000;

    record Node(int index, long start, long length, int depth, Integer parent) {}

    private record Local(Node node, boolean spans) {}

    private record Sequence(long value, int flags, int count) {}

    private static final class NegativeIndex extends Exception {
        private static final long serialVersionUID = 1L;

        NegativeIndex() {
            super(null, null, false, false);
        }
    }

    private Tbs() {}

    static List<byte[]> sequences(List<Node> entries, long textLength, int recordSize) {
        List<Node> byStart = new ArrayList<>(entries);
        byStart.sort(Comparator.comparingLong(Node::start));
        List<List<Map<Integer, List<Local>>>> perRecord = new ArrayList<>();
        for (long recordStart = 0; recordStart < textLength; recordStart += recordSize) {
            long length = Math.min(recordSize, textLength - recordStart);
            List<Local> local = new ArrayList<>();
            for (Node n : byStart) {
                if (n.start() >= recordStart + length) {
                    break;
                }
                if (n.start() + n.length() <= recordStart) {
                    continue;
                }
                long offset = n.start() - recordStart;
                local.add(new Local(n, offset < 0 && offset + n.length() > length));
            }
            perRecord.add(strands(local));
        }
        try {
            return encodeAll(perRecord, TYPE);
        } catch (NegativeIndex e) {
            try {
                return encodeAll(perRecord, NEGATIVE_TYPE);
            } catch (NegativeIndex impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static List<byte[]> encodeAll(List<List<Map<Integer, List<Local>>>> perRecord, int type)
            throws NegativeIndex {
        List<byte[]> out = new ArrayList<>(perRecord.size());
        for (List<Map<Integer, List<Local>>> strands : perRecord) {
            out.add(bytes(encode(strands, type), type));
        }
        return out;
    }

    private static List<Map<Integer, List<Local>>> strands(List<Local> local) {
        List<Map<Integer, List<Local>>> out = new ArrayList<>();
        List<Local> rest = new ArrayList<>(local);
        while (!rest.isEmpty()) {
            Local top = rest.remove(0);
            Map<Integer, List<Local>> layers = new LinkedHashMap<>();
            for (Local l : strand(top, rest)) {
                layers.computeIfAbsent(l.node().depth(), k -> new ArrayList<>()).add(l);
            }
            out.add(layers);
        }
        return out;
    }

    private static List<Local> strand(Local parent, List<Local> rest) {
        List<Local> out = new ArrayList<>();
        out.add(parent);
        Local child = firstChild(parent, rest);
        if (child != null) {
            rest.remove(child);
            out.addAll(strand(child, rest));
            return out;
        }
        int current = parent.node().index();
        for (Local l : new ArrayList<>(rest)) {
            Node n = l.node();
            if (n.depth() != parent.node().depth() || !Objects.equals(n.parent(), parent.node().parent())
                    || n.index() != current + 1) {
                continue;
            }
            current++;
            rest.remove(l);
            if (firstChild(l, rest) != null) {
                out.addAll(strand(l, rest));
                break;
            }
            out.add(l);
        }
        return out;
    }

    private static Local firstChild(Local parent, List<Local> rest) {
        for (Local l : rest) {
            Integer p = l.node().parent();
            if (p != null && p == parent.node().index()) {
                return l;
            }
        }
        return null;
    }

    private static List<Sequence> encode(List<Map<Integer, List<Local>>> strands, int type) throws NegativeIndex {
        Local first = strands.isEmpty() ? null : strands.get(0).values().iterator().next().get(0);
        List<Sequence> out = new ArrayList<>();
        int last = 0;
        for (Map<Integer, List<Local>> strand : strands) {
            List<Sequence> seqs = new ArrayList<>();
            for (List<Local> layer : strand.values()) {
                Local head = layer.get(0);
                int flags = 0;
                if (layer.get(layer.size() - 1).spans()) {
                    flags |= SPANS;
                }
                if (head == first) {
                    flags |= TYPED;
                }
                if (layer.size() > 1) {
                    flags |= COUNTED;
                }
                Integer parent = head.node().parent();
                long value = head.node().index() - (parent == null ? 0 : parent);
                if (!out.isEmpty() && seqs.isEmpty()) {
                    value = last - head.node().index();
                    if (value < 0) {
                        if (type != NEGATIVE_TYPE) {
                            throw new NegativeIndex();
                        }
                        value = -value;
                    } else {
                        flags |= UPWARDS;
                    }
                }
                last = layer.get(layer.size() - 1).node().index();
                seqs.add(new Sequence(value, flags, layer.size()));
            }
            for (int i = 0; i + 1 < seqs.size(); i++) {
                Sequence s = seqs.get(i);
                if ((s.flags() & SPANS) != 0 && (seqs.get(i + 1).flags() & SPANS) != 0) {
                    seqs.set(i, new Sequence(s.value(), s.flags() & ~SPANS, s.count()));
                }
            }
            out.addAll(seqs);
        }
        return out;
    }

    private static byte[] bytes(List<Sequence> sequences, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int flagSize = 3;
        for (Sequence s : sequences) {
            out.writeBytes(Kf8Index.varint(s.value() << flagSize | s.flags()));
            if ((s.flags() & TYPED) != 0) {
                out.writeBytes(Kf8Index.varint(type));
            }
            if ((s.flags() & COUNTED) != 0) {
                out.write(s.count());
            }
            if ((s.flags() & SPANS) != 0) {
                out.writeBytes(Kf8Index.varint(0));
            }
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
