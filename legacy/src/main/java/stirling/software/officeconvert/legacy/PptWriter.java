package stirling.software.officeconvert.legacy;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFFill;
import org.apache.poi.hslf.usermodel.HSLFLine;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.ShapeType;

import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.slides.Frame;
import stirling.software.officeconvert.slides.LineShape;
import stirling.software.officeconvert.slides.PictureShape;
import stirling.software.officeconvert.slides.RectShape;
import stirling.software.officeconvert.slides.Slide;
import stirling.software.officeconvert.slides.SlideShape;
import stirling.software.officeconvert.slides.SlideSink;
import stirling.software.officeconvert.slides.TableShape;
import stirling.software.officeconvert.slides.TextShape;

public final class PptWriter implements SlideSink {

    private static final int SOLID_FILL = 0;

    private final OutputStream out;
    private final HSLFSlideShow show = new HSLFSlideShow();
    private final Map<Object, Picture.MediaRef> mediaByKey = new HashMap<>();
    private final Map<String, HSLFPictureData> pictures = new HashMap<>();
    private final List<HSLFSlide> slides = new ArrayList<>();
    private int firstPage;
    private int next;

    public PptWriter(OutputStream out) {
        this.out = out;
    }

    @Override
    public void begin(float width, float height, int firstPage, int slideCount) {
        show.setPageSize(new Dimension(Math.round(width), Math.round(height)));
        this.firstPage = firstPage;
        for (int i = 0; i < slideCount; i++) {
            slides.add(show.createSlide());
        }
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return mediaByKey.get(key);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key) throws IOException {
        Picture.MediaRef existing = mediaByKey.get(key);
        if (existing != null) {
            return existing;
        }
        boolean jpeg = "jpeg".equals(ext);
        HSLFPictureData data = show.addPicture(bytes, jpeg ? PictureType.JPEG : PictureType.PNG);
        String name = "image" + (pictures.size() + 1) + "." + ext;
        pictures.put(name, data);
        Picture.MediaRef ref = new Picture.MediaRef(name, jpeg ? "image/jpeg" : "image/png", pixelWidth, pixelHeight);
        mediaByKey.put(key, ref);
        return ref;
    }

    @Override
    public void slide(Slide slide) {
        if (next >= slides.size()) {
            slides.add(show.createSlide());
        }
        HSLFSlide s = slides.get(next++);
        if (slide.background() >= 0) {
            s.setFollowMasterBackground(false);
            HSLFFill fill = s.getBackground().getFill();
            fill.setFillType(SOLID_FILL);
            fill.setForegroundColor(new Color(slide.background()));
        }
        PptText text = new PptText(this::slideOf);
        for (SlideShape shape : slide.shapes()) {
            switch (shape) {
                case TextShape t -> text.shape(s, t);
                case PictureShape p -> picture(s, p);
                case RectShape r -> rect(s, r);
                case LineShape l -> line(s, l);
                case TableShape t -> PptTables.table(s, t, text);
            }
        }
    }

    private HSLFSlide slideOf(int page) {
        int n = page - firstPage;
        return n >= 0 && n < slides.size() ? slides.get(n) : null;
    }

    static Rectangle2D anchor(Frame f) {
        double w = Math.max(0.1, f.width());
        double h = Math.max(0.1, f.height());
        double r = rotation(f);
        if (r > 45 && r < 135 || r > 225 && r < 315) {
            return new Rectangle2D.Double(f.x() + (w - h) / 2, f.y() + (h - w) / 2, h, w);
        }
        return new Rectangle2D.Double(f.x(), f.y(), w, h);
    }

    static double rotation(Frame f) {
        int r = Math.floorMod(f.rotation(), 360);
        return r % 90 == 45 ? r + 1.0 / 65536 : r;
    }

    private void picture(HSLFSlide s, PictureShape p) {
        Picture pic = p.picture();
        HSLFPictureData data = pictures.get(pic.media.name());
        if (data == null) {
            return;
        }
        HSLFPictureShape shape = s.createPicture(data);
        shape.setAnchor(anchor(p.frame()));
        if (p.frame().rotation() != 0) {
            shape.setRotation(rotation(p.frame()));
        }
        if (pic.flipH) {
            shape.setFlipHorizontal(true);
        }
        crop(shape, EscherPropertyTypes.BLIP__CROPFROMLEFT, pic.cropLeft);
        crop(shape, EscherPropertyTypes.BLIP__CROPFROMTOP, pic.cropTop);
        crop(shape, EscherPropertyTypes.BLIP__CROPFROMRIGHT, pic.cropRight);
        crop(shape, EscherPropertyTypes.BLIP__CROPFROMBOTTOM, pic.cropBottom);
    }

    private static void crop(HSLFPictureShape shape, EscherPropertyTypes side, float share) {
        if (share > 0) {
            shape.setEscherProperty(side, Math.round(share * 65536));
        }
    }

    private static void rect(HSLFSlide s, RectShape r) {
        boolean round = r.radius() > 0.1f;
        HSLFAutoShape shape = new HSLFAutoShape(round ? ShapeType.ROUND_RECT : ShapeType.RECT, s);
        shape.setAnchor(anchor(r.frame()));
        if (round) {
            float side = Math.max(0.1f, Math.min(r.frame().width(), r.frame().height()));
            shape.setAdjustmentValue(0, Math.clamp(Math.round(r.radius() / side * 21600), 0, 10800));
        }
        shape.setFillColor(r.fillRgb() >= 0 ? new Color(r.fillRgb()) : null);
        if (r.fillRgb() >= 0 && r.alpha() < 0.995f) {
            shape.setEscherProperty(EscherPropertyTypes.FILL__FILLOPACITY, Math.round(r.alpha() * 65536));
        }
        if (r.lineRgb() >= 0 && r.lineWidth() > 0) {
            shape.setLineColor(new Color(r.lineRgb()));
            shape.setLineWidth(r.lineWidth());
        } else {
            shape.setLineColor(null);
        }
        s.addShape(shape);
    }

    private static void line(HSLFSlide s, LineShape l) {
        HSLFLine line = new HSLFLine(s);
        float x = Math.min(l.x1(), l.x2());
        float y = Math.min(l.y1(), l.y2());
        line.setAnchor(new Rectangle2D.Double(x, y, Math.abs(l.x2() - l.x1()), Math.abs(l.y2() - l.y1())));
        if (l.x2() < l.x1()) {
            line.setFlipHorizontal(true);
        }
        if (l.y2() < l.y1()) {
            line.setFlipVertical(true);
        }
        line.setLineColor(new Color(Math.max(0, l.rgb())));
        line.setLineWidth(l.width());
        s.addShape(line);
    }

    @Override
    public void finish(String title, String author) throws IOException {
        while (slides.size() > next) {
            show.removeSlide(slides.size() - 1);
            slides.removeLast();
        }
        if (title != null && !title.isBlank() || author != null && !author.isBlank()) {
            show.createInformationProperties();
            var props = show.getSummaryInformation();
            if (props != null) {
                if (title != null && !title.isBlank()) {
                    props.setTitle(title);
                }
                if (author != null && !author.isBlank()) {
                    props.setAuthor(author);
                }
            }
        }
        show.write(out);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        show.close();
    }
}
