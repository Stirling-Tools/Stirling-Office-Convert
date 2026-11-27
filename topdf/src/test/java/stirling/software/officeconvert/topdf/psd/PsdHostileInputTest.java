package stirling.software.officeconvert.topdf.psd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.UnsupportedFormatException;
import stirling.software.officeconvert.topdf.OfficeToPdf;

class PsdHostileInputTest {

    @TempDir
    Path dir;

    private OfficeToPdf.Result convert(PsdFixture f, String name) throws IOException {
        Path in = Files.write(dir.resolve(name), f.bytes());
        return OfficeToPdf.convert(in, dir.resolve(name + ".pdf"));
    }

    private String pdfText(String name) throws IOException {
        return new String(Files.readAllBytes(dir.resolve(name + ".pdf")), StandardCharsets.ISO_8859_1);
    }

    @Test
    void anOversizedHeaderIsRefusedBeforeAnythingIsAllocated() throws IOException {
        PsdFixture psd = new PsdFixture(1, 1).flat(0).flat(0).flat(0);
        byte[] b = psd.bytes();
        b[18] = 0;
        b[19] = 0;
        b[20] = (byte) 0x75;
        b[21] = (byte) 0x31;
        Path wide = Files.write(dir.resolve("wide.psd"), b);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(wide, dir.resolve("wide.pdf")));
        assertTrue(e.getMessage().contains("30001") || String.valueOf(e.getCause()).contains("30001"), e.toString());
        assertThrows(IOException.class, () -> OfficeToPdf.memoryEstimate(wide));
        PsdFixture psb = new PsdFixture(1, 1).flat(0).flat(0).flat(0);
        psb.version = 2;
        byte[] p = psb.bytes();
        p[14] = 0x7F;
        Path tall = Files.write(dir.resolve("tall.psb"), p);
        assertThrows(IOException.class, () -> OfficeToPdf.convert(tall, dir.resolve("tall.pdf")));
        assertFalse(Files.exists(dir.resolve("tall.pdf")));
    }

    @Test
    @Timeout(60)
    void rowLengthsThatClaimGigabytesAreReadOnlyAsFarAsTheRowAndTheFile() throws IOException {
        PsdFixture f = new PsdFixture(64, 64);
        f.version = 2;
        f.forcedRowCount = 0x7FFFFFF0L;
        f.flat(1).flat(2).flat(3);
        OfficeToPdf.Result r = convert(f, "rle.psb");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("damaged")), r.warnings().toString());
    }

    @Test
    void aLayerCountPastTheLimitIsNotRead() throws IOException {
        PsdFixture f = new PsdFixture(2, 2).flat(9).flat(9).flat(9);
        f.layerCount = 30_000;
        OfficeToPdf.Result r = convert(f, "layers.psd");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("layers could not be read")), r.warnings().toString());
    }

    @Test
    @Timeout(60)
    void deeplyNestedGroupsDoNotOverflowTheStack() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 40));
        for (int i = 0; i < 3000; i++) {
            f.layers.add(new PsdFixture.Layer().section(3));
        }
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(-1, 255).fill(0, 80));
        for (int i = 0; i < 3000; i++) {
            f.layers.add(new PsdFixture.Layer().section(1));
        }
        OfficeToPdf.Result r = convert(f, "nested.psd");
        assertEquals(1, r.pages());
    }

    @Test
    @Timeout(60)
    void aDeeplyNestedEffectsDescriptorIsLeftOut() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(0);
        o.writeInt(16);
        for (int i = 0; i < 20_000; i++) {
            o.writeInt(0);
            o.writeInt(0);
            o.writeBytes("null");
            o.writeInt(1);
            o.writeInt(0);
            o.writeBytes("DrSh");
            o.writeBytes("Objc");
        }
        PsdFixture f = new PsdFixture(1, 1);
        f.hasMerged = false;
        f.flat(255).flat(255).flat(255);
        f.layers.add(new PsdFixture.Layer().rect(0, 0, 1, 1).fill(0, 1).fill(1, 2).fill(2, 3).block("lfx2",
                b.toByteArray()));
        OfficeToPdf.Result r = convert(f, "deepfx.psd");
        assertEquals(1, r.pages());
    }

    @Test
    @Timeout(60)
    void aCompressedLayerChannelThatInflatesToGigabytesIsReadOnlyForTheCanvasRows() throws IOException {
        int w = 20_000;
        int h = 5_000;
        Deflater d = new Deflater(9);
        byte[] zeros = new byte[1 << 20];
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        z.write(0);
        z.write(Rows.ZIP);
        byte[] buf = new byte[1 << 16];
        for (long left = (long) w * h; left > 0; left -= zeros.length) {
            d.setInput(zeros, 0, (int) Math.min(zeros.length, left));
            while (!d.needsInput()) {
                z.write(buf, 0, d.deflate(buf));
            }
        }
        d.finish();
        while (!d.finished()) {
            z.write(buf, 0, d.deflate(buf));
        }
        d.end();
        PsdFixture f = new PsdFixture(4, 4);
        f.hasMerged = false;
        f.mode = PsdDocument.GRAY;
        f.flat(255);
        PsdFixture.Layer bomb = new PsdFixture.Layer().rect(0, 0, h, w);
        bomb.channels.put(0, new byte[1]);
        bomb.encoded.put(0, z.toByteArray());
        f.layers.add(bomb);
        long before = System.nanoTime();
        OfficeToPdf.Result r = convert(f, "bomb.psd");
        assertEquals(1, r.pages());
        assertTrue(System.nanoTime() - before < 20_000_000_000L);
    }

    @Test
    @Timeout(60)
    void linkedFilesAndRemoteReferencesAreNeverOpened() throws Exception {
        Path canary = Files.writeString(dir.resolve("canary-secret.txt"), "CANARY-7f3a9");
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        try {
            server.setSoTimeout(200);
            AtomicInteger hits = new AtomicInteger();
            Thread listener = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        server.accept().close();
                        hits.incrementAndGet();
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            listener.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/x";
            String xmp = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"" + url + "\"><!ENTITY f SYSTEM \""
                    + canary.toUri() + "\">]><x:xmpmeta xmlns:x=\"urn:example:meta\">&e;&f;</x:xmpmeta>";
            PsdFixture f = new PsdFixture(2, 1);
            f.hasMerged = false;
            f.flat(255).flat(255).flat(255);
            f.resources.put(0x0424, xmp.getBytes(StandardCharsets.UTF_8));
            String paths = canary + "\0" + canary.toUri() + "\0../" + canary.getFileName() + "\0" + url;
            PsdFixture.Layer placed = new PsdFixture.Layer().rect(0, 0, 1, 2).fill(0, 1).fill(1, 2).fill(2, 3)
                    .block("PlLd", paths.getBytes(StandardCharsets.UTF_8)).block("SoLd", paths.getBytes(
                            StandardCharsets.UTF_8));
            f.layers.add(placed);
            PsdFixture.Layer linked = new PsdFixture.Layer().rect(0, 0, 0, 0).block("lnkE", paths.getBytes(
                    StandardCharsets.UTF_8));
            linked.channels.put(-1, new byte[0]);
            f.layers.add(linked);
            convert(f, "linked.psd");
            server.close();
            listener.join(5_000);
            assertEquals(0, hits.get());
            assertFalse(pdfText("linked.psd").contains("CANARY-7f3a9"));
        } finally {
            server.close();
        }
    }

    @Test
    void theOutputCarriesNoScriptsOrActions() throws IOException {
        PsdFixture f = new PsdFixture(2, 1);
        f.flat(1).flat(2).flat(3);
        f.resources.put(0x0424, "<x>javascript:app.alert(1)</x>".getBytes(StandardCharsets.UTF_8));
        convert(f, "script.psd");
        String pdf = pdfText("script.psd");
        for (String key : new String[] {"/JavaScript", "/JS", "/Launch", "/OpenAction", "/URI", "/EmbeddedFile"}) {
            assertFalse(pdf.contains(key), key);
        }
    }

    @Test
    void contentDecidesTheFormatWhateverTheName() throws IOException {
        PsdFixture f = new PsdFixture(1, 1).flat(5).flat(6).flat(7);
        Path disguised = Files.write(dir.resolve("report.docx"), f.bytes());
        OfficeToPdf.Result r = OfficeToPdf.convert(disguised, dir.resolve("report.pdf"));
        assertEquals(1, r.pages());
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
        Path fake = Files.write(dir.resolve("picture.psd"), zip);
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.convert(fake, dir.resolve("picture.pdf")));
        assertThrows(UnsupportedFormatException.class, () -> OfficeToPdf.memoryEstimate(fake));
    }
}
