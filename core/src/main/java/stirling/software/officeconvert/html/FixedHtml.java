package stirling.software.officeconvert.html;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.sink.Links;

public final class FixedHtml {

    public enum Text {
        LIVE,
        PICTURE
    }

    @FunctionalInterface
    public interface FontSource {
        byte[] webFont(PDFont font) throws IOException;
    }

    public record Options(int firstPage, int lastPage, float dpi, String title, Text text, FontSource fonts) {

        public static final float DEFAULT_DPI = 150f;

        public Options {
            if (!(dpi >= 36 && dpi <= 300)) {
                throw new IllegalArgumentException("dpi must be 36 to 300, was " + dpi);
            }
            text = text == null ? Text.LIVE : text;
        }

        public static Options defaults() {
            return new Options(1, Integer.MAX_VALUE, DEFAULT_DPI, null, Text.LIVE, null);
        }

        public Options text(Text mode) {
            return new Options(firstPage, lastPage, dpi, title, mode, fonts);
        }

        public Options pages(int first, int last) {
            return new Options(first, last, dpi, title, text, fonts);
        }

        public Options fonts(FontSource source) {
            return new Options(firstPage, lastPage, dpi, title, text, source);
        }
    }

    static final long MAX_PAGE_PIXELS = 40_000_000L;

    static final int MAX_PAGE_SIDE = 32_000;

    static final int MAX_FONT_BYTES = 4 << 20;

    static final long MAX_FONTS_BYTES = 32L << 20;

    private static final class Fonts {
        final FontSource source;
        final Map<Object, String> families = new HashMap<>();
        long bytes;

        Fonts(FontSource source) {
            this.source = source;
        }

        String family(PDFont font, StringBuilder faces) {
            if (source == null || font == null) {
                return null;
            }
            Object key = font.getCOSObject();
            if (families.containsKey(key)) {
                return families.get(key);
            }
            if (bytes >= MAX_FONTS_BYTES) {
                families.put(key, null);
                return null;
            }
            String family = null;
            try {
                byte[] ttf = source.webFont(font);
                if (ttf != null && ttf.length > 0 && ttf.length <= MAX_FONT_BYTES && bytes + ttf.length <= MAX_FONTS_BYTES) {
                    bytes += ttf.length;
                    family = "pdf-f" + (families.size() + 1);
                    faces.append("@font-face{font-family:'").append(family)
                            .append("';src:url(data:font/ttf;base64,")
                            .append(Base64.getEncoder().encodeToString(ttf)).append(") format('truetype')}\n");
                }
            } catch (IOException | RuntimeException e) {
                family = null;
            }
            families.put(key, family);
            return family;
        }
    }

    private record Span(String text, float x, float y, float size, float width, float angle, int rgb, String font,
            boolean bold, boolean italic, PDFont pdFont) {}

    private FixedHtml() {}

    public static void write(PDDocument doc, OutputStream target, Options options) throws IOException {
        int pages = doc.getNumberOfPages();
        int first = Math.max(1, options.firstPage());
        int last = Math.min(pages, options.lastPage());
        OutputStream out = new KeepOpen(target);
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(head(doc, options));
        PDFRenderer renderer = options.text() == Text.LIVE ? new WithoutText(doc) : new PDFRenderer(doc);
        renderer.setSubsamplingAllowed(true);
        Fonts fonts = new Fonts(options.text() == Text.LIVE ? options.fonts() : null);
        Set<String> sizes = new HashSet<>();
        for (int i = first - 1; i < last; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("The conversion was stopped");
            }
            page(doc, renderer, i, options, w, out, fonts, sizes);
        }
        w.write("</body>\n</html>\n");
        w.flush();
    }

    private static String head(PDDocument doc, Options options) {
        String title = options.title();
        if (title == null || title.isBlank()) {
            title = doc.getDocumentInformation() == null ? null : doc.getDocumentInformation().getTitle();
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"generator\" content=\"Stirling Office Convert\">\n<title>");
        HtmlText.escape(sb, title == null || title.isBlank() ? "Document" : title.strip());
        sb.append("</title>\n<style>\n")
                .append("@page{margin:0").append(firstSize(doc, options)).append("}\n")
                .append("html{background:#e8e8e8}body{margin:0;padding:12pt 0}\n")
                .append(".pg{position:relative;margin:0 auto 12pt;background:#fff;overflow:hidden;")
                .append("box-shadow:0 1px 4px rgba(0,0,0,.25);break-after:page}\n")
                .append(".pg>img{position:absolute;left:0;top:0;width:100%;height:100%;user-select:none}\n")
                .append(".pg>svg{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible}\n")
                .append(".pg>svg text{white-space:pre}\n")
                .append(".pg>a{position:absolute;display:block}\n")
                .append("::selection{background:rgba(0,95,255,.3)}\n")
                .append("@media print{html{background:none}body{padding:0}.pg{margin:0;box-shadow:none}}\n")
                .append("</style>\n</head>\n<body>\n");
        return sb.toString();
    }

    private static String firstSize(PDDocument doc, Options options) {
        int index = Math.max(1, options.firstPage()) - 1;
        if (index >= doc.getNumberOfPages()) {
            return "";
        }
        PDPage page = doc.getPage(index);
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        return ";size:" + HtmlText.pt(turned ? box.getHeight() : box.getWidth()) + " "
                + HtmlText.pt(turned ? box.getWidth() : box.getHeight());
    }

    private static void page(PDDocument doc, PDFRenderer renderer, int index, Options options, Writer w,
            OutputStream out, Fonts fonts, Set<String> sizes) throws IOException {
        PDPage page = doc.getPage(index);
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        boolean turned = rotation == 90 || rotation == 270;
        float width = turned ? box.getHeight() : box.getWidth();
        float height = turned ? box.getWidth() : box.getHeight();
        float scale = pictureScale(width, height, options.dpi());
        boolean live = options.text() == Text.LIVE;
        StringBuilder text = new StringBuilder(4096);
        StringBuilder faces = new StringBuilder();
        text.append("<svg viewBox=\"0 0 ").append(num(width)).append(' ').append(num(height))
                .append("\" preserveAspectRatio=\"none\">\n");
        for (Span s : spans(doc, index, rotation)) {
            span(text, s, live, live && s.rgb() >= 0 ? fonts.family(s.pdFont(), faces) : null);
        }
        text.append("</svg>\n");
        links(text, page, box, rotation, width, height);
        StringBuilder sb = new StringBuilder(512);
        String size = "s" + Math.round(width * 10) + "x" + Math.round(height * 10);
        if (sizes.add(size)) {
            faces.append("@page ").append(size).append("{margin:0;size:").append(HtmlText.pt(width)).append(' ')
                    .append(HtmlText.pt(height)).append("}\n");
        }
        if (!faces.isEmpty()) {
            sb.append("<style>\n").append(faces).append("</style>\n");
        }
        sb.append("<section class=\"").append(live ? "pg" : "pg picture").append("\" id=\"page-").append(index + 1)
                .append("\" style=\"page:").append(size).append(";width:").append(HtmlText.pt(width)).append(";height:")
                .append(HtmlText.pt(height)).append("\">\n");
        long estimate = pixels(width, height, scale) * 4L * 3L;
        Admission.Ticket ticket = Admission.jvm().enter(estimate);
        try {
            BufferedImage image = renderer.renderImage(index, scale, ImageType.RGB);
            if (!live || !blank(image)) {
                Picture picture = picture(image);
                sb.append("<img alt=\"\" src=\"data:").append(picture.type()).append(";base64,");
                w.write(sb.toString());
                w.flush();
                try (OutputStream encoded = Base64.getEncoder().wrap(new KeepOpen(out))) {
                    encoded.write(picture.bytes());
                }
                sb.setLength(0);
                sb.append("\">\n");
            }
        } finally {
            ticket.close();
        }
        sb.append(text).append("</section>\n");
        w.write(sb.toString());
        w.flush();
    }

    static float pictureScale(float width, float height, float dpi) {
        double w = Math.max(0, width);
        double h = Math.max(0, height);
        double scale = dpi / 72.0;
        if (w * scale * h * scale > MAX_PAGE_PIXELS) {
            scale = Math.sqrt(MAX_PAGE_PIXELS / (w * h));
        }
        double side = Math.max(w, h);
        if (side * scale > MAX_PAGE_SIDE) {
            scale = MAX_PAGE_SIDE / side;
        }
        return (float) scale;
    }

    private static long pixels(double width, double height, double scale) {
        return (long) Math.max(1, Math.floor(width * scale)) * (long) Math.max(1, Math.floor(height * scale));
    }

    private record Picture(String type, byte[] bytes) {}

    private static Picture picture(BufferedImage image) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] best = png.toByteArray();
        String type = "image/png";
        if (best.length > 200_000) {
            byte[] jpeg = jpeg(image);
            if (jpeg != null && jpeg.length < best.length * 0.6) {
                best = jpeg;
                type = "image/jpeg";
            }
        }
        return new Picture(type, best);
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static boolean blank(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) {
                if ((p & 0xFFFFFF) != 0xFFFFFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Look(int rgb, boolean invisible) {}

    private static List<Span> spans(PDDocument doc, int index, int rotation) throws IOException {
        List<Span> spans = new ArrayList<>();
        Map<TextPosition, Look> looks = new IdentityHashMap<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            {
                addOperator(new SetNonStrokingColorSpace(this));
                addOperator(new SetNonStrokingColor(this));
                addOperator(new SetNonStrokingColorN(this));
                addOperator(new SetNonStrokingDeviceGrayColor(this));
                addOperator(new SetNonStrokingDeviceRGBColor(this));
                addOperator(new SetNonStrokingDeviceCMYKColor(this));
            }

            @Override
            protected void processTextPosition(TextPosition text) {
                int rgb = 0;
                boolean invisible = false;
                try {
                    var state = getGraphicsState();
                    invisible = state.getTextState().getRenderingMode() == RenderingMode.NEITHER
                            || unmapped(text.getFont(), text.getCharacterCodes());
                    var color = state.getNonStrokingColor();
                    rgb = color == null ? 0 : color.toRGB();
                } catch (IOException | RuntimeException ignored) {
                    rgb = 0;
                }
                looks.put(text, new Look(rgb, invisible));
                super.processTextPosition(text);
            }

            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (positions.isEmpty() || text.isBlank() || spans.size() >= 200_000) {
                    return;
                }
                int start = 0;
                for (int i = 1; i <= positions.size(); i++) {
                    if (i == positions.size() || hidden(positions.get(i)) != hidden(positions.get(start))) {
                        List<TextPosition> run = positions.subList(start, i);
                        add(start == 0 && i == positions.size() ? text : joined(run), run);
                        start = i;
                    }
                }
            }

            private boolean hidden(TextPosition position) {
                Look look = looks.get(position);
                return look != null && look.invisible();
            }

            private void add(String text, List<TextPosition> positions) {
                if (text.isBlank()) {
                    return;
                }
                TextPosition first = positions.get(0);
                TextPosition end = positions.get(positions.size() - 1);
                float size = Math.max(1f, first.getHeightDir() / 0.7f);
                float fontSize = first.getFontSizeInPt();
                if (fontSize >= 1 && fontSize < size * 3) {
                    size = fontSize;
                }
                float width = end.getXDirAdj() + end.getWidthDirAdj() - first.getXDirAdj();
                Look look = looks.getOrDefault(first, new Look(0, false));
                String font = first.getFont() == null ? "" : fontName(first.getFont());
                String lower = font.toLowerCase(Locale.ROOT);
                boolean bold = lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
                        || first.getFont() != null && first.getFont().getFontDescriptor() != null
                                && first.getFont().getFontDescriptor().isForceBold();
                boolean italic = lower.contains("italic") || lower.contains("oblique")
                        || first.getFont() != null && first.getFont().getFontDescriptor() != null
                                && first.getFont().getFontDescriptor().isItalic();
                spans.add(new Span(text, first.getX(), first.getY(), size, width, angle(first, rotation),
                        look.invisible() ? -1 : look.rgb(), font, bold, italic, first.getFont()));
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(index + 1);
        stripper.setEndPage(index + 1);
        stripper.getText(doc);
        return spans;
    }

    private static String joined(List<TextPosition> positions) {
        StringBuilder sb = new StringBuilder();
        for (TextPosition position : positions) {
            sb.append(position.getUnicode());
        }
        return sb.toString();
    }

    private static boolean unmapped(PDFont font, int[] codes) throws IOException {
        if (font == null || codes == null || codes.length == 0) {
            return false;
        }
        for (int code : codes) {
            if (font.toUnicode(code) != null) {
                return false;
            }
        }
        return true;
    }

    private static float angle(TextPosition text, int rotation) {
        Matrix m = text.getTextMatrix();
        double degrees = rotation - Math.toDegrees(Math.atan2(m.getShearY(), m.getScaleX()));
        degrees = ((degrees % 360) + 540) % 360 - 180;
        float rounded = Math.round(degrees * 100) / 100f;
        return Math.abs(rounded) < 0.01f ? 0 : rounded;
    }

    private static String fontName(PDFont font) {
        String name = font.getName() == null ? "" : font.getName();
        int plus = name.indexOf('+');
        return plus == 6 ? name.substring(7) : name;
    }

    public static String family(String font) {
        String base = font.replaceAll("[,-].*$", "").replaceAll("(?i)(MT|PS|PSMT)$", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9 ]", "").strip();
        String lower = font.toLowerCase(Locale.ROOT);
        String generic = lower.contains("courier") || lower.contains("mono") || lower.contains("consol")
                ? "monospace"
                : lower.contains("times") || lower.contains("serif") && !lower.contains("sans")
                        || lower.contains("georgia") || lower.contains("garamond") || lower.contains("cambria")
                        || lower.contains("minion") || lower.contains("roman") ? "serif" : "sans-serif";
        return base.isEmpty() ? generic : "'" + base + "'," + generic;
    }

    private static void span(StringBuilder sb, Span s, boolean live, String embedded) {
        boolean hidden = !live || s.rgb() < 0;
        String text = s.text().replace("\u0000", "");
        if (text.isEmpty()) {
            return;
        }
        sb.append("<text x=\"").append(num(s.x())).append("\" y=\"").append(num(s.y())).append("\" font-size=\"")
                .append(num(s.size())).append('"');
        if (s.width() > 0.5f) {
            sb.append(" textLength=\"").append(num(s.width())).append("\" lengthAdjust=\"spacingAndGlyphs\"");
        }
        if (s.angle() != 0) {
            sb.append(" transform=\"rotate(").append(num(s.angle())).append(' ').append(num(s.x())).append(' ')
                    .append(num(s.y())).append(")\"");
        }
        sb.append(" style=\"");
        if (hidden) {
            sb.append("fill:transparent;font-family:'Courier New',monospace");
        } else {
            sb.append("font-family:").append(embedded != null ? "'" + embedded + "'," : "")
                    .append(family(s.font()).replace("\"", ""));
            if ((s.rgb() & 0xFFFFFF) != 0) {
                sb.append(";fill:").append(HtmlText.hex(s.rgb() & 0xFFFFFF));
            }
            if (s.bold() && embedded == null) {
                sb.append(";font-weight:bold");
            }
            if (s.italic() && embedded == null) {
                sb.append(";font-style:italic");
            }
        }
        sb.append("\">");
        HtmlText.escape(sb, text);
        sb.append("</text>\n");
    }

    private static String num(float v) {
        String t = String.format(Locale.ROOT, "%.2f", v);
        return t.contains(".") ? t.replaceAll("0+$", "").replaceAll("\\.$", "") : t;
    }

    private static void links(StringBuilder sb, PDPage page, PDRectangle box, int rotation, float width, float height)
            throws IOException {
        for (PDAnnotation a : page.getAnnotations()) {
            if (!(a instanceof PDAnnotationLink link)) {
                continue;
            }
            PDAction action = link.getAction();
            String url = action instanceof PDActionURI uri && uri.getURI() != null ? Links.safeUrl(uri.getURI()) : null;
            PDRectangle r = link.getRectangle();
            if (url == null || r == null) {
                continue;
            }
            float x0 = r.getLowerLeftX() - box.getLowerLeftX();
            float y0 = r.getLowerLeftY() - box.getLowerLeftY();
            float x1 = r.getUpperRightX() - box.getLowerLeftX();
            float y1 = r.getUpperRightY() - box.getLowerLeftY();
            float bw = box.getWidth();
            float bh = box.getHeight();
            float left;
            float top;
            float w;
            float h;
            switch (rotation) {
                case 90 -> {
                    left = y0;
                    top = x0;
                    w = y1 - y0;
                    h = x1 - x0;
                }
                case 180 -> {
                    left = bw - x1;
                    top = y0;
                    w = x1 - x0;
                    h = y1 - y0;
                }
                case 270 -> {
                    left = bh - y1;
                    top = bw - x1;
                    w = y1 - y0;
                    h = x1 - x0;
                }
                default -> {
                    left = x0;
                    top = bh - y1;
                    w = x1 - x0;
                    h = y1 - y0;
                }
            }
            if (w <= 0 || h <= 0 || left > width || top > height) {
                continue;
            }
            sb.append("<a href=\"");
            HtmlText.escape(sb, url);
            sb.append("\" style=\"left:").append(HtmlText.pt(left)).append(";top:").append(HtmlText.pt(top))
                    .append(";width:").append(HtmlText.pt(w)).append(";height:").append(HtmlText.pt(h))
                    .append("\"></a>\n");
        }
    }

    private static final class WithoutText extends PDFRenderer {

        WithoutText(PDDocument doc) {
            super(doc);
        }

        @Override
        protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
            return new PageDrawer(parameters) {
                @Override
                protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement)
                        throws IOException {
                    if (getGraphicsState().getTextState().getRenderingMode().isClip() || font.toUnicode(code) == null) {
                        super.showFontGlyph(matrix, font, code, displacement);
                    }
                }

                @Override
                protected void showType3Glyph(Matrix matrix, PDType3Font font, int code, Vector displacement)
                        throws IOException {
                    if (getGraphicsState().getTextState().getRenderingMode().isClip() || font.toUnicode(code) == null) {
                        super.showType3Glyph(matrix, font, code, displacement);
                    }
                }
            };
        }
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
