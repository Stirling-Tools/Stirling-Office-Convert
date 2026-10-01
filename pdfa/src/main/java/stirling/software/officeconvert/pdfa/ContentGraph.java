package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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

    record Node(Kind kind, int page, COSDictionary owner, List<COSStream> streams, COSDictionary resources) {}

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

    private void form(COSStream s, int page, COSDictionary parentRes, Kind kind) throws IOException {
        if (nodes.size() >= MAX_NODES || !seenStreams.add(s)) {
            return;
        }
        COSDictionary res = dict(s.getDictionaryObject(COSName.RESOURCES));
        if (res == null) {
            res = parentRes;
        }
        nodes.add(new Node(kind, page, s, List.of(s), res));
        walkResources(res, page);
    }

    private void walkResources(COSDictionary res, int page) throws IOException {
        if (res == null || !resources.add(res)) {
            return;
        }
        PdfFiles.stopIfInterrupted();
        COSDictionary xobjects = dict(res.getDictionaryObject(COSName.XOBJECT));
        if (xobjects != null) {
            for (COSName name : xobjects.keySet()) {
                if (xobjects.getDictionaryObject(name) instanceof COSStream s && COSName.FORM.equals(s.getCOSName(COSName.SUBTYPE))) {
                    form(s, page, res, Kind.FORM);
                }
            }
        }
        COSDictionary patterns = dict(res.getDictionaryObject(COSName.PATTERN));
        if (patterns != null) {
            for (COSName name : patterns.keySet()) {
                if (patterns.getDictionaryObject(name) instanceof COSStream s && s.getInt(COSName.PATTERN_TYPE) == 1) {
                    form(s, page, res, Kind.PATTERN);
                }
            }
        }
        COSDictionary gstates = dict(res.getDictionaryObject(COSName.EXT_G_STATE));
        if (gstates != null) {
            for (COSName name : gstates.keySet()) {
                COSDictionary gs = dict(gstates.getDictionaryObject(name));
                COSDictionary smask = gs == null ? null : dict(gs.getDictionaryObject(COSName.SMASK));
                if (smask != null && smask.getDictionaryObject(COSName.G) instanceof COSStream g) {
                    form(g, page, res, Kind.FORM);
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
                    if (procs.getDictionaryObject(glyph) instanceof COSStream s && nodes.size() < MAX_NODES
                            && seenStreams.add(s)) {
                        COSDictionary r = fres == null ? res : fres;
                        nodes.add(new Node(Kind.GLYPH, page, s, List.of(s), r));
                        walkResources(r, page);
                    }
                }
            }
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
