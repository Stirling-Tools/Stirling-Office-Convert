package stirling.software.officeconvert.optimize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RawPdf {

    final List<byte[]> objects = new ArrayList<>();

    String header = "%PDF-1.4\n%âãÏÓ\n";

    String trailerExtra = "/ID[<0123456789ABCDEF0123456789ABCDEF><0123456789ABCDEF0123456789ABCDEF>]";

    int add(String body) {
        return add(body.getBytes(StandardCharsets.ISO_8859_1));
    }

    int add(byte[] body) {
        objects.add(body);
        return objects.size();
    }

    void set(int n, String body) {
        objects.set(n - 1, body.getBytes(StandardCharsets.ISO_8859_1));
    }

    static String stream(String dict, String data) {
        return "<<" + dict + "/Length " + data.getBytes(StandardCharsets.ISO_8859_1).length + ">>stream\n" + data
                + "\nendstream";
    }

    static byte[] stream(String dict, byte[] data) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(("<<" + dict + "/Length " + data.length + ">>stream\n").getBytes(StandardCharsets.ISO_8859_1));
        b.writeBytes(data);
        b.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        return b.toByteArray();
    }

    static RawPdf page(String resources, String content) {
        RawPdf r = new RawPdf();
        r.add("<</Type/Catalog/Pages 2 0 R>>");
        r.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        r.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<<" + resources + ">>/Contents 4 0 R>>");
        r.add(stream("", content));
        return r;
    }

    static String helvetica(String name) {
        return "/" + name + "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>";
    }

    byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, header);
        long[] offsets = new long[objects.size() + 1];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i + 1] = out.size();
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objects.get(i));
            write(out, "\nendobj\n");
        }
        long xref = out.size();
        StringBuilder b = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n");
        b.append("0000000000 65535 f\r\n");
        for (int i = 1; i <= objects.size(); i++) {
            b.append(String.format("%010d 00000 n\r\n", offsets[i]));
        }
        b.append("trailer\n<</Size ").append(objects.size() + 1).append("/Root 1 0 R").append(trailerExtra)
                .append(">>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, b.toString());
        return out.toByteArray();
    }

    Path write(Path dir, String name) throws IOException {
        Path p = dir.resolve(name + ".pdf");
        Files.write(p, bytes());
        return p;
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
