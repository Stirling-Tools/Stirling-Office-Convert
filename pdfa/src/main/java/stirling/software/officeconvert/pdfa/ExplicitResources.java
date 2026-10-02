package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;

final class ExplicitResources {

    private ExplicitResources() {}

    static void run(ContentGraph graph) {
        for (ContentGraph.Node n : graph.nodes()) {
            if (n.resources() == null || n.kind() == ContentGraph.Kind.GLYPH
                    || n.owner().getDictionaryObject(COSName.RESOURCES) != null) {
                continue;
            }
            n.owner().setItem(COSName.RESOURCES, n.kind() == ContentGraph.Kind.PAGE ? n.resources()
                    : subset(n.resources(), used(n.streams()), n.owner()));
        }
        for (Map.Entry<COSDictionary, COSDictionary> e : graph.type3Fonts().entrySet()) {
            COSDictionary font = e.getKey();
            if (e.getValue() == null || font.getDictionaryObject(COSName.RESOURCES) != null) {
                continue;
            }
            List<COSStream> glyphs = new ArrayList<>();
            COSDictionary procs = ContentGraph.dict(font.getDictionaryObject(COSName.CHAR_PROCS));
            if (procs != null) {
                for (COSBase v : procs.getValues()) {
                    COSBase s = v instanceof COSObject o ? o.getObject() : v;
                    if (s instanceof COSStream stream) {
                        glyphs.add(stream);
                    }
                }
            }
            font.setItem(COSName.RESOURCES, subset(e.getValue(), used(glyphs), font));
        }
    }

    private static Set<COSName> used(List<COSStream> streams) {
        Set<COSName> names = new HashSet<>();
        try {
            for (Object t : ContentTokens.parse(streams)) {
                if (t instanceof COSName n) {
                    names.add(n);
                } else if (t instanceof Operator op && op.getImageParameters() != null
                        && op.getImageParameters().getDictionaryObject(COSName.CS) instanceof COSName cs) {
                    names.add(cs);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return names;
    }

    private static COSDictionary subset(COSDictionary res, Set<COSName> used, COSDictionary self) {
        COSDictionary out = new COSDictionary();
        for (COSName kind : res.keySet()) {
            COSBase v = res.getDictionaryObject(kind);
            if (v instanceof COSDictionary d && !(v instanceof COSStream)) {
                COSDictionary part = new COSDictionary();
                for (COSName k : d.keySet()) {
                    if ((used == null || used.contains(k)) && d.getDictionaryObject(k) != self) {
                        part.setItem(k, d.getItem(k));
                    }
                }
                if (part.size() > 0) {
                    out.setItem(kind, part);
                }
            } else if (v != null) {
                out.setItem(kind, res.getItem(kind));
            }
        }
        return out;
    }
}
