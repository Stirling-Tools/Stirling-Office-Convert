package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTPresetGeometry2D;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.drawingml.x2006.main.STShapeType;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class PicturePainter {

    private static final String DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private final Deck deck;

    private final PdfCanvas canvas;

    private final Map<DecodedPicture, Boolean> opaque = new IdentityHashMap<>();

    PicturePainter(Deck deck, PdfCanvas canvas) {
        this.deck = deck;
        this.canvas = canvas;
    }

    boolean isEmpty(XSLFPictureShape p) {
        CTBlipFillProperties fill = blipFill((CTPicture) p.getXmlObject());
        return fill == null || fill.getBlip() == null || !fill.getBlip().isSetEmbed()
                || fill.getBlip().getEmbed().isBlank();
    }

    static CTBlipFillProperties blipFill(CTPicture pic) {
        CTBlipFillProperties own = pic.getBlipFill();
        if (own != null) {
            return own;
        }
        XmlObject[] found = pic.selectPath("declare namespace mc='" + Fallbacks.MC + "' declare namespace p='"
                + P_NS + "' ./mc:AlternateContent/mc:Fallback/p:blipFill");
        if (found.length == 0) {
            return null;
        }
        try {
            return CTBlipFillProperties.Factory.parse(found[0].getDomNode(),
                    new XmlOptions().setLoadReplaceDocumentElement(null));
        } catch (XmlException | RuntimeException e) {
            return null;
        }
    }

    private static final String P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";

    void paint(XSLFPictureShape p, Space space) throws IOException {
        Rectangle2D anchor = p.getAnchor();
        if (anchor == null) {
            return;
        }
        CTPicture pic = (CTPicture) p.getXmlObject();
        Frame f = space.place(anchor, p.getRotation(), p.getFlipHorizontal(), p.getFlipVertical())
                .viewed(Cameras.view(pic.getSpPr()));
        CTBlipFillProperties fill = blipFill(pic);
        Rectangle2D box = f.bounds();
        DecodedPicture picture = BlipFills.picture(deck, fill, space.relsPart(),
                BlipFills.duotone(fill == null ? null : fill.getBlip(), p.getSheet()), p.getSheet(),
                softEdge(pic.getSpPr(), fill, box));
        Stroke stroke = Strokes.of(p.getStrokeStyle());
        if (picture == null && stroke == null) {
            return;
        }
        Shadows.Shadow shadow = picture != null && opaque(picture, fill) ? Shadows.of(p) : null;
        if (shadow != null) {
            Shadows.draw(deck, canvas, shadow, Shadows.silhouette(Geometry.outlines(p, box), true, stroke),
                    f.shapeTransform(), box);
        }
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            if (picture != null) {
                draw(p, pic, fill, picture, box);
            }
            if (stroke != null) {
                for (Geometry.Outline o : Geometry.outlines(p, box)) {
                    if (o.stroked()) {
                        canvas.draw(o.shape(), null, stroke);
                    }
                }
            }
            if (picture != null && opaque(picture, fill)) {
                reflection(p, pic, fill, space.relsPart(), box);
            }
        } finally {
            canvas.restore();
        }
    }

    // A reflection below the picture, mirrored about its bottom edge and fading out
    private void reflection(XSLFPictureShape p, CTPicture pic, CTBlipFillProperties fill, String relsPart,
            Rectangle2D box) throws IOException {
        CTShapeProperties spPr = pic.getSpPr();
        Map<String, String> r = spPr != null && spPr.isSetEffectLst() ? reflectionOf(spPr.getEffectLst()) : null;
        if (r == null || fill.getBlip() == null || !fill.getBlip().isSetEmbed()) {
            return;
        }
        float sy = number(r, "sy", 100_000) / 100_000f;
        if (sy >= 0) {
            return;
        }
        float stA = number(r, "stA", 100_000) / 100_000f;
        float stPos = number(r, "stPos", 0) / 100_000f;
        float endA = number(r, "endA", 0) / 100_000f;
        float endPos = number(r, "endPos", 100_000) / 100_000f;
        double dist = number(r, "dist", 0) / 12_700.0;
        double dir = Math.toRadians(number(r, "dir", 5_400_000) / 60_000.0);
        Crop c = BlipFills.crop(fill.getSrcRect());
        DecodedPicture img = deck.pictures().reflection(relsPart, fill.getBlip().getEmbed(),
                new float[] {c.left(), c.top(), c.right(), c.bottom()}, stA, stPos, endA, Math.min(1, endPos));
        if (img == null || img.vector()) {
            return;
        }
        float h = (float) (box.getHeight() * Math.min(1, endPos) * -sy);
        double dx = dist * Math.cos(dir);
        double dy = dist * Math.sin(dir);
        canvas.save();
        try {
            if (!rectangular(Geometry.source(p))) {
                for (Geometry.Outline o : Geometry.outlines(p, box)) {
                    if (o.filled()) {
                        AffineTransform mirror = AffineTransform.getTranslateInstance(dx, 2 * box.getMaxY() + dy);
                        mirror.scale(1, -1);
                        canvas.clip(mirror.createTransformedShape(o.shape()));
                        break;
                    }
                }
            }
            canvas.image(img, (float) (box.getX() + dx), (float) (box.getMaxY() + dy), (float) box.getWidth(), h);
        } finally {
            canvas.restore();
        }
    }

    void paint(CTShapeProperties spPr, CTBlipFillProperties fill, Space space, Rectangle2D inherited)
            throws IOException {
        CTTransform2D xfrm = spPr == null ? null : spPr.getXfrm();
        if (xfrm == null || !xfrm.isSetOff() || !xfrm.isSetExt()) {
            if (inherited != null) {
                paint(inherited, 0, false, false, fill, space);
            }
            return;
        }
        Rectangle2D anchor = new Rectangle2D.Double(POIXMLUnits.parseLength(xfrm.getOff().xgetX()) / 12_700.0,
                POIXMLUnits.parseLength(xfrm.getOff().xgetY()) / 12_700.0, xfrm.getExt().getCx() / 12_700.0,
                xfrm.getExt().getCy() / 12_700.0);
        paint(anchor, xfrm.getRot() / 60_000.0, xfrm.getFlipH(), xfrm.getFlipV(), fill, space);
    }

    private void paint(Rectangle2D anchor, double rotation, boolean flipH, boolean flipV, CTBlipFillProperties fill,
            Space space) throws IOException {
        Frame f = space.place(anchor, rotation, flipH, flipV);
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            drawBlip(fill, space.relsPart(), f.bounds());
        } finally {
            canvas.restore();
        }
    }

    void drawBlip(CTBlipFillProperties fill, String relsPart, Rectangle2D box) throws IOException {
        DecodedPicture picture = BlipFills.picture(deck, fill, relsPart);
        if (picture == null) {
            return;
        }
        Rectangle2D r = BlipFills.stretched(fill, box);
        canvas.image(picture, (float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight(),
                BlipFills.crop(fill.getSrcRect()), 0, false, false, BlipFills.alpha(fill));
    }

    private void draw(XSLFPictureShape p, CTPicture pic, CTBlipFillProperties fill, DecodedPicture picture,
            Rectangle2D box) throws IOException {
        boolean clip = !rectangular(Geometry.source(p));
        canvas.save();
        try {
            if (clip) {
                List<Geometry.Outline> outlines = Geometry.outlines(p, box);
                for (Geometry.Outline o : outlines) {
                    if (o.filled()) {
                        canvas.clip(o.shape());
                        break;
                    }
                }
            }
            Rectangle2D r = BlipFills.stretched(fill, box);
            canvas.image(picture, (float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight(),
                    BlipFills.crop(fill.getSrcRect()), 0, false, false, BlipFills.alpha(fill));
        } finally {
            canvas.restore();
        }
    }

    // Soft edges fade the picture out towards its edges, over the radius given
    private static List<Pictures.Step> softEdge(CTShapeProperties spPr, CTBlipFillProperties fill, Rectangle2D box) {
        if (spPr == null || fill == null || !spPr.isSetEffectLst() || box.getWidth() < 1 || box.getHeight() < 1) {
            return List.of();
        }
        float rad;
        try (XmlCursor c = spPr.getEffectLst().newCursor()) {
            if (!c.toChild(DRAWINGML, "softEdge")) {
                return List.of();
            }
            String v = c.getAttributeText(new QName("", "rad"));
            rad = v == null ? 0 : Long.parseLong(v.strip()) / 12_700f;
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!(rad > 0)) {
            return List.of();
        }
        Crop crop = BlipFills.crop(fill.getSrcRect());
        float[] cut = {crop.left(), crop.top(), crop.right(), crop.bottom()};
        float rx = (float) Math.min(0.5, rad / box.getWidth());
        float ry = (float) Math.min(0.5, rad / box.getHeight());
        return List.of(new Pictures.Step("soft|" + Arrays.toString(cut) + "|" + rx + "|" + ry,
                (img, kind) -> Recolor.soften(img, cut, rx, ry)));
    }

    private static Map<String, String> reflectionOf(XmlObject effects) {
        try (XmlCursor c = effects.newCursor()) {
            for (boolean more = c.toFirstChild(); more; more = c.toNextSibling()) {
                if ("reflection".equals(c.getName().getLocalPart())) {
                    Map<String, String> out = new HashMap<>();
                    for (boolean a = c.toFirstAttribute(); a; a = c.toNextAttribute()) {
                        out.put(c.getName().getLocalPart(), c.getTextValue());
                    }
                    return out;
                }
            }
        }
        return null;
    }

    private static float number(Map<String, String> attrs, String name, float fallback) {
        String v = attrs.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            String t = v.strip();
            float f = t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) * 1000 : Float.parseFloat(t);
            return Float.isFinite(f) && Math.abs(f) < 1e9f ? f : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // A picture with see-through parts would cast a shadow of its shape, not of what shows
    private boolean opaque(DecodedPicture picture, CTBlipFillProperties fill) {
        if (picture.vector() || picture.image() == null || BlipFills.alpha(fill) < 1) {
            return false;
        }
        COSDictionary d = picture.image().getCOSObject();
        if (d.containsKey(COSName.MASK)) {
            return false;
        }
        return !d.containsKey(COSName.SMASK) || opaque.computeIfAbsent(picture, PicturePainter::solidMask);
    }

    // An alpha channel that is fully opaque everywhere, as many PNG screenshots carry
    private static boolean solidMask(DecodedPicture picture) {
        try {
            PDImageXObject mask = picture.image().getSoftMask();
            if (mask == null) {
                return true;
            }
            COSBase filter = mask.getCOSObject().getDictionaryObject(COSName.FILTER);
            long n = (long) mask.getWidth() * mask.getHeight();
            if (mask.getBitsPerComponent() != 8 || n > 64_000_000L
                    || filter != null && !COSName.FLATE_DECODE.equals(filter)) {
                return false;
            }
            try (InputStream in = mask.getStream().createInputStream()) {
                byte[] buf = new byte[65_536];
                long seen = 0;
                for (int k; (k = in.read(buf)) > 0; ) {
                    for (int i = 0; i < k; i++) {
                        if ((buf[i] & 0xFF) < 250) {
                            return false;
                        }
                    }
                    seen += k;
                }
                return seen >= n;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean rectangular(XSLFSimpleShape shape) {
        return rectangular(shape.getXmlObject() instanceof CTPicture c ? c.getSpPr()
                : shape.getXmlObject() instanceof CTShape s ? s.getSpPr() : null);
    }

    private static boolean rectangular(CTShapeProperties spPr) {
        if (spPr == null || spPr.isSetCustGeom()) {
            return spPr == null;
        }
        CTPresetGeometry2D g = spPr.getPrstGeom();
        return g == null || g.getPrst() == null || g.getPrst() == STShapeType.RECT;
    }
}
