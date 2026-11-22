package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;

class ImageBudgetJpxHeaderTest {

    @Test
    void theSizeGateReadsTheCodestreamTheDecoderReads() {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(ByteBuffer.allocate(12).putInt(12).putInt(0x6A502020).putInt(0x0D0A870A).array());
        byte[] decoy = siz(10, 10, 1);
        file.writeBytes(ByteBuffer.allocate(8).putInt(8 + decoy.length).putInt(0x786D6C20).array());
        file.writeBytes(decoy);
        byte[] real = siz(3000, 3000, 3);
        file.writeBytes(ByteBuffer.allocate(8).putInt(8 + real.length).putInt(0x6A703263).array());
        file.writeBytes(real);
        assertEquals(3000L * 3000 * 3, ImageBudget.jpxBytes(file.toByteArray()));
    }

    @Test
    void aStreamLabelledAsAnImageIsStillInflateChecked() throws Exception {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(packed)) {
            byte[] zeros = new byte[1 << 20];
            for (long n = 0; n <= StreamGuard.MAX_DECODED; n += zeros.length) {
                deflate.write(zeros);
            }
        }
        byte[] saved;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream label = doc.getDocument().createCOSStream();
            label.setItem(COSName.SUBTYPE, COSName.IMAGE);
            label.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            try (var out = label.createRawOutputStream()) {
                packed.writeTo(out);
            }
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("Im1"), label);
            PDResources resources = new PDResources();
            resources.getCOSObject().setItem(COSName.XOBJECT, xobjects);
            page.setResources(resources);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            saved = out.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(saved)) {
            assertEquals(1, StreamGuard.check(doc));
            COSStream label = (COSStream) doc.getPage(0).getResources().getCOSObject()
                    .getCOSDictionary(COSName.XOBJECT).getDictionaryObject(COSName.getPDFName("Im1"));
            assertEquals(0, label.getLength());
            assertFalse(StreamGuard.fits(label));
        }
    }

    private static byte[] siz(int width, int height, int components) {
        ByteBuffer header = ByteBuffer.allocate(42 + 3 * components);
        header.putInt(0xFF4FFF51).putShort((short) (38 + 3 * components)).putShort((short) 0);
        header.putInt(width).putInt(height).putInt(0).putInt(0);
        header.putInt(width).putInt(height).putInt(0).putInt(0);
        header.putShort((short) components);
        for (int c = 0; c < components; c++) {
            header.put((byte) 7).put((byte) 1).put((byte) 1);
        }
        return header.array();
    }
}
