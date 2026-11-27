package stirling.software.officeconvert;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.extract.FontGuard;
import stirling.software.officeconvert.extract.PdfFiles;
import stirling.software.officeconvert.html.FixedHtml;
import stirling.software.officeconvert.jpx.JpxImageIO;
import stirling.software.officeconvert.sink.KeepOpen;
import stirling.software.officeconvert.svg.SvgPages;

public final class PdfToSvg {

    public record Options(int firstPage, int lastPage, String password, FixedHtml.FontSource fonts) {

        public static Options defaults() {
            return new Options(1, Integer.MAX_VALUE, null, null);
        }

        public Options pages(int first, int last) {
            return new Options(first, last, password, fonts);
        }

        public Options password(String secret) {
            return new Options(firstPage, lastPage, secret, fonts);
        }

        public Options fonts(FixedHtml.FontSource source) {
            return new Options(firstPage, lastPage, password, source);
        }
    }

    public record Result(int pages, int textGlyphs, int pathGlyphs, int invisibleGlyphs) {

        Result plus(SvgPages.Page p) {
            return new Result(pages + 1, textGlyphs + p.textGlyphs(), pathGlyphs + p.pathGlyphs(),
                    invisibleGlyphs + p.invisibleGlyphs());
        }
    }

    private PdfToSvg() {}

    public static Result convert(Path pdf, Path target, Options options) throws IOException {
        PdfFiles.checkSize(pdf);
        PdfFiles.outputFolder(target);
        try (PDDocument doc = PdfFiles.open(pdf, options.password())) {
            int[] range = PdfFiles.pageRange(doc.getNumberOfPages(), options.firstPage(), options.lastPage());
            String name = target.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return PdfFiles.writeThenMove(target, ".office-convert-", part -> {
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                        return writeZip(doc, os, options);
                    }
                });
            }
            if (range[0] == range[1]) {
                return PdfFiles.writeThenMove(target, ".office-convert-", part -> {
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                        return writePage(doc, range[0], os, options);
                    }
                });
            }
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            Result total = new Result(0, 0, 0, 0);
            SvgPages pages = pages(doc, options);
            try {
                for (int i = range[0]; i <= range[1]; i++) {
                    int index = i;
                    Path page = target.resolveSibling(stem + "-" + (i + 1) + ".svg");
                    SvgPages.Page p = PdfFiles.writeThenMove(page, ".office-convert-", part -> {
                        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(part), 1 << 16)) {
                            return pages.write(index, os);
                        }
                    });
                    total = total.plus(p);
                }
            } finally {
                pages.close();
            }
            return total;
        }
    }

    public static Result writePage(PDDocument doc, int pageIndex, OutputStream out, Options options) throws IOException {
        SvgPages pages = pages(doc, options);
        try {
            return new Result(0, 0, 0, 0).plus(pages.write(pageIndex, new KeepOpen(out)));
        } finally {
            pages.close();
        }
    }

    public static Result writeZip(PDDocument doc, OutputStream out, Options options) throws IOException {
        int[] range = PdfFiles.pageRange(doc.getNumberOfPages(), options.firstPage(), options.lastPage());
        int digits = String.valueOf(range[1] + 1).length();
        Result total = new Result(0, 0, 0, 0);
        SvgPages pages = pages(doc, options);
        try (ZipOutputStream zip = new ZipOutputStream(new KeepOpen(out), StandardCharsets.UTF_8)) {
            for (int i = range[0]; i <= range[1]; i++) {
                zip.putNextEntry(new ZipEntry("page-" + pad(i + 1, digits) + ".svg"));
                total = total.plus(pages.write(i, new KeepOpen(zip)));
                zip.closeEntry();
            }
        } finally {
            pages.close();
        }
        return total;
    }

    private static SvgPages pages(PDDocument doc, Options options) throws IOException {
        PdfFiles.checkOpen(doc);
        JpxImageIO.install();
        FontGuard.check(doc);
        return new SvgPages(doc, options.fonts());
    }

    private static String pad(int n, int digits) {
        String s = Integer.toString(n);
        return "0".repeat(Math.max(0, digits - s.length())) + s;
    }
}
