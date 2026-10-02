package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import stirling.software.officeconvert.extract.PdfFiles;

final class ContentGraph {

    static final int MAX_NODES = 200_000;

    enum Kind {
        PAGE,
        FORM,
        PATTERN,
        GLYPH,
        APPEARANCE
    }

    record Node(Kind kind, int page, COSDictionary owner, List<COSStream> streams, COSDictionary resources) {

        @Override
        public List<COSStream> streams() {
            return kind == Kind.PAGE ? contents(owner) : streams;
        }
    }

    private final List<Node> nodes = new ArrayList<>();

    private final Set<COSBase> seenStreams = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<COSDictionary> resources = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<COSDictionary, COSDictionary> type3 = new IdentityHashMap<>();

    private ContentGraph() {}

    static ContentGraph of(PDDocument doc) throws IOException {
        ContentGraph g = new ContentGraph();
        int index = 0;
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            COSDictionary res = dict(page.getCOSObject().getDictionaryObject(COSName.RESOURCES));
            if (res == null && page.getResources() != null) {
                res = page.getResources().getCOSObject();
            }
            g.nodes.add(new Node(Kind.PAGE, index, page.getCOSObject(), contents(page.getCOSObject()), res));
            g.walkResources(res, index);
            COSArray annots = array(page.getCOSObject().getDictionaryObject(COSName.ANNOTS));
            if (annots != null) {
                for (int i = 0; i < annots.size(); i++) {
                    COSDictionary a = dict(annots.getObject(i));
                    if (a != null) {
                        g.appearances(a, index, res);
                    }
                }
            }
            index++;
        }
        COSDictionary acro = dict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM));
        if (acro != null) {
            COSDictionary dr = dict(acro.getDictionaryObject(COSName.DR));
            g.walkResources(dr, -1);
        }
        return g;
    }

    List<Node> nodes() {
        return nodes;
    }

    Set<COSDictionary> resources() {
        return resources;
    }

    Map<COSDictionary, COSDictionary> type3Fonts() {
        return type3;
    }

    private void appearances(COSDictionary annot, int page, COSDictionary pageRes) throws IOException {
        COSDictionary ap = dict(annot.getDictionaryObject(COSName.AP));
        if (ap == null) {
            return;
        }
        for (COSName key : List.of(COSName.N, COSName.R, COSName.D)) {
            COSBase v = ap.getDictionaryObject(key);
            if (v instanceof COSStream s) {
                form(s, page, pageRes, Kind.APPEARANCE);
            } else if (v instanceof COSDictionary states) {
                for (COSName state : states.keySet()) {
                    if (states.getDictionaryObject(state) instanceof COSStream s) {
                        form(s, page, pageRes, Kind.APPEARANCE);
                    }
                }
            }
        }
    }

    private record Step(COSStream stream, COSDictionary res, int page, Kind kind) {}

    private void form(COSStream s, int page, COSDictionary parentRes, Kind kind) throws IOException {
        walk(new Step(s, parentRes, page, kind));
    }

    private void walkResources(COSDictionary res, int page) throws IOException {
        walk(new Step(null, res, page, null));
    }

    private void walk(Step first) throws IOException {
        Deque<Step> todo = new ArrayDeque<>();
        todo.push(first);
        while (!todo.isEmpty()) {
            Step step = todo.pop();
            if (step.stream() == null) {
                expand(step.res(), step.page(), todo);
                continue;
            }
            COSStream s = step.stream();
            if (nodes.size() >= MAX_NODES || !seenStreams.add(s)) {
                continue;
            }
            COSDictionary res = step.kind() == Kind.GLYPH ? step.res() : dict(s.getDictionaryObject(COSName.RESOURCES));
            if (res == null) {
                res = step.res();
            }
            nodes.add(new Node(step.kind(), step.page(), s, List.of(s), res));
            todo.push(new Step(null, res, step.page(), null));
        }
    }

    private void expand(COSDictionary res, int page, Deque<Step> todo) throws IOException {
        if (res == null || !resources.add(res)) {
            return;
        }
        PdfFiles.stopIfInterrupted();
        List<Step> children = new ArrayList<>();
        COSDictionary xobjects = dict(res.getDictionaryObject(COSName.XOBJECT));
        if (xobjects != null) {
            for (COSName name : xobjects.keySet()) {
                if (xobjects.getDictionaryObject(name) instanceof COSStream s && COSName.FORM.equals(s.getCOSName(COSName.SUBTYPE))) {
                    children.add(new Step(s, res, page, Kind.FORM));
                }
            }
        }
        COSDictionary patterns = dict(res.getDictionaryObject(COSName.PATTERN));
        if (patterns != null) {
            for (COSName name : patterns.keySet()) {
                if (patterns.getDictionaryObject(name) instanceof COSStream s && s.getInt(COSName.PATTERN_TYPE) == 1) {
                    children.add(new Step(s, res, page, Kind.PATTERN));
                }
            }
        }
        COSDictionary gstates = dict(res.getDictionaryObject(COSName.EXT_G_STATE));
        if (gstates != null) {
            for (COSName name : gstates.keySet()) {
                COSDictionary gs = dict(gstates.getDictionaryObject(name));
                COSDictionary smask = gs == null ? null : dict(gs.getDictionaryObject(COSName.SMASK));
                if (smask != null && smask.getDictionaryObject(COSName.G) instanceof COSStream g) {
                    children.add(new Step(g, res, page, Kind.FORM));
                }
            }
        }
        COSDictionary fonts = dict(res.getDictionaryObject(COSName.FONT));
        if (fonts != null) {
            for (COSName name : fonts.keySet()) {
                COSDictionary f = dict(fonts.getDictionaryObject(name));
                if (f == null || !COSName.TYPE3.equals(f.getCOSName(COSName.SUBTYPE)) || type3.containsKey(f)) {
                    continue;
                }
                COSDictionary fres = dict(f.getDictionaryObject(COSName.RESOURCES));
                type3.put(f, fres == null ? res : fres);
                COSDictionary procs = dict(f.getDictionaryObject(COSName.CHAR_PROCS));
                if (procs == null) {
                    continue;
                }
                for (COSName glyph : procs.keySet()) {
                    if (procs.getDictionaryObject(glyph) instanceof COSStream s) {
                        children.add(new Step(s, fres == null ? res : fres, page, Kind.GLYPH));
                    }
                }
            }
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            todo.push(children.get(i));
        }
    }

    static List<COSStream> contents(COSDictionary page) {
        COSBase c = page.getDictionaryObject(COSName.CONTENTS);
        List<COSStream> out = new ArrayList<>();
        if (c instanceof COSStream s) {
            out.add(s);
        } else if (c instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.getObject(i) instanceof COSStream s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static COSDictionary dict(COSBase b) {
        if (b instanceof COSObject o) {
            b = o.getObject();
        }
        return b instanceof COSDictionary d ? d : null;
    }

    static COSArray array(COSBase b) {
        if (b instanceof COSObject o) {
            b = o.getObject();
        }
        return b instanceof COSArray a ? a : null;
    }
}
