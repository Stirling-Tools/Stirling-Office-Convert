package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.flat.FlatOpc;
import stirling.software.officeconvert.topdf.sml.Sml2003Package;
import stirling.software.officeconvert.topdf.wordml.WordMlPackage;

/** Single-file XML Office documents: XML Spreadsheet 2003 is rewritten as SpreadsheetML and Word 2003 XML as a DOCX
 * package; a Flat OPC document (Word, Excel or PowerPoint 2007 XML) is unpacked into its package. Flat OpenDocument
 * files are read elsewhere. */
final class XmlInput {

    static final String UNKNOWN = "The XML file is not a Word, Excel, PowerPoint or OpenDocument document";

    private XmlInput() {}

    static Long estimate(Path source) throws IOException {
        if (Sml2003Package.is(source)) {
            return Sml2003Package.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
        }
        if (FlatOpc.is(source)) {
            return 4 * Files.size(source) + 4 * Admission.BASE_BYTES;
        }
        if (WordMlPackage.is(source)) {
            return WordMlPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
        }
        return null;
    }

    static Result render(Path source, OfficeToPdf.Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        if (Sml2003Package.is(source)) {
            return spreadsheet(source, sink, options, renderer);
        }
        if (FlatOpc.is(source)) {
            Path pkg = Files.createTempFile("office-to-pdf-", ".package");
            try {
                Admission.Ticket ticket = Admission.jvm().enter(4 * Files.size(source) + Admission.BASE_BYTES);
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(pkg), 1 << 16)) {
                    FlatOpc.unpack(source, os);
                } finally {
                    ticket.close();
                }
                OfficeToPdf.stopIfInterrupted();
                return OfficeToPdf.render(pkg, requested, sink, options, renderer);
            } finally {
                OfficeToPdf.deleteQuietly(pkg);
            }
        }
        if (WordMlPackage.is(source)) {
            return word2003(source, sink, options, renderer);
        }
        Path name = source.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            throw new IOException(UNKNOWN);
        }
        return null;
    }

    private static Result spreadsheet(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Path xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
        try {
            Sml2003Package.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(Sml2003Package.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                outcome = Sml2003Package.write(source, os, options.fontLibrary());
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(xlsx, OfficeToPdf.Format.XLSX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            for (String w : outcome.warnings()) {
                String c = RenderJob.clean(w);
                if (c != null && !warnings.contains(c)) {
                    warnings.add(c);
                }
            }
            return new Result(r.pages(), r.truncated() || outcome.lost(), warnings, r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(xlsx);
        }
    }

    private static Result word2003(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Path docx = Files.createTempFile("office-to-pdf-", ".docx");
        try {
            WordMlPackage.Outcome outcome;
            Admission.Ticket ticket = Admission.jvm().enter(WordMlPackage.estimate(Files.size(source)));
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(docx), 1 << 16)) {
                outcome = WordMlPackage.write(source, os);
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(docx, OfficeToPdf.Format.DOCX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            return merged(r, outcome.warnings(), outcome.lost());
        } finally {
            OfficeToPdf.deleteQuietly(docx);
        }
    }

    private static Result merged(Result r, List<String> extra, boolean lost) {
        List<String> warnings = new ArrayList<>(r.warnings());
        for (String w : extra) {
            String c = RenderJob.clean(w);
            if (c != null && !warnings.contains(c)) {
                warnings.add(c);
            }
        }
        return new Result(r.pages(), r.truncated() || lost, warnings, r.pageLimitReached());
    }
}
