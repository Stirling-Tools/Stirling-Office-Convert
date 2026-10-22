package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.thumbnail.OleThumbnail;
import stirling.software.officeconvert.topdf.vsdx.VsdxPackage;

final class LegacyDrawingInput {

    private LegacyDrawingInput() {}

    static boolean named(Path source) {
        return switch (extension(source)) {
            case "pub", "vsd", "vss", "vst", "vsw" -> true;
            default -> false;
        };
    }

    static Long estimate(Path source) throws IOException {
        OleThumbnail.Kind kind = OleThumbnail.kind(source);
        if (kind == null) {
            refuseNamed(source);
            return null;
        }
        return footprint(source);
    }

    private static void refuseNamed(Path source) throws IOException {
        if (named(source) && !VsdxPackage.is(source)) {
            String reason = UnsupportedFormats.byContent(source);
            throw new IOException(reason != null ? reason : UnsupportedFormats.byExtension(extension(source)));
        }
    }

    private static long footprint(Path source) throws IOException {
        return Footprint.legacy(OleThumbnail.summaryBytes(source)) + Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options) throws IOException {
        OleThumbnail.Kind kind = OleThumbnail.kind(source);
        if (kind == null) {
            refuseNamed(source);
            return null;
        }
        try (Admission.Ticket _ = Admission.jvm().enter(footprint(source));
                PdfOutput output = new PdfOutput(options.fontLibrary(), options.maxScratchBytes())) {
            OleThumbnail.Outcome drawn = OleThumbnail.draw(source, output);
            String what = kind == OleThumbnail.Kind.PUBLISHER ? "Microsoft Publisher publication"
                    : "Visio 2003-2010 drawing";
            String whole = kind == OleThumbnail.Kind.PUBLISHER ? "save the publication as PDF from Publisher"
                    : "save the drawing as .vsdx or as PDF from Visio";
            if (drawn == OleThumbnail.Outcome.NO_PREVIEW) {
                throw new IOException("The " + what + " holds no preview picture, and its own format cannot be read; "
                        + whole);
            }
            if (drawn == OleThumbnail.Outcome.TOO_SMALL) {
                throw new IOException("The " + what + " holds only a thumbnail too small to read, and its own format"
                        + " cannot be read; " + whole);
            }
            OfficeToPdf.stopIfInterrupted();
            output.save(sink);
            return new Result(1, true, List.of("Only the first page's preview could be converted: the " + what
                    + " was drawn from the preview picture it holds; " + whole + " for the whole document"), false);
        }
    }

    private static String extension(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }
}
