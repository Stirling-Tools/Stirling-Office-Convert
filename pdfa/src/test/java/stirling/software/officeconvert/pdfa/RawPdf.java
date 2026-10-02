package stirling.software.officeconvert.pdfa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class RawPdf {

    final List<String> objects = new ArrayList<>();

    String prefix = "";

    String header = "%PDF-1.4\n%âãÏÓ\n";

    String objectHeader = "%d 0 obj\n";

    String xrefHeader = "xref\n0 %d\n";

    String trailerExtra = "/ID[<0123456789ABCDEF0123456789ABCDEF><0123456789ABCDEF0123456789ABCDEF>]";

    String suffix = "";

    boolean offsetsFromHeader;

    int add(String body) {
        objects.add(body);
        return objects.size();
    }

    void set(int n, String body) {
        objects.set(n - 1, body);
    }

    static String stream(String dict, String data) {
        return "<<" + dict + "/Length " + data.getBytes(StandardCharsets.ISO_8859_1).length + ">>stream\n" + data
                + "\nendstream";
    }

    static RawPdf page(String resources, String content) {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<<" + resources
                + ">>/Contents 4 0 R>>");
        r.add(stream("", content));
        return r;
    }

    static String helvetica() {
        return "/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>>>";
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, prefix + header);
        int base = offsetsFromHeader ? prefix.length() : 0;
        long[] offsets = new long[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size() - base;
            write(out, String.format(objectHeader, i + 1) + objects.get(i) + "\nendobj\n");
        }
        long xref = out.size() - base;
        StringBuilder b = new StringBuilder(String.format(xrefHeader, objects.size() + 1));
        b.append("0000000000 65535 f\r\n");
        for (int i = 1; i <= objects.size(); i++) {
            b.append(String.format("%010d 00000 n\r\n", offsets[i]));
        }
        b.append("trailer\n<</Size ").append(objects.size() + 1).append("/Root 1 0 R").append(trailerExtra)
                .append(">>\nstartxref\n").append(xref).append("\n%%EOF\n").append(suffix);
        write(out, b.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
