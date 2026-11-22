package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectStreamLoadsTest {

    private static final int PAGES = 300;

    @TempDir
    Path dir;

    // Every page carries an unused font, and every tenth page links to a page that is no longer in the tree
    private Path packed() throws Exception {
        Path in = dir.resolve("packed.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDType1Font used = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font unused = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDPage orphan = null;
            for (int p = 0; p < PAGES; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(used, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Page " + p);
                    cs.endText();
                }
                page.getResources().add(unused);
                if (p == 1) {
                    orphan = page;
                } else if (p % 10 == 0 && p > 0) {
                    PDAnnotationLink link = new PDAnnotationLink();
                    link.setRectangle(new PDRectangle(72, 600, 100, 20));
                    PDPageFitDestination target = new PDPageFitDestination();
                    target.setPage(orphan);
                    link.setDestination(target);
                    page.getAnnotations().add(link);
                }
            }
            doc.removePage(orphan);
            doc.save(in.toFile());
        }
        return in;
    }

    private static List<COSObjectKey> inObjectStreams(PDDocument doc) {
        List<COSObjectKey> keys = new ArrayList<>();
        for (Map.Entry<COSObjectKey, Long> e : doc.getDocument().getXrefTable().entrySet()) {
            if (e.getValue() != null && e.getValue() < 0) {
                keys.add(e.getKey());
            }
        }
        Collections.sort(keys);
        return keys;
    }

    @Test
    void keptContainersHandOutOneSharedParseAndFreshLoadsResume() throws Exception {
        try (PDDocument doc = Loader.loadPDF(packed().toFile())) {
            List<COSObjectKey> keys = inObjectStreams(doc);
            assertTrue(keys.size() > PAGES, "the sample should pack its pages into object streams");
            Source source = new Source(doc.getDocument());
            COSObjectKey key = keys.get(keys.size() / 2);
            assertNotSame(source.load(key), source.load(key));
            source.keepContainers(true);
            COSBase kept = source.load(key);
            assertSame(kept, source.load(key));
            source.keepContainers(false);
            COSBase fresh = source.load(key);
            assertNotSame(kept, fresh);
            assertEquals(kept.toString(), fresh.toString());
        }
    }

    @Test
    void batchLoadsGiveEachKeyItsOwnFreshCopyOfWhatASingleLoadReads() throws Exception {
        try (PDDocument doc = Loader.loadPDF(packed().toFile())) {
            List<COSObjectKey> keys = inObjectStreams(doc);
            Source source = new Source(doc.getDocument());
            Map<COSObjectKey, COSBase> batch = new HashMap<>();
            source.load(keys, (key, object) -> assertNull(batch.put(key, object)));
            assertEquals(Set.copyOf(keys), batch.keySet());
            Set<COSBase> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
            distinct.addAll(batch.values());
            assertEquals(keys.size(), distinct.size());
            for (COSObjectKey key : keys) {
                COSBase single = source.load(key);
                assertNotSame(single, batch.get(key));
                assertEquals(single.toString(), batch.get(key).toString());
            }
        }
    }

    @Test
    void unusedFontsAndDeadLinksGoFromEveryPackedPage() throws Exception {
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(packed(), out, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(PAGES - 1, doc.getNumberOfPages());
            int links = 0;
            for (PDPage page : doc.getPages()) {
                COSDictionary fonts = page.getResources().getCOSObject().getCOSDictionary(COSName.FONT);
                assertEquals(1, fonts.size());
                for (var annotation : page.getAnnotations()) {
                    links++;
                    assertFalse(annotation.getCOSObject().containsKey(COSName.DEST));
                }
            }
            assertEquals(PAGES / 10 - 1, links);
        }
    }
}
