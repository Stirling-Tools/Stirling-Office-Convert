package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.xslf.usermodel.XSLFDiagram;
import org.apache.poi.xslf.usermodel.XSLFDiagramDrawing;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.presentationml.x2006.main.CTApplicationNonVisualDrawingProps;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShapeNonVisual;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShapeNonVisual;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.microsoft.schemas.office.drawing.x2008.diagram.CTDrawing;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class DiagramDrawings {

    private static final String DSP = "http://schemas.microsoft.com/office/drawing/2008/diagram";

    record Found(XSLFGroupShape group, String part) {}

    private DiagramDrawings() {}

    static Found find(Deck deck, XSLFDiagram frame, Space space) throws IOException {
        try {
            return read(deck, frame, space);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Found read(Deck deck, XSLFDiagram frame, Space space) throws IOException {
        String[] ids = SmartArtLayout.relIds(frame);
        if (ids == null || frame.getAnchor() == null) {
            return null;
        }
        Relationships rels = deck.job().zip().relationships(space.relsPart());
        Relationship dm = rels.get(ids[0]);
        if (dm == null || !ActiveContent.mayFollow(dm)) {
            return null;
        }
        Document data = deck.job().zip().xml(dm);
        NodeList ext = data.getElementsByTagNameNS(DSP, "dataModelExt");
        if (ext.getLength() == 0) {
            return null;
        }
        Relationship dr = rels.get(((Element) ext.item(0)).getAttribute("relId").strip());
        if (dr == null || !ActiveContent.mayFollow(dr)) {
            return null;
        }
        POIXMLDocumentPart part = frame.getSheet().getRelationById(dr.id());
        if (!(part instanceof XSLFDiagramDrawing drawing) || drawing.getDrawingDocument() == null) {
            return null;
        }
        CTDrawing d = drawing.getDrawingDocument().getDrawing();
        if (d == null || d.getSpTree() == null || d.getSpTree().getSpList().isEmpty()) {
            return null;
        }
        DiagramTextFills fills = DiagramTextFills.read(deck.job().zip(), rels, data, ids[2]);
        return new Found(group(frame, d.getSpTree(), fills), drawing.getPackagePart().getPartName().getName());
    }

    private static XSLFGroupShape group(XSLFDiagram frame,
            com.microsoft.schemas.office.drawing.x2008.diagram.CTGroupShape tree, DiagramTextFills fills) {
        CTGroupShape g = CTGroupShape.Factory.newInstance();
        g.addNewGrpSpPr();
        CTGroupShapeNonVisual nv = g.addNewNvGrpSpPr();
        nv.setCNvPr(tree.getNvGrpSpPr().getCNvPr());
        nv.setCNvGrpSpPr(tree.getNvGrpSpPr().getCNvGrpSpPr());
        nv.setNvPr(CTApplicationNonVisualDrawingProps.Factory.newInstance());
        for (com.microsoft.schemas.office.drawing.x2008.diagram.CTShape s : tree.getSpList()) {
            g.getSpList().addAll(shapes(s, fills));
        }
        Group shape = new Group(g, frame.getSheet());
        Rectangle2D a = frame.getAnchor();
        shape.setAnchor(a);
        shape.setInteriorAnchor(new Rectangle2D.Double(0, 0, a.getWidth(), a.getHeight()));
        shape.setRotation(frame.getRotation());
        return shape;
    }

    private static List<CTShape> shapes(com.microsoft.schemas.office.drawing.x2008.diagram.CTShape s,
            DiagramTextFills fills) {
        List<CTShape> out = new ArrayList<>();
        CTShape sp = CTShape.Factory.newInstance();
        sp.setStyle(s.getStyle());
        fills.apply(modelId(s), sp.getStyle());
        sp.setSpPr(s.getSpPr());
        CTShapeNonVisual nv = sp.addNewNvSpPr();
        nv.setCNvPr(s.getNvSpPr().getCNvPr());
        nv.setCNvSpPr(s.getNvSpPr().getCNvSpPr());
        nv.setNvPr(CTApplicationNonVisualDrawingProps.Factory.newInstance());
        out.add(sp);
        if (hasText(s)) {
            CTShape tx = CTShape.Factory.newInstance();
            tx.addNewSpPr();
            tx.setTxBody(s.getTxBody());
            tx.setStyle(sp.getStyle());
            tx.setNvSpPr((CTShapeNonVisual) nv.copy());
            tx.getNvSpPr().getCNvSpPr().setTxBox(true);
            CTTransform2D t = s.getTxXfrm();
            tx.getSpPr().setXfrm(t);
            int rot = t.getRot();
            if (rot != 0 && s.getSpPr().getXfrm() != null) {
                tx.getSpPr().getXfrm().setRot(s.getSpPr().getXfrm().getRot() + rot);
            }
            out.add(tx);
        }
        return out;
    }

    private static String modelId(com.microsoft.schemas.office.drawing.x2008.diagram.CTShape s) {
        return s.getDomNode() instanceof Element e ? e.getAttribute("modelId") : null;
    }

    private static boolean hasText(com.microsoft.schemas.office.drawing.x2008.diagram.CTShape s) {
        return s.getTxBody() != null && s.getTxXfrm() != null && s.getTxBody().getPList().stream()
                .flatMap(p -> p.getRList().stream())
                .anyMatch(r -> r.getT() != null && !r.getT().isBlank());
    }

    private static final class Group extends XSLFGroupShape {

        Group(CTGroupShape shape, XSLFSheet sheet) {
            super(shape, sheet);
        }
    }
}
