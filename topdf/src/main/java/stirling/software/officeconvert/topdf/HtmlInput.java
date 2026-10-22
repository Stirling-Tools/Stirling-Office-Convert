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

    private HtmlInput() {}

    static boolean is(Path source, Format requested) throws IOException {
        if (requested != null && requested != Format.HTML && requested != Format.DOCX) {
            return false;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(source)) {
            head = in.readNBytes(512);
        }
        boolean zip = head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        boolean ole2 = head.length >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF;
        if (zip || ole2) {
            return false;
        }
        return requested == Format.HTML || named(source) || sniff(head);
    }

    static boolean named(Path source) {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml");
    }

    static Long estimate(Path source, Format requested) throws IOException {
        return is(source, requested) ? HtmlDocument.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES : null;
    }

    static Result render(Path source, Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        if (!is(source, requested)) {
            return null;
        }
        long bytes = Files.size(source);
        if (bytes > MAX_BYTES) {
            throw new IOException("The HTML file is larger than " + (MAX_BYTES >> 20) + " MB");
        }
        Path pkg = Files.createTempFile("office-to-pdf-", ".docx");
        try {
            HtmlDocument.Laid laid = lay(source, pkg, options, bytes, 1f);
            if (laid.fit() < 0.99f) {
                // content wider than the page: laid out again on larger paper, then shrunk to fit as browsers print
                laid = lay(source, pkg, options, bytes, laid.fit());
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

    private static HtmlDocument.Laid lay(Path source, Path pkg, Options options, long bytes, float zoom)
            throws IOException {
        Admission.Ticket ticket = Admission.jvm().enter(HtmlDocument.estimate(bytes));
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(pkg), 1 << 16)) {
            return HtmlDocument.lay(source, os, options.fontLibrary(), zoom);
        } finally {
            ticket.close();
        }
    }

    static boolean sniff(byte[] head) {
        String s = new String(head, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        return s.startsWith("<!doctype html") || s.startsWith("<html");
    }
}
