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
import stirling.software.officeconvert.topdf.grid.Dbf;
import stirling.software.officeconvert.topdf.grid.Dif;
import stirling.software.officeconvert.topdf.grid.Grid;
import stirling.software.officeconvert.topdf.grid.GridPackage;
import stirling.software.officeconvert.topdf.grid.Sylk;

/** Simple one-sheet table formats Excel opens (SYLK, DIF, dBASE), read into a grid and drawn as a workbook. */
final class GridInput {

    private enum Kind { SYLK, DIF, DBF }

    private GridInput() {}

    private static Kind kind(Path source) throws IOException {
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        String ext = n.lastIndexOf('.') < 0 ? "" : n.substring(n.lastIndexOf('.') + 1);
        boolean text = ext.equals("csv") || ext.equals("txt") || ext.equals("tsv") || ext.equals("tab");
        if (ext.equals("slk") || ext.equals("sylk") || !text && Sylk.is(source)) {
            return Kind.SYLK;
        }
        if (!text && Dif.is(source)) {
            return Kind.DIF;
        }
        if (ext.equals("dbf") && Dbf.is(source)) {
            return Kind.DBF;
        }
        if (ext.equals("dbf")) {
            throw new IOException("The file is not a dBASE table that can be read");
        }
        return null;
    }

    static Long estimate(Path source) throws IOException {
        return kind(source) == null ? null : GridPackage.estimate(Files.size(source)) + 2 * Admission.BASE_BYTES;
    }

    static Result render(Path source, OutputStream sink, Options options, OfficeToPdf.Renderer renderer)
            throws IOException {
        Kind kind = kind(source);
        if (kind == null) {
            return null;
        }
        Path xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
        try {
            Grid grid;
            Admission.Ticket ticket = Admission.jvm().enter(GridPackage.estimate(Files.size(source)));
            try {
                grid = switch (kind) {
                    case SYLK -> Sylk.read(source);
                    case DIF -> Dif.read(source);
                    case DBF -> Dbf.read(source);
                };
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                    GridPackage.write(grid, TextInput.sheetName(source, options), os);
                }
            } finally {
                ticket.close();
            }
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(xlsx, OfficeToPdf.Format.XLSX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            if (grid.truncated()) {
                warnings.add("The table is too large; only its first rows were converted");
            }
            return new Result(r.pages(), r.truncated() || grid.truncated(), warnings, r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(xlsx);
        }
    }
}
