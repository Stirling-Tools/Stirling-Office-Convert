package stirling.software.officeconvert.lineart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;

import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.memory.Admission;

final class InlineImages {

    private record Spot(int start, int end, COSDictionary parameters, byte[] data) {}

    private static final byte[] BI = {'B', 'I'};

    static final int MAX_CONTENT_BYTES = 1 << 25;

    private InlineImages() {}

    static void convert(ImageWalk.Content content, LineArt.Options options, Map<LineArt.Codec, Integer> codecs,
            long[] totals, List<String> warnings) throws IOException {
        COSStream stream = content.stream();
        byte[] bytes;
        try {
            bytes = decoded(stream);
        } catch (TooLarge e) {
            warnings.add("Page " + content.page() + ": a content stream is larger than " + (MAX_CONTENT_BYTES >> 20)
                    + " MB, so it was not searched for inline images");
            return;
        }
        if (indexOf(bytes, BI, 0) < 0) {
            return;
        }
        List<Spot> spots = locate(bytes);
        if (spots == null || spots.isEmpty()) {
            return;
        }
        List<Spot> replaced = new ArrayList<>();
        List<byte[]> replacements = new ArrayList<>();
        for (Spot spot : spots) {
            PdfFiles.stopIfInterrupted();
            totals[0]++;
            byte[] replacement;
            try {
                replacement = replacement(spot, content, options, codecs, totals, warnings);
            } catch (IOException | RuntimeException e) {
                LineArt.rethrowCancelled(e);
                warnings.add("Page " + content.page() + ": an inline image could not be read and was left as it was ("
                        + e.getMessage() + ")");
                replacement = null;
            }
            if (replacement != null) {
                replaced.add(spot);
                replacements.add(replacement);
            }
        }
        if (replaced.isEmpty()) {
            return;
        }
        stream.removeItem(COSName.DECODE_PARMS);
        stream.removeItem(COSName.getPDFName("DL"));
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            int at = 0;
            for (int i = 0; i < replaced.size(); i++) {
                Spot spot = replaced.get(i);
                os.write(bytes, at, spot.start() - at);
                os.write(replacements.get(i));
                at = spot.end();
            }
            os.write(bytes, at, bytes.length - at);
        }
    }

    private static byte[] decoded(COSStream stream) throws IOException {
        List<COSName> filters = Source.filters(stream);
        if (filters == null) {
            throw new IOException("The content stream has a malformed filter list");
        }
        Capped out = new Capped(MAX_CONTENT_BYTES);
        if (filters.isEmpty()) {
            try (InputStream in = stream.createInputStream()) {
                in.transferTo(out);
            }
            return out.toByteArray();
        }
        InputStream in = stream.createRawInputStream();
        try {
            for (int i = 0; i < filters.size(); i++) {
                PdfFiles.stopIfInterrupted();
                out = new Capped(MAX_CONTENT_BYTES);
                FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(in, out, stream, i);
                in.close();
                in = new ByteArrayInputStream(out.toByteArray());
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static byte[] replacement(Spot spot, ImageWalk.Content content, LineArt.Options options,
            Map<LineArt.Codec, Integer> codecs, long[] totals, List<String> warnings) throws IOException {
        COSDictionary given = spot.parameters();
        int w = given.getInt(COSName.W, COSName.WIDTH, -1);
        int h = given.getInt(COSName.H, COSName.HEIGHT, -1);
        if (given.getBoolean(COSName.IM, COSName.IMAGE_MASK, false) || w <= 0 || h <= 0) {
            return null;
        }
        long pixels = (long) w * h;
        if (pixels > options.maxPixels()) {
            warnings.add("Page " + content.page() + ": a " + w + "x" + h + " inline image is larger than "
                    + options.maxPixels() + " pixels and was left as it was");
            return null;
        }
        long budget = LineArt.bandBudget();
        long cost = Source.bandBytes(w, h, Binarizer.context(options, w, h), h);
        if (cost > budget) {
            warnings.add("Page " + content.page() + ": a " + w + "x" + h
                    + " inline image is too large to convert in the memory available and was left as it was");
            return null;
        }
        Admission.Ticket ticket = Admission.jvm().enter(pixels / 8 * 4 + cost);
        try {
            return replacement(spot, decoded(spot, content, Math.min(budget, pixels * 8 + (1 << 16))), options,
                    codecs, totals);
        } finally {
            ticket.close();
        }
    }

    private static PDInlineImage decoded(Spot spot, ImageWalk.Content content, long limit) throws IOException {
        COSDictionary given = spot.parameters();
        COSBase f = given.getDictionaryObject(COSName.F, COSName.FILTER);
        List<COSName> filters = new ArrayList<>();
        if (f instanceof COSName name) {
            filters.add(name);
        } else if (f instanceof COSArray array) {
            for (COSBase b : array) {
                if (!(b instanceof COSName name)) {
                    throw new IOException("The inline image has a malformed filter list");
                }
                filters.add(name);
            }
        }
        COSDictionary parameters = new COSDictionary(given);
        byte[] data = spot.data();
        DecodeResult result = null;
        for (int i = 0; i < filters.size(); i++) {
            Capped out = new Capped(limit);
            result = FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(new ByteArrayInputStream(data), out,
                    given, i);
            data = out.toByteArray();
        }
        if (result != null) {
            parameters.addAll(result.getParameters());
        }
        for (COSName key : new COSName[] {COSName.F, COSName.FILTER, COSName.DP, COSName.DECODE_PARMS}) {
            parameters.removeItem(key);
        }
        return new PDInlineImage(parameters, data, content.resources());
    }

    private static byte[] replacement(Spot spot, PDInlineImage image, LineArt.Options options,
            Map<LineArt.Codec, Integer> codecs, long[] totals) throws IOException {
        boolean alreadyBilevel = image.getBitsPerComponent() == 1 && image.getColorSpace().getNumberOfComponents() == 1
                && options.method() != LineArt.Method.EDGES;
        Bitmap bitmap = Binarizer.binarize(image.getImage(), options);
        Codecs.Encoded encoded = switch (options.codec()) {
            case FLATE -> Codecs.flate(bitmap);
            case JBIG2, CCITT_G4 -> Codecs.g4(bitmap);
            case SMALLEST -> {
                Codecs.Encoded g4 = Codecs.g4(bitmap);
                Codecs.Encoded flate = Codecs.flate(bitmap);
                yield flate.data().length < g4.data().length ? flate : g4;
            }
        };
        if (alreadyBilevel && encoded.data().length >= spot.data().length) {
            return null;
        }
        COSDictionary parameters = new COSDictionary();
        parameters.setInt(COSName.W, bitmap.width());
        parameters.setInt(COSName.H, bitmap.height());
        parameters.setInt(COSName.BPC, 1);
        COSBase colour = LineArt.colour(bitmap, null, options.inkColour());
        parameters.setItem(COSName.CS, colour);
        parameters.setItem(COSName.F, encoded.filter());
        if (encoded.parameters() != null && encoded.parameters().size() > 0) {
            parameters.setItem(COSName.DP, encoded.parameters());
        }
        if (image.getInterpolate()) {
            parameters.setBoolean(COSName.I, true);
        }
        COSBase intent = spot.parameters().getDictionaryObject(COSName.INTENT);
        if (intent instanceof COSName) {
            parameters.setItem(COSName.INTENT, intent);
        }
        StringBuilder head = new StringBuilder("BI");
        for (Map.Entry<COSName, COSBase> e : parameters.entrySet()) {
            head.append(' ');
            write(head, e.getKey());
            head.append(' ');
            write(head, e.getValue());
        }
        head.append(" ID ");
        ByteArrayOutputStream b = new ByteArrayOutputStream(encoded.data().length + head.length() + 4);
        b.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        b.writeBytes(encoded.data());
        b.writeBytes("\nEI".getBytes(StandardCharsets.ISO_8859_1));
        totals[1]++;
        totals[2] += spot.data().length;
        totals[3] += encoded.data().length;
        codecs.merge(encoded.codec(), 1, Integer::sum);
        return b.toByteArray();
    }

    private static void write(StringBuilder s, COSBase value) {
        switch (value) {
            case COSName name -> {
                s.append('/');
                for (byte c : name.getName().getBytes(StandardCharsets.UTF_8)) {
                    int v = c & 0xFF;
                    if (v > 32 && v < 127 && "()<>[]{}/%#".indexOf(v) < 0) {
                        s.append((char) v);
                    } else {
                        s.append('#').append(String.format(Locale.ROOT, "%02X", v));
                    }
                }
            }
            case COSBoolean bool -> s.append(bool.getValue());
            case COSNumber number -> s.append(number.intValue() == number.floatValue() ? Integer.toString(number.intValue())
                    : Float.toString(number.floatValue()));
            case COSString string -> s.append('<').append(HexFormat.of().formatHex(string.getBytes())).append('>');
            case COSArray array -> {
                s.append('[');
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) {
                        s.append(' ');
                    }
                    write(s, array.get(i));
                }
                s.append(']');
            }
            case COSDictionary dictionary -> {
                s.append("<<");
                for (Map.Entry<COSName, COSBase> e : dictionary.entrySet()) {
                    write(s, e.getKey());
                    s.append(' ');
                    write(s, e.getValue());
                    s.append(' ');
                }
                s.append(">>");
            }
            default -> throw new IllegalArgumentException("Cannot write " + value);
        }
    }

    static List<Spot> locate(byte[] c) throws IOException {
        List<Operator> images = new ArrayList<>();
        PDFStreamParser parser = new PDFStreamParser(c);
        for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
            if (token instanceof Operator op && "BI".equals(op.getName())) {
                images.add(op);
            }
        }
        List<Spot> spots = new ArrayList<>();
        int i = 0;
        int n = c.length;
        int start = -1;
        while (i < n) {
            int b = c[i] & 0xFF;
            if (whitespace(b)) {
                i++;
            } else if (b == '/') {
                i++;
                while (i < n && !whitespace(c[i] & 0xFF) && !delimiter(c[i] & 0xFF)) {
                    i++;
                }
            } else if (b == '%') {
                while (i < n && c[i] != '\n' && c[i] != '\r') {
                    i++;
                }
            } else if (b == '(') {
                i = string(c, i);
            } else if (b == '<' && i + 1 < n && c[i + 1] != '<') {
                while (i < n && c[i] != '>') {
                    i++;
                }
                i++;
            } else if (delimiter(b)) {
                i++;
            } else {
                int j = i + 1;
                while (j < n && !whitespace(c[j] & 0xFF) && !delimiter(c[j] & 0xFF)) {
                    j++;
                }
                if (j - i == 2 && b == 'B' && c[i + 1] == 'I') {
                    start = i;
                } else if (j - i == 2 && b == 'I' && c[i + 1] == 'D' && start >= 0) {
                    if (spots.size() >= images.size()) {
                        return null;
                    }
                    Operator op = images.get(spots.size());
                    byte[] data = op.getImageData() == null ? new byte[0] : op.getImageData();
                    int dataStart = -1;
                    for (int skip = 1; skip <= 2 && dataStart < 0; skip++) {
                        int d = j + skip;
                        if (d + data.length <= n && Arrays.equals(c, d, d + data.length, data, 0, data.length)) {
                            dataStart = d;
                        }
                    }
                    if (dataStart < 0) {
                        return null;
                    }
                    int k = dataStart + data.length;
                    while (k < n && whitespace(c[k] & 0xFF)) {
                        k++;
                    }
                    if (k + 1 >= n || c[k] != 'E' || c[k + 1] != 'I'
                            || k + 2 < n && !whitespace(c[k + 2] & 0xFF) && !delimiter(c[k + 2] & 0xFF)) {
                        return null;
                    }
                    spots.add(new Spot(start, k + 2, op.getImageParameters(), data));
                    start = -1;
                    i = k + 2;
                    continue;
                }
                i = j;
            }
        }
        return spots.size() == images.size() ? spots : null;
    }

    private static int string(byte[] c, int i) {
        int depth = 0;
        int n = c.length;
        while (i < n) {
            byte b = c[i];
            if (b == '\\') {
                i += 2;
                continue;
            }
            if (b == '(') {
                depth++;
            } else if (b == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return n;
    }

    private static boolean whitespace(int b) {
        return b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32;
    }

    private static boolean delimiter(int b) {
        return b == '(' || b == ')' || b == '<' || b == '>' || b == '[' || b == ']' || b == '{' || b == '}'
                || b == '/' || b == '%';
    }

    private static final class TooLarge extends IOException {
        TooLarge(long limit) {
            super("The data decodes to more than " + limit + " bytes");
        }
    }

    private static final class Capped extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit;

        Capped(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            bytes.write(b, off, len);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void check(int more) throws IOException {
            if (bytes.size() + (long) more > limit) {
                throw new TooLarge(limit);
            }
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
