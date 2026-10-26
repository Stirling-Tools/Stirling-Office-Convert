package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

final class BookPages {

    static final int MAX_ENTRIES = 5_000;

    private static final int MAX_DEPTH = 16;

    private static final String PAGE_NUMBERS = "<style>@page{@bottom-center{content:counter(page);font-size:9pt}}"
            + "</style>";

    record Entry(String title, int depth, int page) {}

    private BookPages() {}

    static String withPageNumbers(String html) {
        int head = html.indexOf("<head>");
        return head < 0 ? PAGE_NUMBERS + html
                : html.substring(0, head + 6) + PAGE_NUMBERS + html.substring(head + 6);
    }

    static List<Entry> outline(Path pdf) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            var outline = doc.getDocumentCatalog().getDocumentOutline();
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

    static String marker() {
        return "https://contents-" + UUID.randomUUID() + ".invalid/";
    }

    static String withContents(String html, List<Entry> entries, String marker) {
        StringBuilder toc = new StringBuilder(4096);
        toc.append("<section style=\"page-break-before:always\"><h1 style=\"font-size:1.6em;margin:0 0 1em\">Contents")
                .append("</h1>");
        for (Entry e : entries) {
            toc.append("<p style=\"margin:2pt 0 2pt ").append(1.5f * Math.min(e.depth(), 8)).append("em\">")
                    .append("<span style=\"float:right;padding-left:1em\">").append(e.page() + 1).append("</span>")
                    .append("<a href=\"").append(marker).append(e.page())
                    .append("\" style=\"color:inherit;text-decoration:none\">").append(escape(e.title()))
                    .append("</a></p>");
        }
        toc.append("</section>");
        int end = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        return end < 0 ? html + toc : html.substring(0, end) + toc + html.substring(end);
    }

    static void linkContents(Path pdf, String marker, OutputStream out) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int pages = doc.getNumberOfPages();
            for (PDPage page : doc.getPages()) {
                for (PDAnnotation a : page.getAnnotations()) {
                    if (a instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI uri
                            && uri.getURI() != null && uri.getURI().startsWith(marker)) {
                        int target = target(uri.getURI().substring(marker.length()), pages);
                        link.setAction(null);
                        if (target >= 0) {
                            PDPageFitWidthDestination d = new PDPageFitWidthDestination();
                            d.setPage(doc.getPage(target));
                            d.setTop((int) doc.getPage(target).getMediaBox().getUpperRightY());
                            link.setDestination(d);
                        }
                    }
                }
            }
            doc.save(out);
        }
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
