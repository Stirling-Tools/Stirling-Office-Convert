package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Format;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.html.HtmlDocument;
import stirling.software.officeconvert.topdf.text.Converted;

/** HTML is laid out as a Word package and drawn by the Word renderer. */
final class HtmlInput {

    static final long MAX_BYTES = 64L << 20;

    static final String PACKAGES_REFUSED = "The ZIP file is a package of separate files, which converts only when"
            + " packages are allowed (Options.allowPackages, --allow-packages)";

    private HtmlInput() {}

    static boolean is(Path source, Format requested) throws IOException {
        if (requested != null && requested != Format.HTML && requested != Format.DOCX) {
            return false;
        }
        byte[] head = head(source);
        if (zip(head) || ole2(head)) {
            return false;
        }
        return requested == Format.HTML || named(source) || sniff(head);
    }

    static boolean packaged(Path source, Format requested) throws IOException {
        if (requested != Format.HTML && requested != Format.EPUB && !zipNamed(source)) {
            return false;
        }
        return zip(head(source));
    }

    private static byte[] head(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            return in.readNBytes(512);
        }
    }

    private static boolean zip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    private static boolean ole2(byte[] head) {
        return head.length >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF;
    }

    private static boolean zipNamed(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".epub") || n.endsWith(".fbz") || n.endsWith(".htmlz");
    }

    static boolean named(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml");
    }

    static Long estimate(Path source, Format requested) throws IOException {
        if (MobiBook.is(source)) {
            return HtmlDocument.estimate(MAX_BYTES) + 3 * Files.size(source) + 2 * Admission.BASE_BYTES;
        }
        if (Fb2Book.is(source)) {
            return HtmlDocument.estimate(Files.size(source)) + 4 * Files.size(source) + 2 * Admission.BASE_BYTES;
        }
        if (packaged(source, requested)) {
            return HtmlDocument.estimate(MAX_BYTES) + 2 * Admission.BASE_BYTES;
        }
        return is(source, requested) ? HtmlDocument.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES : null;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        if (requested == null || requested == Format.HTML) {
            if (MobiBook.is(source)) {
                return renderBook(read(source, 3, MobiBook::read), sink, options, renderer);
            }
            if (Fb2Book.is(source)) {
                return renderBook(read(source, 4, Fb2Book::read), sink, options, renderer);
            }
            if (Fb2Book.named(source)) {
                throw new IOException("The file is not a FictionBook (FB2) book");
            }
            if (MobiBook.named(source)) {
                throw new IOException(MobiBook.unsupported(head(source)));
            }
        }
        if (packaged(source, requested)) {
            return renderPackage(source, requested, sink, options, renderer);
        }
        if (!is(source, requested)) {
            return null;
        }
        return render(source, sink, options, renderer, options.htmlAssets());
    }

    private static Result renderPackage(Path source, Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        try (HtmlPackage pkg = HtmlPackage.open(source)) {
            boolean book = EpubBook.is(pkg);
            if (requested == Format.EPUB && !book) {
                throw new IOException("The file is not an EPUB book: it has no META-INF/container.xml");
            }
            if (book && (requested == Format.EPUB || options.allowPackages())) {
                return renderBook(pkg, sink, options, renderer);
            }
            String fb2 = pkg.page() == null && (options.allowPackages() || compressedFictionBook(source))
                    ? fictionBook(pkg) : null;
            if (fb2 != null) {
                byte[] xml = pkg.read(fb2);
                if (xml == null) {
                    throw new IOException("The FictionBook in the ZIP file is larger than "
                            + (HtmlAssets.MAX_ASSET_BYTES >> 20) + " MB");
                }
                return renderBook(Fb2Book.read(xml), sink, options, renderer);
            }
            if (!options.allowPackages() && !htmlBook(source)) {
                throw new IOException(PACKAGES_REFUSED);
            }
            if (pkg.page() == null) {
                throw new IOException("The ZIP file holds no HTML page");
            }
            if (pkg.pageSize() > MAX_BYTES) {
                throw new IOException("The HTML file is larger than " + (MAX_BYTES >> 20) + " MB");
            }
            Path page = Files.createTempFile("office-to-pdf-", ".html");
            try {
                pkg.extractPage(page);
                return render(page, sink, options, renderer, pkg);
            } finally {
                OfficeToPdf.deleteQuietly(page);
            }
        }
    }

    private static Result renderBook(HtmlPackage pkg, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        EpubBook.Book book = EpubBook.read(pkg);
        pkg.page(Ebook.PAGE);
        return renderBook(new Ebook(book, EpubBook.fonts(pkg)), sink, options, renderer);
    }

    private static Result renderBook(Ebook ebook, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Path page = Files.createTempFile("office-to-pdf-", ".html");
        try {
            Files.writeString(page, ebook.book().html(), StandardCharsets.UTF_8);
            return render(page, sink, BookFonts.with(options, ebook.book().fonts(), ebook.assets()), renderer,
                    ebook.assets());
        } finally {
            OfficeToPdf.deleteQuietly(page);
        }
    }

    static Result renderPage(String html, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Path page = Files.createTempFile("office-to-pdf-", ".html");
        try {
            Files.writeString(page, html, StandardCharsets.UTF_8);
            return render(page, sink, options, renderer, HtmlAssets.NONE);
        } finally {
            OfficeToPdf.deleteQuietly(page);
        }
    }

    private interface BookReader {
        Ebook read(Path source) throws IOException;
    }

    private static Ebook read(Path source, int factor, BookReader reader) throws IOException {
        Admission.Ticket ticket = Admission.jvm().enter(factor * Files.size(source) + Admission.BASE_BYTES);
        try {
            return reader.read(source);
        } finally {
            ticket.close();
        }
    }

    private static boolean htmlBook(Path source) {
        Path name = source.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".htmlz");
    }

    private static boolean compressedFictionBook(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".fbz") || n.endsWith(".fb2.zip");
    }

    private static String fictionBook(HtmlPackage pkg) {
        String found = null;
        for (String name : pkg.names()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".fb2") && !lower.startsWith("__macosx/") && HtmlAssets.packagePath(name)) {
                if (found != null) {
                    return null;
                }
                found = name;
            }
        }
        return found;
    }

    private static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer,
            HtmlAssets assets) throws IOException {
        long bytes = Files.size(source);
        if (bytes > MAX_BYTES) {
            throw new IOException("The HTML file is larger than " + (MAX_BYTES >> 20) + " MB");
        }
        HtmlAssets once = HtmlDocument.readOnce(assets);
        Path pkg = Files.createTempFile("office-to-pdf-", ".docx");
        try {
            HtmlDocument.Laid laid = lay(source, pkg, options, bytes, 1f, once);
            if (laid.fit() < 0.99f) {
                // content wider than the page: laid out again on larger paper, then shrunk to fit as browsers print
                laid = lay(source, pkg, options, bytes, laid.fit(), once);
            }
            OfficeToPdf.stopIfInterrupted();
            if (laid.fit() >= 0.99f) {
                return Rewritten.render(pkg, source, laid.converted(), sink, options, renderer);
            }
            Path wide = Files.createTempFile("office-to-pdf-", ".pdf");
            try {
                Result r;
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(wide), 1 << 16)) {
                    r = Rewritten.render(pkg, source, laid.converted(), os, options, renderer);
                }
                PageScale.shrink(wide, laid.fit(), sink);
                return r;
            } finally {
                OfficeToPdf.deleteQuietly(wide);
            }
        } finally {
            OfficeToPdf.deleteQuietly(pkg);
        }
    }

    private static HtmlDocument.Laid lay(Path source, Path pkg, Options options, long bytes, float zoom,
            HtmlAssets assets) throws IOException {
        Admission.Ticket ticket = Admission.jvm().enter(HtmlDocument.estimate(bytes));
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(pkg), 1 << 16)) {
            return HtmlDocument.lay(source, os, options.fontLibrary(), zoom, assets);
        } finally {
            ticket.close();
        }
    }

    static boolean sniff(byte[] head) {
        String s = new String(head, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        return s.startsWith("<!doctype html") || s.startsWith("<html");
    }
}
