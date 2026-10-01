package stirling.software.officeconvert.topdf.ppt;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hslf.model.MovieShape;
import org.apache.poi.hslf.record.Record;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.hslf.usermodel.HSLFFontInfo;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFObjectShape;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFSoundData;
import org.apache.poi.hslf.usermodel.HSLFTextShape;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.LegacyOffice;
import stirling.software.officeconvert.topdf.io.PictureDecoder;
import stirling.software.officeconvert.topdf.io.SafeImageRenderer;
import stirling.software.officeconvert.topdf.pdf.DocumentInfo;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

/** PowerPoint 97-2003 (.ppt, .pps, .pot): POI draws each slide through its common drawing interfaces. */
public final class PptRenderer {

    static final float DEFAULT_WIDTH = 720;

    static final float DEFAULT_HEIGHT = 540;

    private static final int MAX_SHAPE_DEPTH = 64;

    private PptRenderer() {}

    public static void render(Path source, RenderJob job) throws IOException {
        Objects.requireNonNull(job, "job");
        try (HSLFSlideShow ppt = LegacyOffice.slideShow(Objects.requireNonNull(source, "source"))) {
            info(job, ppt);
            boundPictures(job, ppt);
            WordArt.flatten(ppt);
            fixUp(ppt);
            SlideText text = new SlideText(job, fonts(job, ppt));
            Dimension size = ppt.getPageSize();
            float w = clamp(size == null ? 0 : size.width, DEFAULT_WIDTH);
            float h = clamp(size == null ? 0 : size.height, DEFAULT_HEIGHT);
            List<HSLFSlide> slides = ppt.getSlides();
            SlideLinks links = new SlideLinks(slides);
            SlideFooters footers = new SlideFooters();
            for (HSLFSlide slide : slides) {
                job.checkpoint();
                if (slide.isHidden()) {
                    continue;
                }
                text.startSlide(textLinks(links, slide));
                try (PdfCanvas canvas = job.newPage(w, h)) {
                    List<PDFormXObject> forms = new ArrayList<>(draw(job, slide, footers, text, w, h));
                    for (PDFormXObject form : forms) {
                        canvas.form(form, 0, 0, w, h);
                    }
                    for (SlideLinks.Area a : shapeLinks(links, slide)) {
                        SlideLinks.place(canvas, a.box(), a.target());
                    }
                    for (SlideLinks.Area a : LinkLocator.locate(forms, text.pendingLinks(), w, h)) {
                        SlideLinks.place(canvas, a.box(), a.target());
                    }
                }
            }
            for (String line : ActiveContent.describe(activeContent(ppt))) {
                job.warn(line);
            }
        }
    }

    private static void fixUp(HSLFSlideShow ppt) {
        try {
            TitleFooters.apply(ppt);
            RtlParagraphs.apply(ppt);
        } catch (RuntimeException e) {
            return;
        } finally {
            shadows(ppt);
        }
    }

    private static void shadows(HSLFSlideShow ppt) {
        try {
            ShapeShadows.apply(ppt);
        } catch (RuntimeException e) {
            return;
        }
    }

    private static Map<String, SlideLinks.Target> textLinks(SlideLinks links, HSLFSlide slide) {
        try {
            return links.textTargets(slide);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static List<SlideLinks.Area> shapeLinks(SlideLinks links, HSLFSlide slide) {
        try {
            return links.shapeAreas(slide);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // A slide POI cannot draw whole is drawn again shape by shape, leaving out only what fails
    private static List<PDFormXObject> draw(RenderJob job, HSLFSlide slide, SlideFooters footers, SlideText text,
            float w, float h) throws IOException {
        List<HSLFTextShape> extra = footerShapes(slide, footers);
        try {
            List<PDFormXObject> forms = new ArrayList<>();
            forms.add(SafeImageRenderer.drawForm(job.document(), slide, w, h, text));
            if (!extra.isEmpty()) {
                forms.add(SafeImageRenderer.drawShapes(job.document(), slide, extra, w, h, text));
            }
            return forms;
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            job.checkpoint();
        }
        List<Throwable> lost = new ArrayList<>();
        List<PDFormXObject> forms = SafeImageRenderer.drawParts(job.document(), slide, w, h, text, lost::add);
        if (!lost.isEmpty()) {
            Throwable e = lost.get(0);
            String why = e instanceof StackOverflowError ? "it nests too deeply"
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.warn("Left out " + lost.size() + (lost.size() == 1 ? " part" : " parts") + " of slide "
                    + slide.getSlideNumber() + " that could not be drawn: " + why);
            job.losePart();
        }
        return forms;
    }

    private static List<HSLFTextShape> footerShapes(HSLFSlide slide, SlideFooters footers) {
        try {
            footers.writeFooter(slide);
            return footers.slideNumbers(slide);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void info(RenderJob job, HSLFSlideShow ppt) {
        try {
            SummaryInformation si = ppt.getSummaryInformation();
            if (si != null) {
                job.output().info(new DocumentInfo(si.getTitle(), si.getAuthor(), si.getSubject(), si.getKeywords(),
                        null));
            }
        } catch (RuntimeException ignored) {
            // the properties only fill the PDF's document information
        }
    }

    private static void boundPictures(RenderJob job, HSLFSlideShow ppt) throws IOException {
        List<HSLFPictureData> pictures;
        try {
            pictures = ppt.getPictureData();
        } catch (RuntimeException e) {
            job.warn("The pictures could not be read, so none are drawn: " + e.getMessage());
            job.losePart();
            return;
        }
        int dropped = 0;
        for (HSLFPictureData p : pictures) {
            job.checkpoint();
            if (!LegacyOffice.boundPicture(p, PictureDecoder.MAX_METAFILE_BYTES)) {
                dropped++;
            }
        }
        if (dropped > 0) {
            job.warn("Left out " + dropped + (dropped == 1 ? " picture" : " pictures") + " in a format that cannot be"
                    + " drawn (PICT), damaged, or unpacking past " + (PictureDecoder.MAX_METAFILE_BYTES >> 20) + " MB");
        }
    }

    // A family POI's AWT layout would not find is renamed to the stand-in we draw with, so both measure alike
    private static Map<String, String> fonts(RenderJob job, HSLFSlideShow ppt) {
        Map<String, String> families = new HashMap<>();
        List<HSLFFontInfo> list;
        try {
            list = ppt.getFonts();
        } catch (RuntimeException e) {
            return families;
        }
        for (HSLFFontInfo f : list) {
            String name = f.getTypeface();
            if (name == null || name.isBlank()) {
                continue;
            }
            FontFace face = job.fonts().find(name, false, false);
            String family = face.family();
            if (family == null || family.equalsIgnoreCase(name) || !awtHas(family)) {
                continue;
            }
            f.setTypeface(family);
            families.putIfAbsent(family.toLowerCase(Locale.ROOT), name);
        }
        return families;
    }

    private static boolean awtHas(String family) {
        try {
            return family.equalsIgnoreCase(new java.awt.Font(family, java.awt.Font.PLAIN, 10).getFamily(Locale.ROOT));
        } catch (RuntimeException | InternalError e) {
            return false;
        }
    }

    private static Map<ActiveContent.Kind, Set<String>> activeContent(HSLFSlideShow ppt) {
        Map<ActiveContent.Kind, Set<String>> found = new EnumMap<>(ActiveContent.Kind.class);
        try {
            Record doc = ppt.getDocumentRecord();
            Record[] children = doc == null ? null : doc.getChildRecords();
            if (children != null) {
                for (Record r : children) {
                    if (r != null && r.getRecordType() == RecordTypes.VBAInfo.typeID) {
                        found.computeIfAbsent(ActiveContent.Kind.MACRO, k -> new LinkedHashSet<>()).add("VBA");
                    }
                }
            }
            for (HSLFSlide slide : ppt.getSlides()) {
                shapes(slide.getSlideNumber(), slide.getShapes(), found, 0);
            }
            HSLFSoundData[] sounds = ppt.getSoundData();
            for (int i = 0; sounds != null && i < sounds.length; i++) {
                found.computeIfAbsent(ActiveContent.Kind.MEDIA, k -> new LinkedHashSet<>()).add("sound " + i);
            }
        } catch (RuntimeException ignored) {
            // the warnings only name what was skipped
        }
        return found;
    }

    private static void shapes(int slide, List<HSLFShape> list, Map<ActiveContent.Kind, Set<String>> found,
            int depth) {
        if (list == null || depth > MAX_SHAPE_DEPTH) {
            return;
        }
        for (HSLFShape s : list) {
            String id = slide + "/" + s.getShapeId();
            if (s instanceof HSLFObjectShape) {
                found.computeIfAbsent(ActiveContent.Kind.OLE_OBJECT, k -> new LinkedHashSet<>()).add(id);
            } else if (s instanceof MovieShape) {
                found.computeIfAbsent(ActiveContent.Kind.MEDIA, k -> new LinkedHashSet<>()).add(id);
            } else if (s instanceof HSLFGroupShape g) {
                shapes(slide, g.getShapes(), found, depth + 1);
            }
        }
    }

    private static float clamp(float v, float fallback) {
        if (!(v > 0) || !Float.isFinite(v)) {
            return fallback;
        }
        return Math.max(1, Math.min(14_400, v));
    }
}
