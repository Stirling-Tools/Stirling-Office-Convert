package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.raw;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class SyntaxSamples {

    private static final String TEXT = "BT /F1 18 Tf 72 760 Td (Syntax sample) Tj ET";

    private SyntaxSamples() {}

    static void register() {
        raw("r01_file_structure", Set.of("1:6.1.2-2", "1:6.1.3-1", "1:6.1.3-3", "1:6.1.4-1", "1:6.1.4-2",
                "2:6.1.2-2", "2:6.1.3-1", "2:6.1.3-3", "2:6.1.4-2"), () -> {
                    RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
                    r.header = "%PDF-1.4\n";
                    r.trailerExtra = "";
                    r.suffix = "data after the end of the file\n";
                    r.xrefHeader = "xref\n\n0  %d\n";
                    return r.bytes();
                });
        raw("r01_object_header", Set.of("1:6.1.8-1", "2:6.1.9-1"), () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.objectHeader = "%d  0  obj\n";
            return r.bytes();
        });
        raw("r01_header_offset", Set.of("1:6.1.2-1", "2:6.1.2-1"), () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.prefix = "garbage before the header\n";
            r.offsetsFromHeader = true;
            return r.bytes();
        });
        raw("r02_streams_and_strings", Set.of("1:6.1.6-1", "1:6.1.6-2", "1:6.1.7-1", "1:6.1.7-3",
                "1:6.1.12-1", "2:6.1.6-1", "2:6.1.6-2", "2:6.1.7.1-1", "2:6.1.7.1-3", "2:6.1.13-1", "2:6.1.13-2",
                "2:6.1.13-5"), () -> {
                    RawPdf r = RawPdf.page(RawPdf.helvetica() + "/Properties<</P1<</Odd<414>/Bad<41G2>"
                            + "/Big 9999999999/Small 0.0000000000000000000000000000000000000000001/Huge "
                            + "9".repeat(40) + ".0>>>>",
                            "/OC /P1 BDC " + TEXT + " EMC");
                    r.set(4, "<</Length 9999/F(external.dat)/FFilter/FlateDecode/FDecodeParms<<>>>>stream\n"
                            + "/OC /P1 BDC " + TEXT + " EMC\nendstream");
                    return r.bytes();
                });
        raw("r03_stream_keyword", Set.of("1:6.1.7-2", "2:6.1.7.1-2"), () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.set(4, "<</Length " + TEXT.length() + ">>stream\r" + TEXT + "\nendstream");
            return r.bytes();
        });
        raw("r04_inline_images_and_operators", Set.of("1:6.1.10-2", "1:6.2.10-1", "2:6.1.10-1", "2:6.2.2-1"),
                () -> {
                    String lzw = new String(lzwGray(), StandardCharsets.ISO_8859_1);
                    String content = TEXT + " q 100 0 0 100 72 500 cm BI /W 4 /H 4 /CS /G /BPC 8 /F /LZW ID "
                            + lzw + " EI Q 1 2 3 bogusop";
                    return RawPdf.page(RawPdf.helvetica(), content).bytes();
                });
        raw("r05_page_tree", Set.of(), () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica(), TEXT);
            r.set(2, "<</Kids[3 0 R]/Count 7>>");
            r.set(3, "<</Parent 1 0 R/MediaBox[0 0 595 842]/Resources<<" + RawPdf.helvetica() + ">>/Contents 4 0 R>>");
            return r.bytes();
        });
        raw("r06_unknown_filter", Set.of("2:6.1.7.2-1"), () -> {
            RawPdf r = RawPdf.page(RawPdf.helvetica() + "/XObject<</X1 5 0 R>>", TEXT + " q 100 0 0 100 72 500 cm /X1 Do Q");
            r.add("<</Type/XObject/Subtype/Image/Width 1/Height 1/ColorSpace/DeviceGray/BitsPerComponent 8"
                    + "/Filter/BogusDecode/Length 3>>stream\nabc\nendstream");
            return r.bytes();
        });
        raw("r07_inline_image_without_data", Set.of(), () -> RawPdf.page(RawPdf.helvetica(),
                TEXT + " 1 bogusop q 10 0 0 10 72 500 cm Q BI /W 1 /H 1 /CS /G /BPC 8").bytes());
        raw("r08_junk_at_the_end", Set.of(), () -> RawPdf.page(RawPdf.helvetica(),
                TEXT + "\n\u0091\u008e\u0084<").bytes());
        raw("r09_unclosed_graphics_states", Set.of("1:6.1.12-8", "2:6.1.13-8"), () -> RawPdf.page(
                RawPdf.helvetica(), TEXT + " " + "q 1 0 0 1 1 1 cm ".repeat(40) + "0 0 10 10 re f").bytes());
    }

    private static byte[] lzwGray() {
        int[] codes = {256, 0, 50, 100, 150, 200, 250, 258, 260, 262, 264, 266, 268, 257};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int c : codes) {
            buffer = buffer << 9 | c;
            bits += 9;
            while (bits >= 8) {
                out.write(buffer >>> (bits - 8) & 0xFF);
                bits -= 8;
            }
        }
        if (bits > 0) {
            out.write(buffer << (8 - bits) & 0xFF);
        }
        return out.toByteArray();
    }
}
