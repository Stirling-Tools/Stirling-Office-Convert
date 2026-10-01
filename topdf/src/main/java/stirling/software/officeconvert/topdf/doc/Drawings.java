package stirling.software.officeconvert.topdf.doc;

import java.awt.Dimension;
import java.io.IOException;

import org.apache.poi.hwpf.model.FSPA;
import org.apache.poi.hwpf.model.FSPADocumentPart;
import org.apache.poi.hwpf.model.FSPATable;
import org.apache.poi.hwpf.usermodel.CharacterProperties;
import org.apache.poi.hwpf.usermodel.OfficeDrawing;
import org.apache.poi.hwpf.usermodel.OfficeDrawings;
import org.apache.poi.hwpf.usermodel.Picture;

import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class Drawings {

    static final long EMU_PER_TWIP = 635;

    static final int MAX_PICTURES = 20_000;

    private final Conv c;

    private final byte[] data;

    private FSPATable mainShapes;

    private FSPATable headerShapes;

    private int pictures;

    Drawings(Conv c) {
        this.c = c;
        byte[] d;
        try {
            d = c.src.doc.getDataStream();
        } catch (RuntimeException e) {
            d = null;
        }
        this.data = d == null ? new byte[0] : d;
    }

    String inline(CharacterProperties chp, Rels rels) throws IOException {
        int fc = chp.getFcPic();
        if (fc < 0 || fc + 4 > data.length || pictures >= MAX_PICTURES) {
            return null;
        }
        Picture pic;
        byte[] content;
        try {
            pic = new Picture(fc, data, true);
            content = pic.getContent();
        } catch (RuntimeException e) {
            c.lost = true;
            return null;
        }
        String name = c.media.add(content);
        if (name == null) {
            return null;
        }
        pictures++;
        long goalW = pic.getDxaGoal();
        long goalH = pic.getDyaGoal();
        if (goalW <= 0 || goalH <= 0) {
            Dimension px = pixels(content);
            if (px == null) {
                return null;
            }
            goalW = px.width * 15L;
            goalH = px.height * 15L;
        }
        int mx = pic.getHorizontalScalingFactor() <= 0 ? 1000 : pic.getHorizontalScalingFactor();
        int my = pic.getVerticalScalingFactor() <= 0 ? 1000 : pic.getVerticalScalingFactor();
        long w = Math.max(1, (goalW - pic.getDxaCropLeft() - pic.getDxaCropRight()) * mx / 1000);
        long h = Math.max(1, (goalH - pic.getDyaCropTop() - pic.getDyaCropBottom()) * my / 1000);
        String crop = crop(pic.getDxaCropLeft() / (double) goalW, pic.getDyaCropTop() / (double) goalH,
                pic.getDxaCropRight() / (double) goalW, pic.getDyaCropBottom() / (double) goalH);
        int id = c.nextId();
        String rid = rels.image(name);
        long cx = w * EMU_PER_TWIP;
        long cy = h * EMU_PER_TWIP;
        return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + cx
                + "\" cy=\"" + cy + "\"/><wp:docPr id=\"" + id + "\" name=\"Picture " + id + "\"/>"
                + graphic(id, rid, cx, cy, crop) + "</wp:inline></w:drawing>";
    }

    static String graphic(int id, String rid, long cx, long cy, String crop) {
        return "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"Picture " + id + "\"/><pic:cNvPicPr/>"
                + "</pic:nvPicPr><pic:blipFill><a:blip r:embed=\"" + rid + "\"/>" + crop
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/>"
                + "</a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>";
    }

    private static String crop(double l, double t, double r, double b) {
        if (l == 0 && t == 0 && r == 0 && b == 0 || !Double.isFinite(l + t + r + b)) {
            return "";
        }
        return "<a:srcRect l=\"" + pct(l) + "\" t=\"" + pct(t) + "\" r=\"" + pct(r) + "\" b=\"" + pct(b) + "\"/>";
    }

    private static int pct(double v) {
        return (int) Math.round(Math.max(-1, Math.min(1, v)) * 100000);
    }

    private static Dimension pixels(byte[] content) {
        try {
            return PictureDecoder.pixelSize(content);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    String anchor(Story story, int cp) throws IOException {
        boolean header = story.kind == Story.Kind.HEADER;
        if (story.kind != Story.Kind.MAIN && !header || pictures >= MAX_PICTURES) {
            return null;
        }
        int rel = header ? cp - story.base : cp;
        OfficeDrawing d;
        FSPA fspa;
        try {
            OfficeDrawings all = header ? c.src.doc.getOfficeDrawingsHeaders() : c.src.doc.getOfficeDrawingsMain();
            d = all.getOfficeDrawingAt(rel);
            fspa = shapes(header).getFspaFromCp(rel);
        } catch (RuntimeException e) {
            return null;
        }
        if (d == null) {
            return null;
        }
        byte[] picture;
        try {
            picture = d.getPictureData();
        } catch (RuntimeException e) {
            picture = null;
        }
        if (picture == null) {
            return Shapes.anchor(c, story, d, fspa);
        }
        String name = c.media.add(picture);
        if (name == null) {
            return null;
        }
        pictures++;
        int id = c.nextId();
        String rid = story.rels.image(name);
        long cx = Math.max(1, (long) d.getRectangleRight() - d.getRectangleLeft()) * EMU_PER_TWIP;
        long cy = Math.max(1, (long) d.getRectangleBottom() - d.getRectangleTop()) * EMU_PER_TWIP;
        int z = c.layer(d.getShapeId(), id, header);
        return "<w:drawing>" + Shapes.open(d, Shapes.container(d), fspa, id, z, cx, cy, 0, 0)
                + graphic(id, rid, cx, cy, "")
                + "</wp:anchor></w:drawing>";
    }

    private FSPATable shapes(boolean header) {
        if (header) {
            if (headerShapes == null) {
                headerShapes = new FSPATable(c.src.doc.getTableStream(), c.src.doc.getFileInformationBlock(),
                        FSPADocumentPart.HEADER);
            }
            return headerShapes;
        }
        if (mainShapes == null) {
            mainShapes = new FSPATable(c.src.doc.getTableStream(), c.src.doc.getFileInformationBlock(),
                    FSPADocumentPart.MAIN);
        }
        return mainShapes;
    }
}
