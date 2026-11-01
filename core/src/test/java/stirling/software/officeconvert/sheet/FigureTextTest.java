package stirling.software.officeconvert.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;

class FigureTextTest {

    private static final FontInfo FONT = new FontInfo("Arial", false, false, false, false, false, "Arial", false);

    @Test
    void wordsInsideTwoOverlappingFiguresAreTakenOnce() {
        List<Line> segments = List.of(line(100, "Shared", "words"));
        Set<Word> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        Box box = new Box(0, 80, 400, 120);
        assertEquals(1, FigureText.lines(box, segments, Set.of(), claimed).size());
        assertEquals(0, FigureText.lines(box, segments, Set.of(), claimed).size());
    }

    @Test
    void aCaptionWordBrokenAcrossLinesStaysInOneCell() {
        List<ParaDraft> paras = PageItems.captions(List.of(line(100, "A", "transpar-"), line(112, "ent", "film"),
                line(160, "Next", "caption")));
        assertEquals(2, paras.size());
        assertEquals("A transparent film", CellText.of(List.of(paras.getFirst()), true).text());
    }

    @Test
    void aBrokenWordInTheRightColumnDoesNotJoinTheLeftColumnBelow() {
        Line left = line(100, "Left", "text");
        Line right = line(100, "inter-");
        Line above = joined(left, shifted(right, 200));
        Line below = joined(line(112, "national", "words"), shifted(line(112, "more"), 200));
        List<ParaDraft> paras = PageItems.captions(List.of(above, below));
        assertEquals(2, paras.size());
    }

    private static Line shifted(Line l, float dx) {
        List<Word> out = new ArrayList<>();
        for (Word w : l.words) {
            List<Glyph> glyphs = new ArrayList<>();
            for (Glyph g : w.glyphs) {
                glyphs.add(new Glyph(g.text, g.x + dx, g.width, g.baseline, g.size, g.ascent, g.descent, FONT, 0, g.seq,
                        3, false, false));
            }
            out.add(new Word(glyphs));
        }
        return new Line(out, l.gaps.clone());
    }

    private static Line joined(Line a, Line b) {
        List<Word> words = new ArrayList<>(a.words);
        words.addAll(b.words);
        byte[] gaps = new byte[words.size()];
        java.util.Arrays.fill(gaps, Line.SPACE);
        gaps[a.words.size()] = Line.TAB;
        return new Line(words, gaps);
    }

    private static Line line(float baseline, String... words) {
        List<Word> out = new ArrayList<>();
        float x = 72;
        for (String w : words) {
            List<Glyph> glyphs = new ArrayList<>();
            for (int i = 0; i < w.length(); i++) {
                glyphs.add(new Glyph(String.valueOf(w.charAt(i)), x, 5, baseline, 10, 7.5f, 2.5f, FONT, 0, i, 3,
                        false, false));
                x += 5;
            }
            out.add(new Word(glyphs));
            x += 5;
        }
        byte[] gaps = new byte[out.size()];
        java.util.Arrays.fill(gaps, Line.SPACE);
        return new Line(out, gaps);
    }
}
