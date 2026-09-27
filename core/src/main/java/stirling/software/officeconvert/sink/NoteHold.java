package stirling.software.officeconvert.sink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

public final class NoteHold {

    private final Map<Integer, List<Paragraph>> notes = new HashMap<>();
    private final ArrayDeque<Block> waiting = new ArrayDeque<>();

    public List<Block> block(Block b) {
        waiting.add(b);
        return release();
    }

    public List<Block> note(int id, List<Paragraph> paragraphs) {
        notes.put(id, paragraphs);
        return release();
    }

    public List<Paragraph> take(int id) {
        return notes.remove(id);
    }

    public List<Block> drain() {
        List<Block> out = new ArrayList<>(waiting);
        waiting.clear();
        return out;
    }

    private List<Block> release() {
        List<Block> out = new ArrayList<>();
        while (!waiting.isEmpty() && !refersToMissing(waiting.peek(), id -> !notes.containsKey(id))) {
            out.add(waiting.poll());
        }
        return out;
    }

    private static boolean refersToMissing(Block b, IntPredicate missing) {
        if (b instanceof Table t) {
            for (Table.Row row : t.rows) {
                for (Table.Cell cell : row.cells) {
                    for (Paragraph p : cell.paragraphs) {
                        if (refersToMissing(p, missing)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        for (Inline in : ((Paragraph) b).inlines) {
            if (in instanceof Inline.FootnoteRef ref && missing.test(ref.id())) {
                return true;
            }
            if (in instanceof Inline.TextBox box) {
                for (Paragraph p : box.paragraphs()) {
                    if (refersToMissing(p, missing)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
