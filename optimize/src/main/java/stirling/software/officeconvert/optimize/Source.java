package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.ICOSParser;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.COSParser;
import org.apache.pdfbox.pdfparser.BruteForceParser;

final class Source {

    interface Visitor {
        void visit(COSObjectKey key, COSBase object) throws IOException;
    }

    private final COSDocument cos;

    private final ICOSParser parser;

    private final Map<COSObjectKey, Long> xref;

    private final Map<COSObjectKey, COSBase> injected = new HashMap<>();

    private Map<Long, Map<Long, COSBase>> containers;

    Source(COSDocument cos) {
        this.cos = cos;
        this.parser = parser(cos);
        this.xref = cos.getXrefTable();
    }

    private static ICOSParser parser(COSDocument cos) {
        try {
            Field f = COSDocument.class.getDeclaredField("parser");
            f.setAccessible(true);
            return (ICOSParser) f.get(cos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    void release() {
        try {
            Field f = COSDocument.class.getDeclaredField("objectPool");
            f.setAccessible(true);
            Map<?, ?> pool = (Map<?, ?>) f.get(cos);
            pool.clear();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
    }

    static long pack(COSObjectKey k) {
        return k.getNumber() << 16 | (k.getGeneration() & 0xFFFF);
    }

    List<COSObjectKey> keys() {
        List<COSObjectKey> top = new ArrayList<>();
        Map<Long, List<COSObjectKey>> packed = new HashMap<>();
        for (Map.Entry<COSObjectKey, Long> e : xref.entrySet()) {
            Long off = e.getValue();
            if (off == null) {
                continue;
            }
            if (off < 0) {
                packed.computeIfAbsent(-off, k -> new ArrayList<>()).add(e.getKey());
            } else if (off > 0) {
                top.add(e.getKey());
            }
        }
        top.sort((a, b) -> Long.compare(xref.get(a), xref.get(b)));
        List<COSObjectKey> ordered = new ArrayList<>(xref.size());
        ordered.addAll(top);
        List<Long> containers = new ArrayList<>(packed.keySet());
        containers.sort((a, b) -> Long.compare(containerOffset(a), containerOffset(b)));
        for (Long c : containers) {
            List<COSObjectKey> members = packed.get(c);
            members.sort((a, b) -> Long.compare(a.getNumber(), b.getNumber()));
            ordered.addAll(members);
        }
        return ordered;
    }

    void keepContainers(boolean keep) {
        containers = keep ? new HashMap<>() : null;
    }

    void inject(COSObjectKey key, COSBase object) {
        injected.put(key, object);
    }

    boolean rebuilt() {
        return bruteForce() != null;
    }

    private BruteForceParser bruteForce() {
        if (!(parser instanceof COSParser)) {
            return null;
        }
        try {
            Field field = COSParser.class.getDeclaredField("bruteForceParser");
            field.setAccessible(true);
            return field.get(parser) instanceof BruteForceParser recovery && recovery.bfSearchTriggered() ? recovery
                    : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<COSObjectKey, Long> recovered() {
        BruteForceParser brute = bruteForce();
        if (brute == null) {
            return Map.of();
        }
        try {
            Field field = BruteForceParser.class.getDeclaredField("bfSearchCOSObjectKeyOffsets");
            field.setAccessible(true);
            return (Map<COSObjectKey, Long>) field.get(brute);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    List<COSObjectKey> recover(List<COSObjectKey> missing, Visitor visitor) throws IOException {
        if (missing.isEmpty()) {
            return List.of();
        }
        Map<COSObjectKey, Long> recovered = recovered();
        if (recovered.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> generations = new HashMap<>();
        for (COSObjectKey key : missing) {
            Long offset = recovered.get(key);
            if (!xref.containsKey(key) && offset != null && offset != 0) {
                generations.put(key.getNumber(), -1);
            }
        }
        if (generations.isEmpty()) {
            return List.of();
        }
        int scanned = 0;
        for (COSObjectKey key : xref.keySet()) {
            Integer generation = generations.get(key.getNumber());
            if (generation != null) {
                generations.put(key.getNumber(), Math.max(generation, key.getGeneration()));
            }
            if ((++scanned & 1023) == 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
        }
        List<COSObjectKey> added = new ArrayList<>();
        for (COSObjectKey key : missing) {
            Integer generation = generations.get(key.getNumber());
            if (generation != null && generation < 0 && !xref.containsKey(key)) {
                Long offset = recovered.get(key);
                if (offset != null && offset != 0) {
                    xref.put(key, offset);
                    added.add(key);
                }
            }
        }
        load(added, visitor);
        return added;
    }

    Map<COSObjectKey, Long> locate(List<COSObjectKey> keys) throws IOException {
        return parser == null ? Map.of() : Truncated.locate(parser, keys);
    }

    COSStream cutOff(long offset) {
        try {
            return parser == null ? null : Truncated.read(parser, offset, Recoder.MAX_RAW_BYTES);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private long containerOffset(long number) {
        Long off = xref.get(new COSObjectKey(number, 0));
        return off == null ? Long.MAX_VALUE : off;
    }

    boolean exists(COSObjectKey key) {
        if (injected.containsKey(key)) {
            return true;
        }
        Long off = xref.get(key);
        return off != null && off != 0;
    }

    void load(List<COSObjectKey> keys, Visitor visitor) throws IOException {
        Map<Long, List<COSObjectKey>> packed = new HashMap<>();
        List<Long> order = new ArrayList<>();
        int done = 0;
        for (COSObjectKey key : keys) {
            Long off = xref.get(key);
            if (off != null && off < 0) {
                List<COSObjectKey> members = packed.get(-off);
                if (members == null) {
                    members = new ArrayList<>();
                    packed.put(-off, members);
                    order.add(-off);
                }
                members.add(key);
            } else {
                visitor.visit(key, top(key));
            }
            if ((++done & 1023) == 0) {
                stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
            }
        }
        for (Long container : order) {
            Map<Long, COSBase> objects = unpack(container);
            for (COSObjectKey key : packed.get(container)) {
                COSBase b = objects.get(key.getNumber());
                visitor.visit(key, b == null ? COSNull.NULL : b);
            }
            stirling.software.officeconvert.extract.PdfFiles.stopIfInterrupted();
        }
    }

    COSBase load(COSObjectKey key) throws IOException {
        COSBase own = injected.get(key);
        if (own != null) {
            return own;
        }
        Long off = xref.get(key);
        if (off != null && off < 0) {
            Map<Long, COSBase> objects = containers == null ? unpack(-off)
                    : containers.computeIfAbsent(-off, this::unpack);
            COSBase b = objects.get(key.getNumber());
            return b == null ? COSNull.NULL : b;
        }
        return top(key);
    }

    private COSBase top(COSObjectKey key) {
        COSBase b;
        try {
            b = parser != null ? new COSObject(key, parser).getObject() : cos.getObjectFromPool(key).getObject();
        } catch (RuntimeException e) {
            b = null;
        }
        return b == null ? COSNull.NULL : b;
    }

    private static final class Unpacker extends PDFObjectStreamParser {

        Unpacker(COSStream stream, COSDocument document) throws IOException {
            super(stream, document);
        }

        @Override
        protected COSObjectKey getObjectKey(long num, int gen) {
            return new COSObjectKey(num, gen);
        }
    }

    private Map<Long, COSBase> unpack(long container) {
        Map<Long, COSBase> objects = new HashMap<>();
        if (!(top(new COSObjectKey(container, 0)) instanceof COSStream stream)) {
            return objects;
        }
        try {
            for (Map.Entry<COSObjectKey, COSBase> e : new Unpacker(stream, cos).parseAllObjects()
                    .entrySet()) {
                objects.putIfAbsent(e.getKey().getNumber(), e.getValue());
            }
            return objects;
        } catch (IOException | RuntimeException e) {
            objects.clear();
        }
        try {
            Unpacker members = new Unpacker(stream, cos);
            for (Long number : members.readObjectNumbers().keySet()) {
                try {
                    COSBase b = members.parseObject(number);
                    if (b != null) {
                        objects.putIfAbsent(number, b);
                    }
                } catch (IOException | RuntimeException e) {
                    continue;
                }
            }
        } catch (IOException | RuntimeException e) {
            return objects;
        }
        return objects;
    }
}
