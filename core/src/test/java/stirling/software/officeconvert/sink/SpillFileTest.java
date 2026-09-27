package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.StyleSheet;

class SpillFileTest {

    @Test
    void blocksReadBackInAnyOrderAndTheFileGoesOnClose() throws IOException {
        Random r = new Random(7);
        List<byte[]> data = new ArrayList<>();
        List<SpillFile.Block> blocks = new ArrayList<>();
        Path file;
        try (SpillFile spill = new SpillFile("office-convert-test")) {
            for (int i = 0; i < 40; i++) {
                byte[] b = new byte[1 + r.nextInt(200_000)];
                r.nextBytes(b);
                data.add(b);
                blocks.add(spill.append(b));
            }
            for (int i = data.size() - 1; i >= 0; i -= 3) {
                assertArrayEquals(data.get(i), spill.read(blocks.get(i)), "block " + i);
            }
            assertArrayEquals(data.getFirst(), spill.read(blocks.getFirst()));
            file = spill.file();
            assertTrue(Files.exists(file), "one file holds every block");
        }
        assertFalse(Files.exists(file), "removed on close");
    }

    @Test
    void mediaStoreReadsSpilledImagesBack() throws IOException {
        List<byte[]> data = randomImages(12);
        try (MediaStore store = new MediaStore()) {
            List<Picture.MediaRef> refs = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                refs.add(store.add(data.get(i), "png", 10, 10, "image" + i));
            }
            for (int i = 0; i < data.size(); i++) {
                assertArrayEquals(data.get(i), store.bytes(refs.get(i)), "image " + i);
            }
        }
    }

    @Test
    void docxKeepsEverySpilledPicture() throws IOException {
        List<byte[]> data = randomImages(12);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DocxWriter w = new DocxWriter(out)) {
            StyleSheet styles = new StyleSheet(SampleDocument.BODY);
            w.begin(styles, null);
            Paragraph p = SampleDocument.paragraph("Pictures");
            for (int i = 0; i < data.size(); i++) {
                Picture.MediaRef ref = w.media(data.get(i), i % 3 == 0 ? "jpeg" : "png", 10, 10, "image" + i);
                p.inlines.add(new Inline.Image(new Picture(ref, 20, 20)));
            }
            w.block(p);
            w.finish(SampleDocument.section(1), null, new Numbering(), styles, "t", "a");
        }
        int found = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().startsWith("word/media/image")) {
                    continue;
                }
                int i = Integer.parseInt(e.getName().replaceAll("\\D", "")) - 1;
                assertArrayEquals(data.get(i), zip.readAllBytes(), e.getName());
                assertEquals(e.getName().endsWith(".png") ? ZipEntry.STORED : ZipEntry.DEFLATED, e.getMethod(), e.getName());
                found++;
            }
        }
        assertEquals(data.size(), found, "every picture is in the package");
    }

    private static List<byte[]> randomImages(int megabytes) {
        Random r = new Random(11);
        List<byte[]> data = new ArrayList<>();
        for (int i = 0; i < megabytes; i++) {
            byte[] b = new byte[1 << 20];
            r.nextBytes(b);
            data.add(b);
        }
        return data;
    }
}
