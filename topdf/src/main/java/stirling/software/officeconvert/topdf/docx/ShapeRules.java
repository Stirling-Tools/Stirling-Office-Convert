package stirling.software.officeconvert.topdf.docx;

final class ShapeRules {

    private ShapeRules() {}

    // A VML horizontal rule (o:hr) is as wide as its percentage of the line it sits in
    static void fit(ParaItems pi, float available) {
        for (Item it : pi.items) {
            Drawing d = it.drawing;
            if (it.kind == Item.Kind.OBJECT && d != null && d.rulePct > 0) {
                d.width = Math.max(1, available * d.rulePct - d.effL - d.effR);
                it.objectWidth = d.width + d.effL + d.effR;
            }
        }
    }
}
