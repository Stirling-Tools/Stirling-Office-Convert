package stirling.software.officeconvert.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

class PosterBudgetTest {

    @Test
    void manyTinyMarksAreMeasuredQuickly() {
        List<VectorMark> marks = new ArrayList<>();
        for (int i = 0; i < 2_000_000; i++) {
            float x = (i % 1000) * 0.6f;
            float top = (i / 1000) * 0.39f;
            marks.add(mark(x, top, x + 0.1f, top + 0.1f));
        }
        assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> PageAnalyzer.poster(page(marks))));
    }

    @Test
    void graphicsOverMostOfThePageMakeAPoster() {
        assertTrue(PageAnalyzer.poster(page(List.of(mark(0, 0, 300, 792), mark(300, 0, 612, 300)))));
        assertFalse(PageAnalyzer.poster(page(List.of(mark(0, 0, 200, 792), mark(400, 0, 612, 300)))));
    }

    @Test
    void aFullPageBackgroundDoesNotCount() {
        assertFalse(PageAnalyzer.poster(page(List.of(mark(0, 0, 612, 792), mark(0, 0, 100, 100)))));
    }

    private static VectorMark mark(float x, float top, float right, float bottom) {
        return new VectorMark(x, top, right, bottom, 1, false, false, true, false, 0, false, 0, 0, false);
    }

    private static PageData page(List<VectorMark> marks) {
        return new PageData(0, 612, 792, 0, List.of(), List.of(), List.of(),
                new PageGraphics(List.of(), List.of(), List.of(), marks), List.of());
    }
}
