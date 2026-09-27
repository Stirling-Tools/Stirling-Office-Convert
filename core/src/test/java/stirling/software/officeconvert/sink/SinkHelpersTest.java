package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;

class SinkHelpersTest {

    @Test
    void spillBufferResolvesPlaceholdersAcrossTheSpill() throws IOException {
        StringWriter out = new StringWriter();
        try (SpillBuffer b = new SpillBuffer()) {
            b.append("head ").placeholder("A");
            String big = "x".repeat(1 << 20);
            b.append(big).placeholder("B").append(" tail");
            b.copyTo(out, Map.of("A", "[a]", "B", "[b]")::get);
            assertTrue(b.isEmpty());
        }
        String s = out.toString();
        assertTrue(s.startsWith("head [a]x"));
        assertTrue(s.endsWith("x[b] tail"));
        assertEquals(("head [a]" + "[b] tail").length() + (1 << 20), s.length());
    }

    @Test
    void listLabelsCountLikeWord() {
        Numbering n = new Numbering();
        Numbering.Instance i = n.create();
        i.definition.levels[0] = new Numbering.Level("upperRoman", "%1.", null, 36, 18);
        i.definition.levels[1] = new Numbering.Level("lowerLetter", "(%2)", null, 72, 18);
        i.starts[0] = 4;
        ListLabels labels = new ListLabels(n);
        assertEquals("IV.", labels.next(1, 0));
        assertEquals("(a)", labels.next(1, 1));
        assertEquals("(b)", labels.next(1, 1));
        assertEquals("V.", labels.next(1, 0));
        assertEquals("(a)", labels.next(1, 1), "a new item restarts the level below");
        assertEquals("", labels.next(7, 0));
        assertEquals("aa", ListLabels.format("lowerLetter", 27));
        assertEquals("xiv", ListLabels.format("lowerRoman", 14));
    }

    @Test
    void noteHoldReleasesBlocksOnceTheirNotesArrive() {
        NoteHold hold = new NoteHold();
        Paragraph ref = new Paragraph();
        ref.inlines.add(new Inline.FootnoteRef(5, "5", false, null));
        Paragraph after = new Paragraph();
        assertEquals(List.of(), hold.block(ref));
        assertEquals(List.of(), hold.block(after), "order is kept: nothing overtakes the held block");
        List<Block> ready = hold.note(5, List.of(new Paragraph()));
        assertEquals(List.of(ref, after), ready);
        assertEquals(1, hold.take(5).size());
        assertNull(hold.take(5));
        Paragraph dangling = new Paragraph();
        dangling.inlines.add(new Inline.FootnoteRef(9, "9", false, null));
        hold.block(dangling);
        assertEquals(List.of(dangling), hold.drain());
    }
}
