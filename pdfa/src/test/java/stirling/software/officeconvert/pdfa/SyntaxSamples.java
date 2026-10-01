package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.raw;

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
                "1:6.1.12-1", "2:6.1.6-1", "2:6.1.6-2", "2:6.1.7.1-1", "2:6.1.7.1-3", "2:6.1.13-1",
                "2:6.1.13-5"), () -> {
                    RawPdf r = RawPdf.page(RawPdf.helvetica() + "/Properties<</P1<</Odd<414>/Bad<41G2>"
                            + "/Big 9999999999/Small 0.0000000000000000000000000000000000000000001>>>>",
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
    }
}
