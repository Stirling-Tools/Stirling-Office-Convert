package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStreamLoadTest {

    private static final int OBJECTS = 300_000;

    @TempDir
    Path dir;

    @Test
    void objectsInThousandsOfObjectStreamsResolveInLinearTime() throws Exception {
        Path pdf = dir.resolve("many.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            COSArray items = new COSArray();
            for (int i = 0; i < OBJECTS; i++) {
                COSDictionary item = new COSDictionary();
                item.setInt(COSName.K, i);
                items.add(item);
            }
            d.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("Items"), items);
            d.save(pdf.toFile(), new CompressParameters(50));
        }
        long sum = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            try (PDDocument d = PdfFiles.open(pdf, null)) {
                COSArray items = (COSArray) d.getDocumentCatalog().getCOSObject()
                        .getDictionaryObject(COSName.getPDFName("Items"));
                long total = 0;
                for (int i = 0; i < items.size(); i++) {
                    total += ((COSInteger) ((COSDictionary) items.getObject(i)).getDictionaryObject(COSName.K))
                            .longValue();
                }
                return total;
            }
        });
        assertEquals((long) OBJECTS * (OBJECTS - 1) / 2, sum);
    }
}
