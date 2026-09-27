package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.StyleSheet;

final class NoteWriter {

    private final DocSink sink;
    private final ParagraphFactory paragraphs;
    private final RunBuilder runs;
    private final StyleSheet styles;
    private final List<PageLayout.Note> pending = new ArrayList<>();
    private final Map<Integer, Boolean> custom = new HashMap<>();
    private List<Paragraph> held;
    private int heldId;
    private int nextAuto = 1;

    NoteWriter(DocSink sink, ParagraphFactory paragraphs, RunBuilder runs, StyleSheet styles) {
        this.sink = sink;
        this.paragraphs = paragraphs;
        this.runs = runs;
        this.styles = styles;
    }

    List<ParaDraft> startPage(PageLayout layout) {
        List<ParaDraft> strayLines = List.of();
        if (held != null) {
            for (ParaDraft d : layout.noteContinuation()) {
                held.add(paragraphs.notePara(d));
            }
            sink.footnote(heldId, held);
            held = null;
        } else {
            strayLines = layout.noteContinuation();
        }
        for (PageLayout.Note note : layout.notes()) {
            boolean own = !note.marker().equals(String.valueOf(nextAuto));
            if (!own) {
                nextAuto++;
            }
            runs.registerNote(note.id(), note.marker(), own);
            pending.add(note);
            custom.put(note.id(), own);
        }
        return strayLines;
    }

    void endPage() {
        for (int ni = 0; ni < pending.size(); ni++) {
            PageLayout.Note note = pending.get(ni);
            List<Paragraph> paras = new ArrayList<>();
            for (ParaDraft d : note.paras()) {
                paras.add(paragraphs.notePara(d));
            }
            if (paras.isEmpty()) {
                paras.add(new Paragraph());
            }
            Paragraph first = paras.getFirst();
            RunStyle markStyle = first.inlines.isEmpty() || !(first.inlines.getFirst() instanceof Inline.Text t)
                    ? styles.normal.withVertAlign(1) : t.style().withVertAlign(1);
            first.inlines.addFirst(new Inline.FootnoteMark(note.marker(), custom.getOrDefault(note.id(), true), markStyle));
            if (ni == pending.size() - 1) {
                held = paras;
                heldId = note.id();
            } else {
                sink.footnote(note.id(), paras);
            }
        }
        pending.clear();
    }

    void finish() {
        if (held != null) {
            sink.footnote(heldId, held);
            held = null;
        }
    }
}
