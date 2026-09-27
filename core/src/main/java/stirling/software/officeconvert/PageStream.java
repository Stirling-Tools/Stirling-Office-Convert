package stirling.software.officeconvert;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageReader;
import stirling.software.officeconvert.extract.PdfFiles;

final class PageStream {

    private static final Log LOG = LogFactory.getLog(PageStream.class);

    static final int SAMPLE_ALL_BELOW = 40;

    private PageStream() {}

    static PDDocument load(Path pdf, String password) throws IOException {
        return PdfFiles.open(pdf, password);
    }

    static void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    static void readPages(PageReader reader, int first, int last, PageReader.PageConsumer consumer,
            PageReader.PageConsumer onUnreadable) throws IOException {
        int from = first;
        while (from <= last) {
            int[] reading = {from};
            boolean[] consuming = {false};
            try {
                reader.read(from, last, true, page -> {
                    consuming[0] = true;
                    consumer.accept(page);
                    consuming[0] = false;
                    reading[0] = page.index() + 1;
                });
                return;
            } catch (IOException | RuntimeException | StackOverflowError e) {
                if (consuming[0] || e instanceof InterruptedIOException) {
                    throw e;
                }
                if (onUnreadable == null) {
                    throw new IOException("Page " + (reading[0] + 1) + " could not be read", e);
                }
                LOG.warn("Page " + (reading[0] + 1) + " could not be read", e);
                onUnreadable.accept(reader.unreadable(reading[0]));
                from = reading[0] + 1;
            }
        }
    }

    static List<int[]> sampleRuns(int first, int last) {
        int count = last - first + 1;
        TreeSet<Integer> pages = new TreeSet<>();
        if (count <= SAMPLE_ALL_BELOW) {
            for (int i = first; i <= last; i++) {
                pages.add(i);
            }
        } else {
            for (int i = 0; i < 20; i++) {
                pages.add(first + i);
            }
            for (int i = 0; i < 5; i++) {
                pages.add(last - i);
            }
            for (int k = 1; k <= 15; k++) {
                int page = first + (int) ((long) count * k / 16);
                pages.add(page);
                pages.add(Math.min(last, page + 1));
            }
        }
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        int prev = -2;
        for (int p : pages) {
            if (p != prev + 1) {
                if (start >= 0) {
                    runs.add(new int[] {start, prev});
                }
                start = p;
            }
            prev = p;
        }
        if (start >= 0) {
            runs.add(new int[] {start, prev});
        }
        return runs;
    }

    static int weight(PageData page) {
        var g = page.graphics();
        return page.glyphs().size() + page.hidden().size() + page.rotated().size()
                + g.rules().size() + g.fills().size() + g.images().size() + g.marks().size();
    }

    static boolean autoHyphenated(PDDocument doc) {
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info == null) {
            return false;
        }
        String s = ((info.getProducer() == null ? "" : info.getProducer()) + " "
                        + (info.getCreator() == null ? "" : info.getCreator()))
                .toLowerCase(Locale.ROOT);
        for (String k : new String[] {"tex", "indesign", "quark", "framemaker", "scribus", "arbortext", "dvipdf", "xsl"}) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
