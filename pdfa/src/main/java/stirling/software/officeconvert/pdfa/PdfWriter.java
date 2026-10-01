package stirling.software.officeconvert.pdfa;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.PdfFiles;

final class PdfWriter {

    static final int OBJECTS_PER_STREAM = 200;

    static final int BUFFERED_STREAM_BYTES = 16 << 20;

    static final int MAX_OBJECTS = 8_388_607;

    private static final COSName OBJ_STM = COSName.getPDFName("ObjStm");

    private final CountingOutput out;

    private final boolean objectStreams;

    private final ObjectSyntax syntax;

    private final Map<COSBase, Integer> numbers = new IdentityHashMap<>();

    private final ArrayDeque<COSBase> queue = new ArrayDeque<>();

    private Set<COSBase> once = Set.of();

    private final Map<Integer, COSInteger> lateLengths = new HashMap<>();

    private long[] offsets = new long[1024];

    private int[] container = new int[1024];

    private int next = 1;

    private final ByteArrayOutputStream packed = new ByteArrayOutputStream();

    private final StringBuilder packedIndex = new StringBuilder();

    private final int[] packedNumbers = new int[OBJECTS_PER_STREAM];

    private int packedCount;

    private PdfWriter(OutputStream os, boolean objectStreams) {
        this.out = new CountingOutput(new BufferedOutputStream(os, 1 << 16));
        this.objectStreams = objectStreams;
        this.syntax = new ObjectSyntax(new ObjectSyntax.Numbering() {
            @Override
            public int number(COSBase indirect) {
                return PdfWriter.this.number(indirect);
            }

            @Override
            public boolean single(COSBase container) {
                return once.contains(container);
            }
        });
    }

    static void write(PDDocument doc, OutputStream os, PdfALevel level) throws IOException {
        new PdfWriter(os, level.part() > 1).run(doc, level);
    }

    private void run(PDDocument doc, PdfALevel level) throws IOException {
        COSDictionary trailer = doc.getDocument().getTrailer();
        COSDictionary root = ContentGraph.dict(trailer.getDictionaryObject(COSName.ROOT));
        if (root == null) {
            throw new IOException("The PDF has no document catalog");
        }
        out.write(String.format(Locale.ROOT, "%%PDF-%.1f\n%%âãÏÓ\n", level.pdfVersion())
                .getBytes(StandardCharsets.ISO_8859_1));
        COSDictionary info = ContentGraph.dict(trailer.getDictionaryObject(COSName.INFO));
        once = References.once(root, info);
        int rootNumber = number(root);
        int infoNumber = info == null ? 0 : number(info);
        int written = 0;
        while (!queue.isEmpty()) {
            COSBase b = queue.poll();
            if ((++written & 4095) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            int n = numbers.get(b);
            if (b instanceof COSStream s) {
                stream(n, s);
            } else if (objectStreams && b != root) {
                pack(n, b);
            } else {
                plain(n, b);
            }
            while (queue.isEmpty() && !lateLengths.isEmpty()) {
                Map.Entry<Integer, COSInteger> e = lateLengths.entrySet().iterator().next();
                lateLengths.remove(e.getKey());
                if (objectStreams) {
                    pack(e.getKey(), e.getValue());
                } else {
                    plain(e.getKey(), e.getValue());
                }
            }
        }
        flushPacked();
        if (next - 1 > MAX_OBJECTS) {
            throw new IOException("The PDF/A would have " + (next - 1) + " objects, more than the " + MAX_OBJECTS
                    + " PDF/A allows");
        }
        COSArray id = doc.getDocument().getDocumentID();
        if (objectStreams) {
            xrefStream(rootNumber, infoNumber, id);
        } else {
            xrefTable(rootNumber, infoNumber, id);
        }
        out.flush();
    }

    private int number(COSBase b) {
        Integer n = numbers.get(b);
        if (n != null) {
            return n;
        }
        int k = next++;
        numbers.put(b, k);
        queue.add(b);
        return k;
    }

    private int reserve() {
        return next++;
    }

    private void record(int n, long offset, int objectStream) {
        if (n >= offsets.length) {
            int size = Math.max(n + 1, offsets.length * 2);
            offsets = Arrays.copyOf(offsets, size);
            container = Arrays.copyOf(container, size);
        }
        offsets[n] = offset;
        container[n] = objectStream;
    }

    private void plain(int n, COSBase b) throws IOException {
        record(n, out.position(), 0);
        ascii(n + " 0 obj\n");
        syntax.object(b, out);
        ascii("\nendobj\n");
    }

    private void pack(int n, COSBase b) throws IOException {
        if (packedCount == OBJECTS_PER_STREAM) {
            flushPacked();
        }
        if (packedCount > 0) {
            packed.write(' ');
            packedIndex.append(' ');
        }
        packedIndex.append(n).append(' ').append(packed.size());
        syntax.object(b, packed);
        packedNumbers[packedCount++] = n;
    }

    private void flushPacked() throws IOException {
        if (packedCount == 0) {
            return;
        }
        int n = reserve();
        byte[] index = packedIndex.append('\n').toString().getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(index.length + packed.size());
        raw.write(index);
        packed.writeTo(raw);
        byte[] data = deflate(raw.toByteArray());
        for (int i = 0; i < packedCount; i++) {
            record(packedNumbers[i], i, n);
        }
        record(n, out.position(), 0);
        ascii(n + " 0 obj\n<</Type/ObjStm/N " + packedCount + "/First " + index.length + "/Filter/FlateDecode/Length "
                + data.length + ">>stream\n");
        out.write(data);
        ascii("\nendstream\nendobj\n");
        packed.reset();
        packedIndex.setLength(0);
        packedCount = 0;
    }

    private void stream(int n, COSStream s) throws IOException {
        Map<COSName, COSBase> replace = new HashMap<>();
        byte[] data = null;
        long declared = s.hasData() ? s.getLength() : 0;
        if (s.hasData() && declared <= BUFFERED_STREAM_BYTES) {
            try (InputStream in = s.createRawInputStream()) {
                data = in.readNBytes(BUFFERED_STREAM_BYTES + 1);
            }
            if (data.length > BUFFERED_STREAM_BYTES) {
                data = null;
            }
        } else if (!s.hasData()) {
            data = new byte[0];
        }
        if (data != null && data.length > 64 && compressible(s)) {
            byte[] packedData = deflate(data);
            if (packedData.length < data.length) {
                data = packedData;
                replace.put(COSName.FILTER, COSName.FLATE_DECODE);
            }
        }
        int lengthObject = 0;
        if (data != null) {
            replace.put(COSName.LENGTH, COSInteger.get(data.length));
        } else {
            lengthObject = reserve();
            COSDictionary placeholder = new COSDictionary();
            numbers.put(placeholder, lengthObject);
            replace.put(COSName.LENGTH, new COSObject(placeholder));
        }
        record(n, out.position(), 0);
        ascii(n + " 0 obj\n");
        syntax.dictionary(s, out, replace);
        ascii("stream\n");
        if (data != null) {
            out.write(data);
        } else {
            long start = out.position();
            try (InputStream in = s.createRawInputStream()) {
                in.transferTo(out);
            }
            lateLengths.put(lengthObject, COSInteger.get(out.position() - start));
        }
        ascii("\nendstream\nendobj\n");
    }

    private static boolean compressible(COSStream s) {
        if (s.getDictionaryObject(COSName.FILTER) != null || s.getDictionaryObject(COSName.DECODE_PARMS) != null) {
            return false;
        }
        return !COSName.METADATA.equals(s.getCOSName(COSName.TYPE));
    }

    static byte[] deflate(byte[] data) throws IOException {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, data.length / 3));
            try (DeflaterOutputStream z = new DeflaterOutputStream(b, d, 1 << 16)) {
                z.write(data);
            }
            return b.toByteArray();
        } finally {
            d.end();
        }
    }

    private void xrefTable(int root, int info, COSArray id) throws IOException {
        long start = out.position();
        int size = next;
        StringBuilder b = new StringBuilder(size * 20 + 64);
        b.append("xref\n0 ").append(size).append('\n').append("0000000000 65535 f\r\n");
        for (int n = 1; n < size; n++) {
            String o = Long.toString(n < offsets.length ? offsets[n] : 0);
            b.append("0".repeat(Math.max(0, 10 - o.length()))).append(o).append(" 00000 n\r\n");
        }
        ascii(b.toString());
        ascii("trailer\n<</Size " + size + "/Root " + root + " 0 R" + (info > 0 ? "/Info " + info + " 0 R" : ""));
        if (id != null) {
            ascii("/ID");
            syntax.object(id, out);
        }
        ascii(">>\nstartxref\n" + start + "\n%%EOF\n");
    }

    private void xrefStream(int root, int info, COSArray id) throws IOException {
        int self = reserve();
        long start = out.position();
        record(self, start, 0);
        int size = next;
        long max = start;
        for (int n = 1; n < size; n++) {
            max = Math.max(max, container[n] != 0 ? container[n] : offsets[n]);
        }
        int w = 1;
        while (w < 8 && max >= 1L << (8 * w)) {
            w++;
        }
        byte[] rows = new byte[size * (1 + w + 2)];
        int p = 0;
        rows[p] = 0;
        rows[p + 1 + w] = (byte) 0xFF;
        rows[p + 2 + w] = (byte) 0xFF;
        p += 3 + w;
        for (int n = 1; n < size; n++) {
            boolean packedObject = container[n] != 0;
            rows[p] = (byte) (packedObject ? 2 : 1);
            long field = packedObject ? container[n] : offsets[n];
            for (int k = 0; k < w; k++) {
                rows[p + 1 + k] = (byte) (field >>> (8 * (w - 1 - k)));
            }
            long third = packedObject ? offsets[n] : 0;
            rows[p + 1 + w] = (byte) (third >>> 8);
            rows[p + 2 + w] = (byte) third;
            p += 3 + w;
        }
        byte[] data = deflate(rows);
        ascii(self + " 0 obj\n<</Type/XRef/Size " + size + "/W[1 " + w + " 2]/Root " + root + " 0 R"
                + (info > 0 ? "/Info " + info + " 0 R" : ""));
        if (id != null) {
            ascii("/ID");
            syntax.object(id, out);
        }
        ascii("/Filter/FlateDecode/Length " + data.length + ">>stream\n");
        out.write(data);
        ascii("\nendstream\nendobj\nstartxref\n" + start + "\n%%EOF\n");
    }

    private void ascii(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static final class CountingOutput extends OutputStream {

        private final OutputStream out;

        private long position;

        CountingOutput(OutputStream out) {
            this.out = out;
        }

        long position() {
            return position;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            position++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            position += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
