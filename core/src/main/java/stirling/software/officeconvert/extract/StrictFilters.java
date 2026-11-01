package stirling.software.officeconvert.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;

public final class StrictFilters {

    private static final COSName AHX = COSName.getPDFName("AHx");

    private static final COSName A85 = COSName.getPDFName("A85");

    private static final long MAX_STAGE_BYTES = ImageBudget.MAX_DECODED_BYTES;

    private static final Map<COSStream, Boolean> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private StrictFilters() {}

    public static boolean decodable(COSStream s) {
        Boolean known = KNOWN.get(s);
        if (known == null) {
            known = check(s);
            KNOWN.put(s, known);
        }
        return known;
    }

    private static boolean check(COSStream s) {
        List<COSName> filters = filters(s);
        if (filters == null) {
            return false;
        }
        for (int i = 0; i < filters.size(); i++) {
            COSName f = filters.get(i);
            boolean hex = COSName.ASCII_HEX_DECODE.equals(f) || AHX.equals(f);
            boolean a85 = COSName.ASCII85_DECODE.equals(f) || A85.equals(f);
            if (!hex && !a85) {
                continue;
            }
            byte[] input;
            try {
                input = firstStages(s, filters, i);
            } catch (IOException | RuntimeException e) {
                return false;
            }
            if (hex ? !hexText(input) : !a85Text(input)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hexText(byte[] data) {
        for (byte b : data) {
            int c = b & 0xFF;
            if (c == '>') {
                return true;
            }
            if (!whitespace(c) && Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean a85Text(byte[] data) {
        int start = data.length >= 2 && data[0] == '<' && data[1] == '~' ? 2 : 0;
        for (int i = start; i < data.length; i++) {
            int c = data[i] & 0xFF;
            if (c == '~') {
                return true;
            }
            if (!whitespace(c) && c != 'z' && (c < '!' || c > 'u')) {
                return false;
            }
        }
        return true;
    }

    private static List<COSName> filters(COSStream s) {
        COSBase f = s.getDictionaryObject(COSName.FILTER);
        List<COSName> out = new ArrayList<>();
        if (f instanceof COSName name) {
            out.add(name);
        } else if (f instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof COSName name)) {
                    return null;
                }
                out.add(name);
            }
        } else if (f != null) {
            return null;
        }
        return out;
    }

    private static byte[] firstStages(COSStream s, List<COSName> filters, int stages) throws IOException {
        try (InputStream raw = s.createRawInputStream()) {
            byte[] data = raw.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_STAGE_BYTES + 1));
            if (data.length > MAX_STAGE_BYTES) {
                throw new IOException("stream too large to check");
            }
            for (int i = 0; i < stages; i++) {
                Capped out = new Capped();
                FilterFactory.INSTANCE.getFilter(filters.get(i)).decode(new ByteArrayInputStream(data), out, s, i);
                data = out.toByteArray();
            }
            return data;
        }
    }

    private static boolean whitespace(int c) {
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }

    private static final class Capped extends OutputStream {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (bytes.size() + (long) len > MAX_STAGE_BYTES) {
                throw new IOException("stream too large to check");
            }
            bytes.write(b, off, len);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
