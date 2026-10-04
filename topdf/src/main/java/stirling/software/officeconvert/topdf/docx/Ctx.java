package stirling.software.officeconvert.topdf.docx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class Ctx {

    interface PageNumbers {
        String page();

        default int pageNumber() {
            return 1;
        }

        String pages();

        String sectionPages();
    }

    static final PageNumbers NO_NUMBERS = new PageNumbers() {
        @Override
        public String page() {
            return "1";
        }

        @Override
        public String pages() {
            return "1";
        }

        @Override
        public String sectionPages() {
            return "1";
        }
    };

    final DocxPackage pkg;

    final RenderJob job;

    final Fonts fonts;

    final Settings settings;

    private final Map<String, DecodedPicture> pictures = new HashMap<>();

    // Drawn once per chart and size, keyed by identity: a chart record hashes every value it holds
    private final Map<Chart, Map<Long, List<Op>>> charts = new IdentityHashMap<>();

    private final Map<String, Boolean> failedPictures = new HashMap<>();

    private final Map<Integer, String> footnoteNumbers = new HashMap<>();

    private final Map<Integer, String> endnoteNumbers = new HashMap<>();

    private int nextFootnote;

    private int nextEndnote;

    PageNumbers numbers = NO_NUMBERS;

    String currentNoteMark;

    int notePage;

    int noteSection;

    String noteRestart;

    private int noteScope = -1;

    int headerDepth;

    // The body's document grid pitch for the section being laid out
    float gridPitch;

    float charGrid;

    boolean bodyTotals;

    int knownPages;

    Map<Integer, Integer> knownSectionPages = Map.of();

    int depth;

    Ctx(DocxPackage pkg) {
        this.pkg = pkg;
        this.job = pkg.job;
        this.fonts = new Fonts(pkg.job, pkg.theme, pkg.settings.eastAsiaLang).bidi(pkg.settings.bidiLang)
                .alternatives(pkg.altFonts);
        this.settings = pkg.settings;
        this.nextFootnote = pkg.settings.footnoteStart;
        this.nextEndnote = pkg.settings.endnoteStart;
    }

    List<Op> chart(Chart chart, float w, float h) {
        long size = (long) Float.floatToIntBits(w) << 32 | Float.floatToIntBits(h) & 0xFFFFFFFFL;
        return charts.computeIfAbsent(chart, c -> new HashMap<>()).computeIfAbsent(size, k -> {
            List<Op> ops = new ArrayList<>();
            ChartPainter.paint(chart, 0, 0, w, h, ops, fonts, pkg.theme);
            return List.copyOf(ops);
        });
    }

    DecodedPicture picture(String part) {
        if (part == null || failedPictures.containsKey(part)) {
            return null;
        }
        DecodedPicture p = pictures.get(part);
        if (p != null) {
            return p;
        }
        try {
            job.checkpoint();
            byte[] data = pkg.zip.read(part);
            p = PictureDecoder.decode(job.document(), data);
            pictures.put(part, p);
            return p;
        } catch (IOException | RuntimeException e) {
            if (e instanceof RenderJob.PageLimitReached r) {
                throw r;
            }
            failedPictures.put(part, true);
            job.warn("A picture could not be drawn (" + part + "): " + e.getMessage());
            return null;
        }
    }

    String noteNumber(Inline.NoteRef ref) {
        if (ref.endnote()) {
            return endnoteNumbers.computeIfAbsent(ref.id(), k -> NumberFormat.format(nextEndnote++,
                    settings.endnoteFormat));
        }
        String restart = noteRestart != null ? noteRestart : settings.footnoteRestart;
        int scope = "eachPage".equals(restart) ? notePage : "eachSect".equals(restart) ? noteSection : -1;
        if (scope != noteScope && !footnoteNumbers.containsKey(ref.id())) {
            if (noteScope >= 0 || scope >= 0) {
                nextFootnote = settings.footnoteStart;
            }
            noteScope = scope;
        }
        return footnoteNumbers.computeIfAbsent(ref.id(), k -> NumberFormat.format(nextFootnote++,
                settings.footnoteFormat));
    }

    void resetNotes() {
        footnoteNumbers.clear();
        endnoteNumbers.clear();
        nextFootnote = settings.footnoteStart;
        nextEndnote = settings.endnoteStart;
        noteScope = -1;
    }

    float bodyGrid() {
        return headerDepth > 0 ? 0 : gridPitch;
    }

    float charGrid() {
        return headerDepth > 0 ? 0 : charGrid;
    }
}
