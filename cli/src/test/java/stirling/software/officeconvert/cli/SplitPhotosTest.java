package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitPhotosTest {

    @TempDir
    Path dir;

    record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = Main.run(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void writesOnePngPerPhotoAndKeepsExistingOnes() throws Exception {
        Path scan = dir.resolve("album page.png");
        ImageIO.write(scan(), "png", scan.toFile());
        Path out = dir.resolve("photos");
        Result r = run("split-photos", scan.toString(), "-o", out.toString(), "--angle_threshold", "10", "--tolerance", "30",
                "--min_area", "10000", "--min_contour_area", "500", "--border_size", "1");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().startsWith("OK ") && r.out().contains("-> 2 photos"), r.out());
        BufferedImage first = ImageIO.read(out.resolve("album page_1.png").toFile());
        BufferedImage second = ImageIO.read(out.resolve("album page_2.png").toFile());
        assertEquals(318, first.getWidth(), 3);
        assertEquals(218, first.getHeight(), 3);
        assertEquals(258, second.getWidth(), 3);
        assertFalse(Files.exists(out.resolve("album page_3.png")));

        Result again = run("split-photos", scan.toString(), "-o", out.toString());
        assertEquals(1, again.code());
        assertTrue(again.err().startsWith("SKIP ") && again.err().contains("--overwrite"), again.err());
        assertEquals(0, run("split-photos", scan.toString(), "-o", out.toString(), "--overwrite", "-q").code());
    }

    @Test
    void scriptMethodKeepsTheUprightBoxes() throws Exception {
        Path scan = dir.resolve("scan.png");
        ImageIO.write(scan(), "png", scan.toFile());
        Result r = run("split-photos", scan.toString(), "--method", "script", "-q");
        assertEquals(0, r.code(), r.err());
        BufferedImage first = ImageIO.read(dir.resolve("scan_1.png").toFile());
        assertTrue(first.getWidth() > 275, "width " + first.getWidth());
    }

    @Test
    void searchesEveryPageOfAPdf() throws Exception {
        Path pdf = dir.resolve("scans.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(new PDRectangle(450, 360));
                doc.addPage(page);
                PDImageXObject image = LosslessFactory.createFromImage(doc, scan());
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(image, 0, 0, 450, 360);
                }
            }
            doc.save(pdf.toFile());
        }
        Result r = run("split-photos", pdf.toString(), "--dpi", "144", "-o", dir.resolve("out").toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(Files.exists(dir.resolve("out/scans_4.png")));
        assertFalse(Files.exists(dir.resolve("out/scans_5.png")));
    }

    @Test
    void refusesBadArguments() {
        assertEquals(2, run("split-photos").code());
        assertEquals(2, run("split-photos", "a.png", "--tolerance", "lots").code());
        assertEquals(2, run("split-photos", "a.png", "--method", "magic").code());
        assertEquals(2, run("split-photos", "a.png", "--border-size", "-1").code());
        Result missing = run("split-photos", dir.resolve("none.png").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("no such file"), missing.err());
        assertEquals(0, run("split-photos", "--help").code());
    }

    private static BufferedImage scan() {
        BufferedImage img = new BufferedImage(900, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(247, 247, 244));
        g.fillRect(0, 0, 900, 720);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(80, 80, new Color(180, 40, 30), 400, 300, new Color(40, 90, 200)));
        g.fillRect(80, 80, 320, 220);
        AffineTransform t = g.getTransform();
        g.rotate(Math.toRadians(5), 620, 450);
        g.setPaint(new GradientPaint(490, 350, new Color(30, 140, 60), 750, 550, new Color(220, 200, 40)));
        g.fillRect(490, 340, 260, 220);
        g.setTransform(t);
        g.dispose();
        return img;
    }

    @Test
    void scansSharingANameKeepTheirExtensionInThePhotoNames() throws Exception {
        Path png = dir.resolve("album.png");
        Path jpg = dir.resolve("album.jpg");
        ImageIO.write(scan(), "png", png.toFile());
        ImageIO.write(scan(), "jpg", jpg.toFile());
        Path out = dir.resolve("out");
        for (String overwrite : new String[] {"-q", "--overwrite"}) {
            Result r = run("split-photos", png.toString(), jpg.toString(), "-o", out.toString(), overwrite);
            assertEquals(0, r.code(), r.err());
            assertFalse(Files.exists(out.resolve("album_1.png")));
            BufferedImage fromPng = ImageIO.read(out.resolve("album.png_1.png").toFile());
            BufferedImage fromJpg = ImageIO.read(out.resolve("album.jpg_1.png").toFile());
            assertEquals(fromPng.getWidth(), fromJpg.getWidth(), 3);
            assertTrue(Files.exists(out.resolve("album.png_2.png")) && Files.exists(out.resolve("album.jpg_2.png")));
        }
        Result alone = run("split-photos", png.toString(), "-o", dir.resolve("alone").toString());
        assertEquals(0, alone.code(), alone.err());
        assertTrue(Files.exists(dir.resolve("alone").resolve("album_1.png")));
    }

    @Test
    void overwriteCannotReplaceAnotherSuppliedScan() throws Exception {
        Path first = dir.resolve("album.png");
        Path second = dir.resolve("album_1.png");
        ImageIO.write(scan(), "png", first.toFile());
        ImageIO.write(scan(), "png", second.toFile());
        byte[] original = Files.readAllBytes(second);
        Result result = run("split-photos", first.toString(), second.toString(), "-o", dir.toString(), "--overwrite");
        assertEquals(2, result.code(), result.err());
        assertTrue(result.err().contains("overwrite input"), result.err());
        assertArrayEquals(original, Files.readAllBytes(second));
    }


    @Test
    void overwriteProtectsHardLinksAndSymbolicLinksToSuppliedInputs() throws Exception {
        Path first = dir.resolve("album.png");
        Path protectedScan = dir.resolve("original.png");
        Path target = dir.resolve("album_1.png");
        ImageIO.write(scan(), "png", first.toFile());
        ImageIO.write(scan(), "png", protectedScan.toFile());
        byte[] original = Files.readAllBytes(protectedScan);
        for (boolean symbolic : new boolean[] {false, true}) {
            if (symbolic) {
                try {
                    Files.createSymbolicLink(target, protectedScan);
                } catch (UnsupportedOperationException | IOException unavailable) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + unavailable);
                }
            } else {
                Files.createLink(target, protectedScan);
            }
            Result result = run("split-photos", first.toString(), protectedScan.toString(), "-o", dir.toString(),
                    "--overwrite");
            assertEquals(2, result.code(), result.err());
            assertTrue(result.err().contains("overwrite input"), result.err());
            assertArrayEquals(original, Files.readAllBytes(protectedScan));
            Files.delete(target);
        }
    }

    @Test
    void runningOutOfMemoryFailsThatScanAndCarriesOn() throws Exception {
        Path huge = dir.resolve("huge.png");
        ImageIO.write(new BufferedImage(3000, 3000, BufferedImage.TYPE_INT_RGB), "png", huge.toFile());
        Path small = dir.resolve("small.png");
        ImageIO.write(scan(), "png", small.toFile());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Xmx64m", "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "split-photos", huge.toString(), small.toString(), "--method", "script", "-o",
                dir.resolve("out").toString())
                .start();
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, process.waitFor(), err);
        assertTrue(err.contains("FAIL " + huge), err);
        assertFalse(err.contains("Exception in thread"), err);
        assertTrue(new String(out, StandardCharsets.UTF_8).contains("OK " + small), err);
    }
}
