package stirling.software.officeconvert.topdf.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

// Rebuilds a package whose zip directory is missing or broken from its local headers, as Office does
final class ZipRepair {

    record Repaired(Path file, int parts, int lost, int cut, boolean guessedTypes, boolean guessedMain) {}

    private record Part(byte[] name, int flags, int method, long crc, long compressed, long size, long offset,
            byte[] inline) {}

    private record Data(long consumed, long size, long crc, boolean complete) {}

    private static final int LOCAL = 0x04034b50;

    private static final int CENTRAL = 0x02014b50;

    private static final int END = 0x06054b50;

    private static final int DESCRIPTOR = 0x08074b50;

    private static final long MAX_ZIP32 = 0xFFFFFFFFL;

    private final FileChannel in;

    private final long end;

    private final OfficeZip.Limits limits;

    private final List<Part> parts = new ArrayList<>();

    private final Set<String> seen = new HashSet<>();

    private long total;

    private long compressed;

    private int lost;

    private int cut;

    private ZipRepair(FileChannel in, OfficeZip.Limits limits) throws IOException {
        this.in = in;
        this.end = in.size();
        this.limits = limits;
    }

    // Null when no part could be recovered
    static Repaired rebuild(Path file, OfficeZip.Limits limits) throws IOException {
        return rebuild(file, limits, Set.of());
    }

    // The dropped parts (lower case, no leading slash) are made again as for a package that lost them
    static Repaired rebuild(Path file, OfficeZip.Limits limits, Set<String> drop) throws IOException {
        ZipRepair scan;
        try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ)) {
            scan = new ZipRepair(in, limits);
            scan.scan();
            scan.parts.removeIf(p -> drop.contains(key(new String(p.name(), StandardCharsets.UTF_8))));
            scan.seen.removeAll(drop);
            if (scan.parts.isEmpty()) {
                return null;
            }
            int recovered = scan.parts.size();
            boolean guessed = !scan.seen.contains("[content_types].xml");
            if (guessed) {
                scan.addContentTypes();
            }
            boolean main = !scan.seen.contains("_rels/.rels") && scan.addPackageRelationships();
            Path out = Files.createTempFile("office-repair-", ".zip");
            try {
                scan.write(out);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(out);
                throw e;
            }
            return new Repaired(out, recovered, scan.lost, scan.cut, guessed, main);
        }
    }

    private void scan() throws IOException {
        long pos = 0;
        while (pos + 30 <= end) {
            checkNotInterrupted();
            ByteBuffer h = read(pos, 30);
            if (h.getInt(0) != LOCAL) {
                return;
            }
            int flags = h.getShort(6) & 0xFFFF;
            int method = h.getShort(8) & 0xFFFF;
            long csize = h.getInt(18) & MAX_ZIP32;
            long size = h.getInt(22) & MAX_ZIP32;
            int nameLength = h.getShort(26) & 0xFFFF;
            int extraLength = h.getShort(28) & 0xFFFF;
            long data = pos + 30 + nameLength + extraLength;
            if (data > end) {
                lost++;
                return;
            }
            byte[] name = bytes(pos + 30, nameLength);
            boolean zip64 = csize == MAX_ZIP32 || size == MAX_ZIP32;
            if (zip64) {
                long[] sizes = zip64Sizes(bytes(pos + 30 + nameLength, extraLength), size == MAX_ZIP32,
                        csize == MAX_ZIP32);
                if (sizes == null) {
                    lost++;
                    return;
                }
                size = size == MAX_ZIP32 ? sizes[0] : size;
                csize = csize == MAX_ZIP32 ? sizes[1] : csize;
            }
            boolean sized = (flags & 8) == 0;
            if ((flags & 1) != 0 || (method != 0 && method != 8) || (method == 0 && !sized)) {
                lost++;
                if (!sized) {
                    return;
                }
                pos = data + csize;
                continue;
            }
            long available = sized ? Math.min(csize, end - data) : end - data;
            Data d = method == 8 ? inflate(data, available) : stored(data, csize);
            if (d == null || !d.complete()) {
                shortened(name, flags, method, data, available);
                if (!sized || data + csize > end) {
                    return;
                }
                pos = data + csize;
                continue;
            }
            total += d.size();
            compressed += d.consumed();
            keep(name, flags, method, d, data);
            pos = sized ? data + csize : skipDescriptor(data + d.consumed(), zip64 || d.size() > MAX_ZIP32);
        }
    }

    // An XML part cut short keeps its well-formed start; any other part is lost
    private void shortened(byte[] name, int flags, int method, long data, long available) throws IOException {
        String n = new String(name, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        byte[] kept = n.endsWith(".xml") || n.endsWith(".rels") ? XmlSalvage.salvage(partial(method, data, available))
                : null;
        if (kept == null) {
            lost++;
            return;
        }
        CRC32 crc = new CRC32();
        crc.update(kept);
        int before = parts.size();
        keep(name, flags, 0, new Data(kept.length, kept.length, crc.getValue(), true), -1, kept);
        if (parts.size() > before) {
            cut++;
        }
    }

    private byte[] partial(int method, long data, long available) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer input = ByteBuffer.allocate(1 << 16);
        byte[] buffer = new byte[1 << 16];
        Inflater inf = method == 8 ? new Inflater(true) : null;
        try {
            for (long fed = 0; fed < available && out.size() <= XmlSalvage.MAX_BYTES; ) {
                checkNotInterrupted();
                input.clear().limit((int) Math.min(input.capacity(), available - fed));
                int n = in.read(input, data + fed);
                if (n <= 0) {
                    break;
                }
                fed += n;
                if (inf == null) {
                    out.write(input.array(), 0, n);
                    continue;
                }
                inf.setInput(input.array(), 0, n);
                while (!inf.needsInput() && !inf.finished() && out.size() <= XmlSalvage.MAX_BYTES) {
                    int got = inf.inflate(buffer);
                    if (got == 0 && (inf.needsDictionary() || inf.finished())) {
                        break;
                    }
                    out.write(buffer, 0, got);
                }
                if (inf.finished() || inf.needsDictionary()) {
                    break;
                }
            }
        } catch (DataFormatException e) {
            // keep what inflated before the damage
        } finally {
            if (inf != null) {
                inf.end();
            }
        }
        return out.size() > XmlSalvage.MAX_BYTES ? new byte[0] : out.toByteArray();
    }

    private static String key(String name) {
        return name.replace('\\', '/').replaceFirst("^/+", "").toLowerCase(Locale.ROOT);
    }

    private void keep(byte[] name, int flags, int method, Data d, long offset) throws IOException {
        keep(name, flags, method, d, offset, null);
    }

    private void keep(byte[] name, int flags, int method, Data d, long offset, byte[] inline) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
        } catch (CharacterCodingException e) {
            lost++;
            return;
        }
        String key = key(text);
        if (key.isEmpty() || key.endsWith("/")) {
            return;
        }
        if (!seen.add(key)) {
            lost++;
            return;
        }
        if (parts.size() >= limits.maxEntries() || parts.size() >= 0xFFFF) {
            throw new IOException("The document is too large: it has more than " + limits.maxEntries() + " parts");
        }
        parts.add(new Part(name, flags & 0x0800, method, d.crc(), d.consumed(), d.size(), offset, inline));
    }

    private Data inflate(long data, long available) throws IOException {
        Inflater inf = new Inflater(true);
        CRC32 crc = new CRC32();
        ByteBuffer input = ByteBuffer.allocate(1 << 16);
        byte[] output = new byte[1 << 16];
        long fed = 0;
        try {
            while (!inf.finished()) {
                checkNotInterrupted();
                if (inf.needsInput()) {
                    if (fed >= available) {
                        return new Data(fed, inf.getBytesWritten(), 0, false);
                    }
                    input.clear().limit((int) Math.min(input.capacity(), available - fed));
                    int n = in.read(input, data + fed);
                    if (n <= 0) {
                        return new Data(fed, inf.getBytesWritten(), 0, false);
                    }
                    fed += n;
                    inf.setInput(input.array(), 0, n);
                }
                int n = inf.inflate(output);
                if (n == 0 && inf.needsDictionary()) {
                    return null;
                }
                crc.update(output, 0, n);
                charge(data, inf.getBytesWritten());
            }
            return new Data(inf.getBytesRead(), inf.getBytesWritten(), crc.getValue(), true);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    private Data stored(long data, long size) throws IOException {
        if (data + size > end) {
            return new Data(end - data, end - data, 0, false);
        }
        charge(data, size);
        CRC32 crc = new CRC32();
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        for (long done = 0; done < size; ) {
            checkNotInterrupted();
            buffer.clear().limit((int) Math.min(buffer.capacity(), size - done));
            int n = in.read(buffer, data + done);
            if (n <= 0) {
                return new Data(done, done, 0, false);
            }
            crc.update(buffer.array(), 0, n);
            done += n;
        }
        return new Data(size, size, crc.getValue(), true);
    }

    // The same limits as an intact package; a bomb is refused as soon as the rest of the file cannot make up for it
    private void charge(long data, long size) throws IOException {
        if (size > limits.maxEntryBytes()) {
            throw new IOException("The document is too large: a part is over " + (limits.maxEntryBytes() >> 20)
                    + " MB uncompressed");
        }
        long inflated = total + size;
        if (inflated > limits.maxTotalBytes()) {
            throw new IOException("The document is too large: over " + (limits.maxTotalBytes() >> 20)
                    + " MB uncompressed");
        }
        long best = compressed + (end - data);
        if (inflated > Math.max(limits.graceBytes(), limits.maxDenseBytes())
                && (double) best / inflated < limits.minInflateRatio()) {
            throw new IOException("The document looks like a zip bomb: it inflates more than "
                    + (inflated / Math.max(1, best)) + " times");
        }
    }

    private static final String OOXML = "application/vnd.openxmlformats-officedocument.";

    // The usual part names of Office packages and their content types, for a package that lost [Content_Types].xml
    private static final String[][] KNOWN = {
        {"word/document.xml", "wordprocessingml.document.main+xml"}, {"word/styles.xml", "wordprocessingml.styles+xml"},
        {"word/settings.xml", "wordprocessingml.settings+xml"}, {"word/numbering.xml", "wordprocessingml.numbering+xml"},
        {"word/fonttable.xml", "wordprocessingml.fontTable+xml"}, {"word/websettings.xml", "wordprocessingml.webSettings+xml"},
        {"word/footnotes.xml", "wordprocessingml.footnotes+xml"}, {"word/endnotes.xml", "wordprocessingml.endnotes+xml"},
        {"word/comments.xml", "wordprocessingml.comments+xml"}, {"word/header", "wordprocessingml.header+xml"},
        {"word/footer", "wordprocessingml.footer+xml"}, {"ppt/presentation.xml", "presentationml.presentation.main+xml"},
        {"ppt/slides/", "presentationml.slide+xml"}, {"ppt/slidelayouts/", "presentationml.slideLayout+xml"},
        {"ppt/slidemasters/", "presentationml.slideMaster+xml"}, {"ppt/notesslides/", "presentationml.notesSlide+xml"},
        {"ppt/notesmasters/", "presentationml.notesMaster+xml"}, {"ppt/handoutmasters/", "presentationml.handoutMaster+xml"},
        {"ppt/presprops.xml", "presentationml.presProps+xml"}, {"ppt/viewprops.xml", "presentationml.viewProps+xml"},
        {"ppt/tablestyles.xml", "presentationml.tableStyles+xml"}, {"xl/workbook.xml", "spreadsheetml.sheet.main+xml"},
        {"xl/worksheets/", "spreadsheetml.worksheet+xml"}, {"xl/chartsheets/", "spreadsheetml.chartsheet+xml"},
        {"xl/sharedstrings.xml", "spreadsheetml.sharedStrings+xml"}, {"xl/styles.xml", "spreadsheetml.styles+xml"},
        {"xl/comments", "spreadsheetml.comments+xml"}, {"xl/tables/", "spreadsheetml.table+xml"},
        {"/theme/", "theme+xml"}, {"/drawings/", "drawing+xml"}, {"/charts/chart", "drawingml.chart+xml"},
        {"docprops/core.xml", "!application/vnd.openxmlformats-package.core-properties+xml"},
        {"docprops/app.xml", "extended-properties+xml"},
    };

    private static final String[][] MEDIA = {{"rels", "application/vnd.openxmlformats-package.relationships+xml"},
        {"xml", "application/xml"}, {"png", "image/png"}, {"jpeg", "image/jpeg"}, {"jpg", "image/jpeg"},
        {"gif", "image/gif"}, {"bmp", "image/bmp"}, {"tif", "image/tiff"}, {"tiff", "image/tiff"}, {"emf", "image/x-emf"},
        {"wmf", "image/x-wmf"}, {"vml", OOXML + "vmlDrawing"}, {"bin", "application/octet-stream"}};

    // A package that lost /_rels/.rels still names its main part by the usual name
    private boolean addPackageRelationships() {
        for (String main : new String[] {"word/document.xml", "ppt/presentation.xml", "xl/workbook.xml"}) {
            if (seen.contains(main)) {
                addInline("_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                        + "relationships/officeDocument\" Target=\"" + main + "\"/></Relationships>");
                return true;
            }
        }
        return false;
    }

    private void addInline(String name, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        parts.add(new Part(name.getBytes(StandardCharsets.UTF_8), 0x0800, 0, crc.getValue(), data.length, data.length,
                -1, data));
    }

    private void addContentTypes() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        for (String[] m : MEDIA) {
            xml.append("<Default Extension=\"").append(m[0]).append("\" ContentType=\"").append(m[1]).append("\"/>");
        }
        for (Part p : parts) {
            String name = new String(p.name(), StandardCharsets.UTF_8).replace('\\', '/').replaceFirst("^/+", "");
            String key = name.toLowerCase(Locale.ROOT);
            if (!key.endsWith(".xml") || key.chars().anyMatch(c -> c < 0x20 || c == '"' || c == '<' || c == '&')) {
                continue;
            }
            for (String[] k : KNOWN) {
                if (k[0].startsWith("/") ? key.contains(k[0]) : key.startsWith(k[0])) {
                    String type = k[1].startsWith("!") ? k[1].substring(1) : OOXML + k[1];
                    xml.append("<Override PartName=\"/").append(name).append("\" ContentType=\"").append(type)
                            .append("\"/>");
                    break;
                }
            }
        }
        addInline("[Content_Types].xml", xml.append("</Types>").toString());
    }

    private long skipDescriptor(long at, boolean wide) throws IOException {
        long start = at + 4 <= end && read(at, 4).getInt(0) == DESCRIPTOR ? at + 4 : at;
        long[] widths = wide ? new long[] {20, 12} : new long[] {12, 20};
        for (long w : widths) {
            long next = start + w;
            if (next >= end - 3 || signature(next)) {
                return Math.min(next, end);
            }
        }
        return start + widths[0];
    }

    private boolean signature(long at) throws IOException {
        int sig = read(at, 4).getInt(0);
        return sig == LOCAL || sig == CENTRAL || sig == END;
    }

    private void write(Path file) throws IOException {
        List<Long> offsets = new ArrayList<>(parts.size());
        try (FileChannel out = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long pos = 0;
            for (Part p : parts) {
                checkNotInterrupted();
                offsets.add(pos);
                ByteBuffer h = header(30 + p.name().length);
                h.putInt(LOCAL).putShort((short) 20).putShort((short) p.flags()).putShort((short) p.method())
                        .putShort((short) 0).putShort((short) 0x21).putInt((int) p.crc()).putInt((int) p.compressed())
                        .putInt((int) p.size()).putShort((short) p.name().length).putShort((short) 0).put(p.name());
                pos += writeAll(out, h.flip());
                if (p.inline() != null) {
                    writeAll(out, ByteBuffer.wrap(p.inline()));
                }
                for (long done = p.inline() == null ? 0 : p.compressed(); done < p.compressed(); ) {
                    long n = in.transferTo(p.offset() + done, p.compressed() - done, out);
                    if (n <= 0) {
                        throw new IOException("The damaged document could not be repaired");
                    }
                    done += n;
                }
                pos += p.compressed();
                if (pos > MAX_ZIP32 || p.size() > MAX_ZIP32) {
                    throw new IOException("The damaged document is too large to repair");
                }
            }
            long directory = pos;
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                ByteBuffer c = header(46 + p.name().length);
                c.putInt(CENTRAL).putShort((short) 20).putShort((short) 20).putShort((short) p.flags())
                        .putShort((short) p.method()).putShort((short) 0).putShort((short) 0x21).putInt((int) p.crc())
                        .putInt((int) p.compressed()).putInt((int) p.size()).putShort((short) p.name().length)
                        .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0)
                        .putInt(offsets.get(i).intValue()).put(p.name());
                pos += writeAll(out, c.flip());
            }
            if (pos > MAX_ZIP32) {
                throw new IOException("The damaged document is too large to repair");
            }
            ByteBuffer e = header(22);
            e.putInt(END).putShort((short) 0).putShort((short) 0).putShort((short) parts.size())
                    .putShort((short) parts.size()).putInt((int) (pos - directory)).putInt((int) directory)
                    .putShort((short) 0);
            writeAll(out, e.flip());
        }
    }

    private static ByteBuffer header(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static long writeAll(FileChannel out, ByteBuffer b) throws IOException {
        long n = b.remaining();
        while (b.hasRemaining()) {
            out.write(b);
        }
        return n;
    }

    private static long[] zip64Sizes(byte[] extra, boolean size, boolean csize) {
        ByteBuffer b = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN);
        while (b.remaining() >= 4) {
            int id = b.getShort() & 0xFFFF;
            int length = b.getShort() & 0xFFFF;
            if (length > b.remaining()) {
                return null;
            }
            if (id == 1) {
                long[] out = new long[2];
                if (size) {
                    if (length < 8) {
                        return null;
                    }
                    out[0] = b.getLong();
                    length -= 8;
                }
                if (csize) {
                    if (length < 8) {
                        return null;
                    }
                    out[1] = b.getLong();
                }
                return out[0] < 0 || out[1] < 0 ? null : out;
            }
            b.position(b.position() + length);
        }
        return null;
    }

    private ByteBuffer read(long at, int length) throws IOException {
        ByteBuffer b = header(length);
        while (b.hasRemaining()) {
            if (in.read(b, at + b.position()) < 0) {
                throw new IOException("The damaged document ends too early");
            }
        }
        return b.flip();
    }

    private byte[] bytes(long at, int length) throws IOException {
        return read(at, length).array();
    }

    private static void checkNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }
}
