package stirling.software.officeconvert.topdf.odf;

import java.util.HashSet;
import java.util.Set;

import org.w3c.dom.Element;

/** The drawing layers a printout leaves out: those shown on screen only, or never. */
final class Layers {

    private Layers() {}

    static Set<String> unprinted(OdfDocument doc) {
        Set<String> out = new HashSet<>();
        for (Element root : new Element[] {doc.styles(), doc.content()}) {
            Element master = Dom.kid(root, Ns.OFFICE, "master-styles");
            for (Element set : Dom.kids(master, Ns.DRAW, "layer-set")) {
                for (Element layer : Dom.kids(set, Ns.DRAW, "layer")) {
                    String display = Dom.attr(layer, Ns.DRAW, "display");
                    if ("screen".equals(display) || "none".equals(display)) {
                        out.add(Dom.attr(layer, Ns.DRAW, "name", ""));
                    }
                }
            }
        }
        return out;
    }
}
