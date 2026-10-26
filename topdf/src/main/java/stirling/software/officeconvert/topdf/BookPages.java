package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;

final class BookPages {

    static final int MAX_ENTRIES = 5_000;

    private static final int MAX_DEPTH = 16;

    private static final float NUMBER_SIZE = 9f;

    private static final float NUMBER_RISE = 20f;

    private static final String NUMBER_FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    record Entry(String title, int depth, int page) {}

    private BookPages() {}

    static boolean wanted(Options options) {
        return options.tableOfContents() || options.pageNumbers();
    }

    static Result render(Path source, OfficeToPdf.Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        Options plain = options.tableOfContents(false).pageNumbers(false);
        Path laid = Files.createTempFile("office-to-pdf-", ".pdf");
        Path contents = null;
        try {
            Result result;
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(laid), 1 << 16)) {
                result = OfficeToPdf.render(source, requested, os, plain, renderer);
            }
            String marker = "https://contents-" + UUID.randomUUID() + ".invalid/";
            List<Entry> entries = options.tableOfContents() ? outline(laid) : List.of();
            if (!entries.isEmpty()) {
                OfficeToPdf.stopIfInterrupted();
                contents = Files.createTempFile("office-to-pdf-", ".pdf");
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(contents), 1 << 16)) {
                    HtmlInput.renderPage(contents(entries, marker), os, plain, renderer);
                }
            }
            int added = finish(laid, contents, marker, options.pageNumbers(), sink);
            return added == 0 ? result
                    : new Result(result.pages() + added, result.truncated(), result.warnings(), result.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(laid);
            if (contents != null) {
                OfficeToPdf.deleteQuietly(contents);
            }
        }
    }

    static List<Entry> outline(Path pdf) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                collect(doc, outline, 0, out);
            }
        }
        return out;
    }

    private static void collect(PDDocument doc, PDOutlineNode node, int depth, List<Entry> out) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (PDOutlineItem item : node.children()) {
            if (out.size() >= MAX_ENTRIES) {
                return;
            }
            OfficeToPdf.stopIfInterrupted();
            PDPage page = item.findDestinationPage(doc);
            int index = page == null ? -1 : doc.getPages().indexOf(page);
            String title = item.getTitle() == null ? "" : item.getTitle().strip();
            if (index >= 0 && !title.isEmpty()) {
                out.add(new Entry(title, depth, index));
            }
            collect(doc, item, depth + 1, out);
        }
    }

    static String contents(List<Entry> entries, String marker) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Contents</title></head><body>")
                .append("<h1 style=\"font-size:1.6em;margin:0 0 1em\">Contents</h1>");
        for (Entry e : entries) {
            html.append("<p style=\"margin:2pt 0 2pt ").append(1.5f * Math.min(e.depth(), 8)).append("em\">")
                    .append("<span style=\"float:right;padding-left:1em\">").append(e.page() + 1).append("</span>")
                    .append("<a href=\"").append(marker).append(e.page())
                    .append("\" style=\"color:inherit;text-decoration:none\">").append(escape(e.title()))
                    .append("</a></p>");
        }
        return html.append("</body></html>").toString();
    }

    private static int finish(Path laid, Path contents, String marker, boolean numbers, OutputStream out)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(laid.toFile(), IOUtils.createTempFileOnlyStreamCache());
                PDDocument toc = contents == null ? null
                        : Loader.loadPDF(contents.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int bookPages = doc.getNumberOfPages();
            int added = 0;
            if (toc != null && toc.getNumberOfPages() > 0) {
                for (PDPage page : toc.getPages()) {
                    OfficeToPdf.stopIfInterrupted();
                    PDPage imported = doc.importPage(page);
                    link(doc, imported, marker, bookPages);
                    added++;
                }
                bookmark(doc, doc.getPage(bookPages));
            }
            if (numbers) {
                number(doc);
            }
            doc.save(out);
            return added;
        }
    }

    private static void link(PDDocument doc, PDPage page, String marker, int bookPages) throws IOException {
        for (PDAnnotation a : page.getAnnotations()) {
            if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri
                    && uri.getURI() != null && uri.getURI().startsWith(marker)) {
                int target = target(uri.getURI().substring(marker.length()), bookPages);
                link.setAction(null);
                if (target >= 0) {
                    PDPageFitWidthDestination d = new PDPageFitWidthDestination();
                    d.setPage(doc.getPage(target));
                    d.setTop((int) doc.getPage(target).getCropBox().getUpperRightY());
                    link.setDestination(d);
                }
            }
        }
    }

    private static void bookmark(PDDocument doc, PDPage first) {
        PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
        }
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle("Contents");
        PDPageFitWidthDestination d = new PDPageFitWidthDestination();
        d.setPage(first);
        d.setTop((int) first.getCropBox().getUpperRightY());
        item.setDestination(d);
        outline.addLast(item);
    }

    private static void number(PDDocument doc) throws IOException {
        PDType0Font font;
        try (InputStream in = PDDocument.class.getResourceAsStream(NUMBER_FONT)) {
            if (in == null) {
                throw new IOException("The page number font is missing");
            }
            font = PDType0Font.load(doc, in);
        }
        int n = 0;
        for (PDPage page : doc.getPages()) {
            OfficeToPdf.stopIfInterrupted();
            String label = Integer.toString(++n);
            PDRectangle box = page.getCropBox();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            boolean turned = rotation == 90 || rotation == 270;
            float width = turned ? box.getHeight() : box.getWidth();
            float textWidth = font.getStringWidth(label) / 1000f * NUMBER_SIZE;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND,
                    true, true)) {
                cs.transform(upright(box, rotation));
                cs.beginText();
                cs.setFont(font, NUMBER_SIZE);
                cs.newLineAtOffset((width - textWidth) / 2f, NUMBER_RISE);
                cs.showText(label);
                cs.endText();
            }
        }
    }

    private static Matrix upright(PDRectangle box, int rotation) {
        float x = box.getLowerLeftX();
        float y = box.getLowerLeftY();
        return switch (rotation) {
            case 90 -> new Matrix(0, 1, -1, 0, x + box.getWidth(), y);
            case 180 -> new Matrix(-1, 0, 0, -1, x + box.getWidth(), y + box.getHeight());
            case 270 -> new Matrix(0, -1, 1, 0, x, y + box.getHeight());
            default -> new Matrix(1, 0, 0, 1, x, y);
        };
    }

    private static int target(String page, int pages) {
        try {
            int n = Integer.parseInt(page);
            return n >= 0 && n < pages ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
