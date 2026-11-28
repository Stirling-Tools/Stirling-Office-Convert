package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.Deflater;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.jpx.JpxEncoder;

class JpxHostileTest {

    private static final String SECRET = "canary-6f1c2d";

    @TempDir
    Path dir;

    private static JpxEncoder.Planes photo(int w, int h) {
        int[][] s = new int[3][w * h];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < w * h; i++) {
                s[c][i] = (i % w * (c + 1) + i / w * 3) & 0xFF;
            }
        }
        return new JpxEncoder.Planes(w, h, s, new int[] {8, 8, 8}, null, JpxEncoder.Colour.SRGB, -1);
    }

    private Path page(byte[] data, int width, int height, boolean flate, boolean smaskInData) throws Exception {
        Path in = dir.resolve("hostile.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = Samples.page(d);
            COSStream img = d.getDocument().createCOSStream();
            try (OutputStream o = img.createRawOutputStream()) {
                o.write(flate ? deflate(data) : data);
            }
            img.setItem(COSName.TYPE, COSName.XOBJECT);
            img.setItem(COSName.SUBTYPE, COSName.IMAGE);
            if (flate) {
                COSArray filters = new COSArray();
                filters.add(COSName.FLATE_DECODE);
                filters.add(COSName.JPX_DECODE);
                img.setItem(COSName.FILTER, filters);
            } else {
                img.setItem(COSName.FILTER, COSName.JPX_DECODE);
            }
            img.setInt(COSName.WIDTH, width);
            img.setInt(COSName.HEIGHT, height);
            if (smaskInData) {
                img.setInt(COSName.getPDFName("SMaskInData"), 1);
            }
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im0"), img);
            COSDictionary res = new COSDictionary();
            res.setItem(COSName.XOBJECT, xobjects);
            p.getCOSObject().setItem(COSName.RESOURCES, res);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write("q 320 0 0 240 100 400 cm /Im0 Do Q".getBytes(StandardCharsets.US_ASCII));
            }
            p.setContents(s);
            d.save(in.toFile());
        }
        return in;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] body) {
        int len = 8 + body.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len});
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] withBoxesBeforeCodestream(byte[] jp2, byte[]... extra) {
        int at = -1;
        for (int i = 0; i + 8 < jp2.length; i++) {
            if (jp2[i + 4] == 'j' && jp2[i + 5] == 'p' && jp2[i + 6] == '2' && jp2[i + 7] == 'c') {
                at = i;
                break;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jp2, 0, at);
        for (byte[] b : extra) {
            out.writeBytes(b);
        }
        out.write(jp2, at, jp2.length - at);
        return out.toByteArray();
    }

    private Path secret() throws IOException {
        Path f = dir.resolve("secret.txt");
        Files.writeString(f, SECRET);
        return f;
    }

    private static String text(Path pdf) throws IOException {
        return new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
    }

    @Test
    void referencesInsideAJp2FileNeverReachTheOutput() throws Exception {
        Path secret = secret();
        String uri = secret.toUri().toString();
        byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"" + uri + "\">"
                + "<!ENTITY r SYSTEM \"http://127.0.0.1:9/x.dtd\">]><x>&e;&r;</x>").getBytes(StandardCharsets.UTF_8);
        byte[] url = ("\0\0\0\0" + uri + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] remote = ("\0\0\0\0http://127.0.0.1:9/fragment\0").getBytes(StandardCharsets.UTF_8);
        byte[] jp2 = JpxEncoder.encode(photo(120, 90), JpxEncoder.Options.rate(1));
        byte[] hostile = withBoxesBeforeCodestream(jp2, box("xml ", xml), box("url ", url), box("url ", remote),
                box("uuid", new byte[24]));
        Path in = page(hostile, 120, 90, false, true);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("refs-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertFalse(text(out).contains(SECRET), level.toString());
            VeraPdf.assertCompliant(out, level);
        }
    }

    @Test
    void aFragmentTableWithoutACodestreamIsRefused() throws Exception {
        Path secret = secret();
        byte[] url = ("\0\0\0\0" + secret.toUri() + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] flst = {0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 0, 1};
        byte[] ftbl = box("ftbl", box("flst", flst));
        byte[] jp2 = JpxEncoder.encode(photo(64, 64), JpxEncoder.Options.exact());
        int at = new String(jp2, StandardCharsets.ISO_8859_1).indexOf("jp2c") - 4;
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(jp2, 0, at);
        file.writeBytes(ftbl);
        file.writeBytes(box("dtbl", concat(new byte[] {0, 1}, box("url ", url))));
        Path in = page(file.toByteArray(), 64, 64, false, false);
        Path out = dir.resolve("ftbl.pdf");
        IOException e = assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B)));
        assertFalse(String.valueOf(e.getMessage()).contains(SECRET));
        assertFalse(Files.exists(out) && text(out).contains(SECRET));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void anOversizedCodestreamHeaderIsRefusedBeforeAnythingIsAllocated() throws Exception {
        byte[] cs = JpxEncoder.encode(photo(32, 32), JpxEncoder.Options.exact().withCodestream(true));
        byte[] huge = cs.clone();
        for (int k : new int[] {8, 12, 24, 28}) {
            huge[k] = 0;
            huge[k + 1] = 0;
            huge[k + 2] = (byte) 0xEA;
            huge[k + 3] = 0x60;
        }
        Path in = page(huge, 60000, 60000, false, true);
        Path out = dir.resolve("huge.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B))));
    }

    @Test
    void aDecompressionBombAroundAJpxStreamIsRefused() throws Exception {
        byte[] jp2 = JpxEncoder.encode(photo(32, 32), JpxEncoder.Options.exact());
        byte[] bomb = Arrays.copyOf(jp2, jp2.length + (200 << 20));
        Path in = page(bomb, 32, 32, true, true);
        assertTrue(Files.size(in) < 2 << 20, "bomb stays small on disk: " + Files.size(in));
        Path out = dir.resolve("bomb.pdf");
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B))));
    }

    @Test
    void deeplyNestedBoxesDoNotOverflowTheStack() throws Exception {
        byte[] nested = box("asoc", new byte[0]);
        for (int i = 0; i < 20000; i++) {
            nested = box("asoc", nested);
        }
        byte[] jp2 = JpxEncoder.encode(photo(48, 48), JpxEncoder.Options.rate(2));
        Path in = page(withBoxesBeforeCodestream(jp2, nested), 48, 48, false, true);
        Path out = dir.resolve("nested.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }
}
