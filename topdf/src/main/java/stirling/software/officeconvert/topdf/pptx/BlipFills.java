package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.poi.ooxml.util.POIXMLUnits;
import org.apache.poi.sl.usermodel.ColorStyle;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlip;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRelativeRect;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTileInfoProperties;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class BlipFills {

    private static final int MAX_TILES = 4096;

    private static final String DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private BlipFills() {}

    static DecodedPicture picture(Deck deck, CTBlipFillProperties fill, String relsPart) throws IOException {
        return picture(deck, fill, relsPart, null);
    }

    static DecodedPicture picture(Deck deck, CTBlipFillProperties fill, String relsPart, List<Color> duotone)
            throws IOException {
        return picture(deck, fill, relsPart, duotone, null);
    }

    static DecodedPicture picture(Deck deck, CTBlipFillProperties fill, String relsPart, List<Color> duotone,
            XSLFSheet sheet) throws IOException {
        return picture(deck, fill, relsPart, duotone, sheet, List.of());
    }

    static DecodedPicture picture(Deck deck, CTBlipFillProperties fill, String relsPart, List<Color> duotone,
            XSLFSheet sheet, List<Pictures.Step> after) throws IOException {
        CTBlip blip = fill == null ? null : fill.getBlip();
        if (blip == null || !blip.isSetEmbed()) {
            return null;
        }
        String id = blip.getEmbed();
        if (id == null || id.isBlank()) {
            return null;
        }
        List<Pictures.Step> steps = steps(blip, duotone, sheet);
        steps.addAll(after);
        return steps.isEmpty() ? deck.pictures().picture(relsPart, id)
                : deck.pictures().recoloured(relsPart, id, steps);
    }

    // The colour effects of a picture in the order they are written, which is the order PowerPoint applies them
    static List<Pictures.Step> steps(CTBlip blip, List<Color> duotone, XSLFSheet sheet) {
        List<Pictures.Step> out = new ArrayList<>();
        Pictures.Step duo = duotone != null && duotone.size() == 2 && duotone.get(0) != null && duotone.get(1) != null
                ? new Pictures.Step("duotone|" + duotone.get(0).getRGB() + "|" + duotone.get(1).getRGB(),
                        (img, kind) -> Recolor.duotone(img, duotone.get(0), duotone.get(1)))
                : null;
        try (XmlCursor c = blip.newCursor()) {
            for (boolean more = c.toFirstChild(); more; more = c.toNextSibling()) {
                if (!DRAWINGML.equals(c.getName().getNamespaceURI())) {
                    continue;
                }
                switch (c.getName().getLocalPart()) {
                    case "clrChange" -> {
                        Pictures.Step s = change(c.getObject(), sheet);
                        if (s != null) {
                            out.add(s);
                        }
                    }
                    case "grayscl" -> out.add(new Pictures.Step("grayscl", (img, kind) -> Recolor.gray(img)));
                    case "biLevel" -> {
                        float t = percent(c.getAttributeText(new QName("", "thresh")));
                        out.add(new Pictures.Step("biLevel|" + t, (img, kind) -> Recolor.biLevel(img, t)));
                    }
                    case "lum" -> {
                        float bright = percent(c.getAttributeText(new QName("", "bright")));
                        float contrast = percent(c.getAttributeText(new QName("", "contrast")));
                        if (bright != 0 || contrast != 0) {
                            out.add(new Pictures.Step("lum|" + bright + "|" + contrast,
                                    (img, kind) -> Recolor.lum(img, bright, contrast)));
                        }
                    }
                    case "duotone" -> {
                        if (duo != null) {
                            out.add(duo);
                            duo = null;
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (duo != null) {
            out.add(duo);
        }
        return out;
    }

    private static Pictures.Step change(XmlObject x, XSLFSheet sheet) {
        Color from = null;
        Color to = null;
        try (XmlCursor c = x.newCursor()) {
            if (c.toChild(DRAWINGML, "clrFrom")) {
                from = color(c.getObject(), sheet);
            }
        }
        try (XmlCursor c = x.newCursor()) {
            if (c.toChild(DRAWINGML, "clrTo")) {
                to = color(c.getObject(), sheet);
            }
        }
        if (from == null || to == null || from.equals(to)) {
            return null;
        }
        Color f = from;
        Color t = to;
        return new Pictures.Step("clrChange|" + f.getRGB() + "|" + t.getRGB(),
                (img, kind) -> Recolor.change(img, f, t, tolerance(kind)));
    }

    // How near a pixel must be to the colour to change: lossy pictures need more room than exact ones
    static int tolerance(PictureDecoder.Kind kind) {
        return switch (kind) {
            case JPEG -> 15;
            case PNG, TIFF -> 1;
            case BMP -> 0;
            default -> 9;
        };
    }

    private static Color color(XmlObject holder, XSLFSheet sheet) {
        try {
            return Paints.color(new XSLFColor(holder, sheet == null ? null : sheet.getTheme(), null, sheet)
                    .getColorStyle());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float percent(String v) {
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            String t = v.strip();
            float f = t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) / 100
                    : Float.parseFloat(t) / 100_000f;
            return Float.isFinite(f) ? Math.max(-1, Math.min(1, f)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static List<Color> duotone(PaintStyle paint) {
        if (!(paint instanceof PaintStyle.TexturePaint t)) {
            return null;
        }
        try {
            List<ColorStyle> styles = t.getDuoTone();
            if (styles == null || styles.size() != 2) {
                return null;
            }
            List<Color> out = new ArrayList<>(2);
            for (ColorStyle cs : styles) {
                out.add(Paints.color(cs));
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Both duotone colours of a blip, in any colour model and with their transforms
    static List<Color> duotone(CTBlip blip, XSLFSheet sheet) {
        if (blip == null || sheet == null || blip.sizeOfDuotoneArray() == 0) {
            return null;
        }
        List<Color> out = new ArrayList<>(2);
        try (XmlCursor c = blip.getDuotoneArray(0).newCursor()) {
            for (boolean more = c.toFirstChild(); more && out.size() < 2; more = c.toNextSibling()) {
                Color color = TableStyles.color(c.getObject(), sheet);
                if (color != null) {
                    out.add(color);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out.size() == 2 ? out : null;
    }

    static float alpha(CTBlipFillProperties fill) {
        CTBlip blip = fill.getBlip();
        if (blip == null) {
            return 1;
        }
        try {
            if (blip.sizeOfAlphaModFixArray() > 0) {
                var mod = blip.getAlphaModFixArray(0);
                if (mod.isSetAmt()) {
                    return Math.max(0, Math.min(1, POIXMLUnits.parsePercent(mod.xgetAmt()) / 100_000f));
                }
            }
        } catch (RuntimeException e) {
            return 1;
        }
        return 1;
    }

    static Crop crop(CTRelativeRect r) {
        if (r == null) {
            return Crop.NONE;
        }
        float l = fraction(r.isSetL() ? POIXMLUnits.parsePercent(r.xgetL()) : 0);
        float t = fraction(r.isSetT() ? POIXMLUnits.parsePercent(r.xgetT()) : 0);
        float rr = fraction(r.isSetR() ? POIXMLUnits.parsePercent(r.xgetR()) : 0);
        float b = fraction(r.isSetB() ? POIXMLUnits.parsePercent(r.xgetB()) : 0);
        if (!(l + rr < 0.999f) || !(t + b < 0.999f)) {
            return Crop.NONE;
        }
        return Crop.fractions(l, t, rr, b);
    }

    private static float fraction(int thousandths) {
        return Math.max(-10, Math.min(10, thousandths / 100_000f));
    }

    static Rectangle2D stretched(CTBlipFillProperties fill, Rectangle2D box) {
        if (fill.isSetStretch() && fill.getStretch().isSetFillRect()) {
            CTRelativeRect r = fill.getStretch().getFillRect();
            Crop c = crop(r);
            double w = box.getWidth();
            double h = box.getHeight();
            return new Rectangle2D.Double(box.getX() + w * c.left(), box.getY() + h * c.top(),
                    w * (1 - c.left() - c.right()), h * (1 - c.top() - c.bottom()));
        }
        return box;
    }

    static void draw(Deck deck, PdfCanvas canvas, CTBlipFillProperties fill, String relsPart, Shape clip,
            Rectangle2D box, List<Color> duotone, XSLFSheet sheet) throws IOException {
        DecodedPicture pic = picture(deck, fill, relsPart, duotone, sheet);
        if (pic == null) {
            return;
        }
        float alpha = alpha(fill);
        canvas.save();
        try {
            canvas.clip(clip);
            if (fill.isSetTile() && tile(canvas, pic, fill.getTile(), box, alpha)) {
                return;
            }
            Rectangle2D r = stretched(fill, box);
            canvas.image(pic, (float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight(),
                    crop(fill.getSrcRect()), 0, false, false, alpha);
        } finally {
            canvas.restore();
        }
    }

    private static boolean tile(PdfCanvas canvas, DecodedPicture pic, CTTileInfoProperties tile, Rectangle2D box,
            float alpha) throws IOException {
        double sx = tile.isSetSx() ? POIXMLUnits.parsePercent(tile.xgetSx()) / 100_000.0 : 1;
        double sy = tile.isSetSy() ? POIXMLUnits.parsePercent(tile.xgetSy()) / 100_000.0 : 1;
        double tw = pic.naturalWidth() * Math.abs(sx);
        double th = pic.naturalHeight() * Math.abs(sy);
        if (!(tw > 0.5 && th > 0.5)) {
            return false;
        }
        double ox = tile.isSetTx() ? POIXMLUnits.parseLength(tile.xgetTx()) / 12_700.0 : 0;
        double oy = tile.isSetTy() ? POIXMLUnits.parseLength(tile.xgetTy()) / 12_700.0 : 0;
        long cols = (long) Math.ceil(box.getWidth() / tw) + 1;
        long rows = (long) Math.ceil(box.getHeight() / th) + 1;
        if (cols * rows > MAX_TILES) {
            return false;
        }
        double x0 = box.getX() + ((ox % tw) + tw) % tw - tw;
        double y0 = box.getY() + ((oy % th) + th) % th - th;
        for (double y = y0; y < box.getMaxY(); y += th) {
            for (double x = x0; x < box.getMaxX(); x += tw) {
                canvas.image(pic, (float) x, (float) y, (float) tw, (float) th, Crop.NONE, 0, false, false, alpha);
            }
        }
        return true;
    }
}
