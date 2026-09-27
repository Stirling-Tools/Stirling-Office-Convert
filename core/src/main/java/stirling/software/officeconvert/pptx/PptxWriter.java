package stirling.software.officeconvert.pptx;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.sink.ZipParts;
import stirling.software.officeconvert.slides.Slide;
import stirling.software.officeconvert.slides.SlideShape;
import stirling.software.officeconvert.slides.SlideSink;
import stirling.software.officeconvert.slides.TextPara;
import stirling.software.officeconvert.slides.TextShape;

public final class PptxWriter implements SlideSink {

    private final ZipOutputStream zip;
    private final Map<Object, Picture.MediaRef> mediaByKey = new HashMap<>();
    private final Map<String, Integer> fonts = new HashMap<>();
    private final Map<String, Integer> titleFonts = new HashMap<>();
    private long width;
    private long height;
    private int firstPage;
    private int expected;
    private int slides;
    private int mediaCount;

    public PptxWriter(OutputStream target) {
        this.zip = new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
        this.zip.setLevel(6);
    }

    @Override
    public void begin(float slideWidth, float slideHeight, int firstPage, int slideCount) {
        this.width = Ooxml.emu(slideWidth);
        this.height = Ooxml.emu(slideHeight);
        this.firstPage = firstPage;
        this.expected = slideCount;
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
        String name = "image" + (++mediaCount) + "." + ext;
        ZipParts.picture(zip, "ppt/media/" + name, bytes);
        Picture.MediaRef ref = new Picture.MediaRef(name, "jpeg".equals(ext) ? "image/jpeg" : "image/png", pixelWidth,
                pixelHeight);
        mediaByKey.put(key, ref);
        return ref;
    }

    @Override
    public void slide(Slide slide) throws IOException {
        slides++;
        SlideXml xml = new SlideXml(slide, this::slideOf, fonts);
        String body = xml.xml(slide);
        entry("ppt/slides/slide" + slides + ".xml", body);
        entry("ppt/slides/_rels/slide" + slides + ".xml.rels", xml.rels().xml());
        for (SlideShape s : slide.shapes()) {
            if (s instanceof TextShape t && t.title()) {
                for (TextPara p : t.paras()) {
                    for (Inline in : p.content().inlines) {
                        if (in instanceof Inline.Text text && text.style().font() != null) {
                            titleFonts.merge(text.style().font(), text.text().length(), Integer::sum);
                        }
                    }
                }
            }
        }
    }

    private int slideOf(int page) {
        int n = page - firstPage + 1;
        return n >= 1 && n <= expected ? n : -1;
    }

    @Override
    public void finish(String title, String author) throws IOException {
        String body = mostUsed(fonts, "Calibri");
        String heading = mostUsed(titleFonts, body);
        entry("ppt/presentation.xml", PptxParts.presentation(slides, width, height));
        entry("ppt/_rels/presentation.xml.rels", PptxParts.presentationRels(slides));
        entry("ppt/slideMasters/slideMaster1.xml", PptxParts.master(width, height));
        entry("ppt/slideMasters/_rels/slideMaster1.xml.rels", PptxParts.masterRels());
        entry("ppt/slideLayouts/" + SlideXml.TITLE_LAYOUT, PptxParts.titleLayout());
        entry("ppt/slideLayouts/_rels/" + SlideXml.TITLE_LAYOUT + ".rels", PptxParts.layoutRels());
        entry("ppt/slideLayouts/" + SlideXml.BLANK_LAYOUT, PptxParts.blankLayout());
        entry("ppt/slideLayouts/_rels/" + SlideXml.BLANK_LAYOUT + ".rels", PptxParts.layoutRels());
        entry("ppt/theme/theme1.xml", PptxParts.theme(heading, body));
        entry("ppt/presProps.xml", PptxParts.presProps());
        entry("ppt/viewProps.xml", PptxParts.viewProps());
        entry("ppt/tableStyles.xml", PptxParts.tableStyles());
        entry("docProps/core.xml", PptxParts.core(title, author));
        entry("docProps/app.xml", PptxParts.app(slides));
        entry("_rels/.rels", PptxParts.packageRels());
        entry("[Content_Types].xml", PptxParts.contentTypes(slides));
        zip.finish();
    }

    private static String mostUsed(Map<String, Integer> counts, String fallback) {
        String best = fallback;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts).entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private void entry(String name, String content) throws IOException {
        entry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private void entry(String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
