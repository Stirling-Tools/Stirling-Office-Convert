package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

class LayoutBudgetTest {

    private static final FontInfo TIMES = new FontInfo("Times New Roman", false, false, true, false, false, "NimbusRomNo9L-Regu", true);

    private static final Duration BOX = Duration.ofSeconds(20);

    private static final Duration FAST = Duration.ofSeconds(5);

    @Test
    void columnSeedsOnAStaircaseStayCheap() {
        List<ColumnFinder.Item> items = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            float x = i * 0.1f;
            float y = i * 0.19f;
            items.add(new ColumnFinder.Item(x, y, x + 0.05f, y + 0.18f, y + 0.15f, true, 1));
        }
        assertTimeoutPreemptively(BOX, () -> ColumnFinder.find(items, 0, 612, 792, 10));
    }

    @Test
    void columnSeedsOnTwoDenseColumnsStayCheap() {
        List<ColumnFinder.Item> items = new ArrayList<>();
        for (int i = 0; i < 1250; i++) {
            float y = 20 + i * 0.6f;
            items.add(new ColumnFinder.Item(20, y, 290, y + 0.5f, y + 0.4f, true, 60));
            items.add(new ColumnFinder.Item(320, y, 590, y + 0.5f, y + 0.4f, true, 60));
        }
        assertTimeoutPreemptively(BOX, () -> ColumnFinder.find(items, 20, 590, 792, 0.5f));
    }

    @Test
    void tooManyTextItemsReadAsOneColumn() {
        List<ColumnFinder.Item> items = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            float x = (i % 2) * 300f + 20;
            float y = (i / 2) * 0.007f;
            items.add(new ColumnFinder.Item(x, y, x + 250, y + 0.006f, y + 0.005f, true, 40));
        }
        List<ColumnFinder.Band> bands = assertTimeoutPreemptively(BOX, () -> ColumnFinder.find(items, 20, 590, 792, 10));
        assertEquals(1, bands.size());
        assertEquals(1, bands.getFirst().columns().size());
    }

    @Test
    void manyFillsBesideManyLinesStayCheap() {
        List<Line> lines = new ArrayList<>();
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            float y = 10 + i * 0.25f;
            lines.add(line("beside", 20, y, 0.2f));
            fills.add(new Fill(300, y - 10, 360, y + 20, 0xCCDDEE));
        }
        TextBoxFinder finder = new TextBoxFinder(new ParagraphBuilder(new DocStats()));
        assertTimeoutPreemptively(BOX, () -> finder.find(fills, new ArrayList<>(), lines, 0, 612));
    }

    @Test
    void underlinesAndHighlightsIndexTheRowsTheyMeet() {
        List<Line> lines = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < 40_000; i++) {
            float y = 10 + i * 0.0175f;
            lines.add(line("ab", 20 + (i % 50) * 10, y, 0.015f));
            rules.add(new Rule(true, y + 0.5f, 0, 5, 0.01f, 0));
            rules.add(new Rule(false, 600 - (i % 7), 0, 792, 0.5f, 0));
            fills.add(new Fill(590, y, 600, y + 0.015f, 0xFFFF00));
        }
        assertTimeoutPreemptively(FAST, () -> {
            Decorations.underlines(lines, rules);
            Decorations.highlights(lines, fills);
        });
    }

    @Test
    void anUnderlineStillMarksItsWords() {
        List<Line> lines = new ArrayList<>(List.of(line("under", 100, 200, 10)));
        List<Rule> rules = new ArrayList<>(List.of(new Rule(true, 201, 100, 125, 0.5f, 0)));
        Decorations.underlines(lines, rules);
        assertTrue(rules.isEmpty());
        assertTrue(lines.getFirst().words.getFirst().glyphs.getFirst().underline);
    }

    @Test
    void longPunctuationInARunningLineIsCheap() {
        Line l = line("a" + "!".repeat(60_000) + "a", 20, 20, 10);
        assertTimeoutPreemptively(FAST, () -> PageNumbers.in(l));
        assertEquals(List.of(12), PageNumbers.in(line("-12-", 20, 20, 10)));
    }

    @Test
    void aLongRowOfNumbersBesideAFigureDoesNotOverflow() {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            row.append(i).append(' ');
        }
        Line numbers = line(row.toString().strip(), 20, 300, 5);
        Box figure = new Box(20, 100, 400, 290);
        assertTimeoutPreemptively(BOX, () -> FigureLabels.grow(List.of(figure), List.of(FigureLabels.of(numbers)), 10));
    }

    @Test
    void crowdedRunningZonesAreSkippedQuickly() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            lines.add(line("7", (i % 30) * 20f, 1 + (i / 30) * 0.03f, 0.02f));
        }
        DocStats stats = new DocStats();
        PageData page = page(List.of());
        assertTimeoutPreemptively(FAST, () -> stats.add(page, lines));
    }

    @Test
    void anInterruptStopsLayoutWithACheckedException() {
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            glyphs.add(glyph("x", 72 + i * 6, 100, 10));
        }
        PageData page = page(glyphs);
        PageAnalyzer analyzer = new PageAnalyzer(new DocStats(), true);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> analyzer.analyze(page));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
            assertThrows(InterruptedIOException.class, () -> new DocStats().add(page, LineBuilder.build(glyphs)));
            assertThrows(Polls.Stopped.class, () -> ColumnFinder.find(List.of(
                    new ColumnFinder.Item(20, 10, 100, 20, 18, true, 10)), 0, 612, 792, 10));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptLandsWhileAHeavyPageIsLaidOut() throws Exception {
        Random random = new Random(7);
        List<Glyph> glyphs = new ArrayList<>();
        for (int i = 0; i < 80_000; i++) {
            glyphs.add(glyph(String.valueOf((char) ('a' + i % 26)), random.nextFloat() * 600, random.nextFloat() * 780, 2));
        }
        List<Rule> rules = new ArrayList<>();
        List<Fill> fills = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            float x = random.nextFloat() * 560;
            float y = random.nextFloat() * 760;
            rules.add(new Rule(i % 2 == 0, i % 2 == 0 ? y : x, i % 2 == 0 ? x : y, (i % 2 == 0 ? x : y) + 30, 0.5f, 0));
            fills.add(new Fill(x, y, x + 40, y + 20, 0x88AACC));
        }
        PageData page = new PageData(0, 612, 792, 0, glyphs, List.of(), List.of(),
                new PageGraphics(rules, new InterruptInsideAStage<>(fills), List.of(), List.of()), List.of());
        PageAnalyzer analyzer = new PageAnalyzer(new DocStats(), true);
        AtomicReference<Throwable> ended = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().stackSize(8L << 20).start(() -> {
            try {
                analyzer.analyze(page);
            } catch (Throwable e) {
                ended.set(e);
            }
        });
        worker.join(Duration.ofSeconds(30));
        StringBuilder where = new StringBuilder();
        for (StackTraceElement e : worker.getStackTrace()) {
            where.append(" at ").append(e);
        }
        assertFalse(worker.isAlive(), "layout ignored the interrupt" + where);
        assertTrue(ended.get() instanceof InterruptedIOException, String.valueOf(ended.get()));
    }

    private static final class InterruptInsideAStage<T> extends AbstractList<T> {

        private final List<T> items;
        private boolean interrupted;

        InterruptInsideAStage(List<T> items) {
            this.items = items;
        }

        @Override
        public T get(int index) {
            if (!interrupted && insideStage()) {
                interrupted = true;
                Thread.currentThread().interrupt();
            }
            return items.get(index);
        }

        @Override
        public int size() {
            return items.size();
        }
    }

    private static boolean insideStage() {
        return StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                .anyMatch(c -> (c.contains(".layout.") || c.contains(".table.")) && !c.endsWith(".PageAnalyzer")
                        && !c.endsWith(".Polls") && !c.contains("LayoutBudgetTest")));
    }

    private static PageData page(List<Glyph> glyphs) {
        return new PageData(0, 612, 792, 0, glyphs, List.of(), List.of(),
                new PageGraphics(List.of(), List.of(), List.of(), List.of()), List.of());
    }

    private static Glyph glyph(String text, float x, float baseline, float size) {
        return new Glyph(text, x, size * 0.5f, baseline, size, size * 0.7f, size * 0.2f, TIMES, 0, 0, size * 0.25f,
                false, false);
    }

    private static Line line(String text, float x, float baseline, float size) {
        List<Glyph> glyphs = new ArrayList<>(text.length());
        float at = x;
        for (int i = 0; i < text.length(); i++) {
            glyphs.add(glyph(String.valueOf(text.charAt(i)), at, baseline, size));
            at += size * 0.5f;
        }
        List<Word> words = new ArrayList<>();
        int from = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                if (i > from) {
                    words.add(new Word(new ArrayList<>(glyphs.subList(from, i))));
                }
                from = i + 1;
            }
        }
        return new Line(words, new byte[words.size()]);
    }
}
