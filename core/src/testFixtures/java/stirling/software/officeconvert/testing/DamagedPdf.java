package stirling.software.officeconvert.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public final class DamagedPdf {

    public static final String HELLO = "1. 0. 0. 1. 50. 700. cm\nBT\n/F0 36. Tf\n(Hello PDF-world!) Tj\nET\n";

    private final TreeMap<Integer, String> objects = new TreeMap<>();

    private String header = "%PDF-1.4\n%âãÏÓ\n";

    private String trailer = "/Root 5 0 R";

    private boolean xref = true;

    public static DamagedPdf hello() {
        return new DamagedPdf()
                .object(1, "<< /Kids [2 0 R] /Type /Pages /Count 1 >>")
                .object(2, "<< /Parent 1 0 R /MediaBox [0 0 612 792] /Resources 3 0 R /Type /Page /Contents [4 0 R] >>")
                .object(3, "<< /Font << /F0 << /BaseFont /Times-Italic /Subtype /Type1 /Type /Font >> >> >>")
                .content(HELLO)
                .object(5, "<< /Pages 1 0 R /Type /Catalog >>");
    }

    public DamagedPdf object(int number, String body) {
        objects.put(number, body);
        return this;
    }

    public DamagedPdf without(int number) {
        objects.remove(number);
        return this;
    }

    public DamagedPdf content(String data) {
        return object(4, "<< /Length " + data.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + data
                + "\nendstream");
    }

    public DamagedPdf header(String text) {
        header = text;
        return this;
    }

    public DamagedPdf trailer(String entries) {
        trailer = entries;
        return this;
    }

    public DamagedPdf noXref() {
        xref = false;
        return this;
    }

    public byte[] bytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, header);
        Map<Integer, Integer> offsets = new TreeMap<>();
        for (Map.Entry<Integer, String> e : objects.entrySet()) {
            offsets.put(e.getKey(), out.size());
            write(out, e.getKey() + " 0 obj\n" + e.getValue() + "\nendobj\n");
        }
        int size = objects.isEmpty() ? 1 : objects.lastKey() + 1;
        int start = out.size();
        StringBuilder b = new StringBuilder();
        if (xref) {
            b.append("xref\n0 ").append(size).append("\n0000000000 65535 f\r\n");
            for (int n = 1; n < size; n++) {
                Integer at = offsets.get(n);
                b.append(at == null ? "0000000000 65535 f\r\n" : String.format("%010d 00000 n\r\n", at));
            }
        }
        b.append("trailer\n<< ").append(trailer).append(" /Size ").append(size).append(" >>\nstartxref\n")
                .append(xref ? start : 0).append("\n%%EOF\n");
        write(out, b.toString());
        return out.toByteArray();
    }

    public Path write(Path file) throws IOException {
        Files.write(file, bytes());
        return file;
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
