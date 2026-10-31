package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;

final class StrictFilters {

    private static final COSName AHX = COSName.getPDFName("AHx");

    private static final COSName A85 = COSName.getPDFName("A85");

    private StrictFilters() {}

    static boolean decodable(COSStream s) {
        List<COSName> filters;
        try {
            filters = Decoded.filterNames(s);
        } catch (IOException e) {
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
                input = Decoded.firstStages(s, i, Decoded.MAX_STREAM_BYTES, "A stream");
            } catch (IOException | RuntimeException e) {
                return false;
            }
            if (hex ? !hexText(input) : !a85Text(input)) {
                return false;
            }
        }
        return true;
    }

    static boolean hexText(byte[] data) {
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

    static boolean a85Text(byte[] data) {
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

    private static boolean whitespace(int c) {
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
    }
}
