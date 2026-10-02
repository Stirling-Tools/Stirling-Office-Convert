package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.hemf.draw.HemfGraphics;
import org.apache.poi.hemf.record.emf.HemfRecord;
import org.apache.poi.hemf.record.emf.HemfRecordType;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwmf.record.HwmfColorRef;
import org.apache.poi.util.LittleEndianInputStream;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.testing.Emf;

class EmfFallbackTest {

    @Test
    void aFailureSwallowedByPoiTriggersTheRasterFallback() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture picture = Metafiles.emf(doc, picture(false));
            assertFalse(picture.vector());
            var image = picture.image().getImage();
            assertEquals(0xFF0000, image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xFFFFFF);
        }
    }

    @Test
    void aRecordThatFailsBothBackendsCannotReturnAPartialPicture() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            assertThrows(IOException.class, () -> Metafiles.emf(doc, picture(true)));
        }
    }

    private static HemfPicture picture(boolean failBoth) {
        return new HemfPicture(new ByteArrayInputStream(Emf.of(100, 100).bytes())) {
            @Override
            public List<HemfRecord> getRecords() {
                List<HemfRecord> records = new ArrayList<>(super.getRecords());
                records.add(new HemfRecord() {
                    @Override
                    public HemfRecordType getEmfRecordType() {
                        return HemfRecordType.rectangle;
                    }

                    @Override
                    public long init(LittleEndianInputStream in, long size, long id) {
                        return 0;
                    }

                    @Override
                    public void draw(HemfGraphics context) {
                        if (failBoth || context.getTransform().getScaleX() < 1) {
                            throw new IllegalStateException("Simulated vector backend failure");
                        }
                        context.getProperties().setBrushColor(new HwmfColorRef(Color.RED));
                        context.fill(new Rectangle2D.Double(10, 10, 80, 80));
                    }

                    @Override
                    public Map<String, java.util.function.Supplier<?>> getGenericProperties() {
                        return Map.of();
                    }
                });
                return records;
            }
        };
    }
}
