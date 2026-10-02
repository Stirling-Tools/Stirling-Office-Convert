package stirling.software.officeconvert.topdf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.OfficeToPdf.Options;
import stirling.software.officeconvert.topdf.OfficeToPdf.Result;
import stirling.software.officeconvert.topdf.grid.Dbf;
import stirling.software.officeconvert.topdf.grid.Dif;
import stirling.software.officeconvert.topdf.grid.Grid;
import stirling.software.officeconvert.topdf.grid.GridPackage;
import stirling.software.officeconvert.topdf.grid.Sylk;
import stirling.software.officeconvert.topdf.io.SourceFile;
import stirling.software.officeconvert.topdf.lotus.Lotus;
import stirling.software.officeconvert.topdf.text.TextFormats;

/** Simple table formats Excel opens (SYLK, DIF, dBASE, Lotus 1-2-3), read into grids and drawn as a workbook. */
final class GridInput {

    private enum Kind { SYLK, DIF, DBF, LOTUS }

    private GridInput() {}

    private static Kind kind(Path source, OfficeToPdf.Format requested) throws IOException {
        if (requested == OfficeToPdf.Format.TEXT || requested == OfficeToPdf.Format.CSV
                || requested == OfficeToPdf.Format.TSV) {
            return null;
        }
        Path name = source.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        String ext = n.lastIndexOf('.') < 0 ? "" : n.substring(n.lastIndexOf('.') + 1);
        boolean text = TextFormats.kind(ext) != null;
        if (!text && Lotus.is(source)) {
            return Kind.LOTUS;
        }
        if (OfficeToPdf.container(source)) {
            return null;
        }
        if (ext.equals("wk1") || ext.equals("wks") || ext.equals("wk3") || ext.equals("wk4") || ext.equals("123")) {
            String reason = UnsupportedFormats.byContent(source);
            throw new IOException(reason != null ? reason : UnsupportedFormats.byExtension("wk!"));
        }
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

    private static List<GridPackage.Sheet> named(List<GridPackage.Sheet> sheets, String name) {
        if (sheets.size() == 1 && sheets.get(0).name() == null) {
            return List.of(new GridPackage.Sheet(name, sheets.get(0).grid()));
        }
        return sheets;
    }

    private static final long CELL_BYTES = 128;

    static Long estimate(Path source) throws IOException {
        Kind kind = kind(source, null);
        return kind == null ? null : estimate(source, kind) + 2 * Admission.BASE_BYTES;
    }

    private static long estimate(Path source, Kind kind) throws IOException {
        long size = SourceFile.size(source);
        long bytes = GridPackage.estimate(size);
        return kind == Kind.DBF ? Math.max(bytes, Admission.BASE_BYTES + 2 * size + CELL_BYTES * dbaseCells(source))
                : bytes;
    }

    static long dbaseCells(Path source) throws IOException {
        byte[] h = SourceFile.head(source, 12);
        if (h.length < 12) {
            return 0;
        }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        long records = b.getInt(4) & 0xFFFFFFFFL;
        int header = b.getShort(8) & 0xFFFF;
        int record = b.getShort(10) & 0xFFFF;
        long fields = Math.max(0, (header - 33) / 32);
        long stored = record == 0 ? 0 : Math.max(0, SourceFile.size(source) - header) / record;
        return Math.min(Grid.MAX_CELLS, Math.min(records, stored) * fields);
    }

    static Result render(Path source, OfficeToPdf.Format requested, OutputStream sink, Options options,
            OfficeToPdf.Renderer renderer) throws IOException {
        Kind kind = kind(source, requested);
        if (kind == null) {
            return null;
        }
        Path xlsx = Files.createTempFile("office-to-pdf-", ".xlsx");
        try {
            List<GridPackage.Sheet> sheets;
            Admission.Ticket ticket = Admission.jvm().enter(estimate(source, kind));
            try {
                String name = TextInput.sheetName(options);
                sheets = switch (kind) {
                    case SYLK -> List.of(new GridPackage.Sheet(name, Sylk.read(source)));
                    case DIF -> List.of(new GridPackage.Sheet(name, Dif.read(source)));
                    case DBF -> List.of(new GridPackage.Sheet(name, Dbf.read(source)));
                    case LOTUS -> named(Lotus.read(source), name);
                };
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(xlsx), 1 << 16)) {
                    GridPackage.write(sheets, os);
                }
            } finally {
                ticket.close();
            }
            boolean truncated = sheets.stream().anyMatch(s -> s.grid().truncated());
            OfficeToPdf.stopIfInterrupted();
            Result r = OfficeToPdf.render(xlsx, OfficeToPdf.Format.XLSX, sink, options,
                    (s, job) -> renderer.render(source, job), OfficeToPdf.REWRITTEN);
            List<String> warnings = new ArrayList<>(r.warnings());
            if (truncated) {
                warnings.add("The table is too large; only its first rows were converted");
            }
            Set<String> lost = new LinkedHashSet<>();
            sheets.forEach(s -> lost.addAll(s.grid().warnings()));
            warnings.addAll(lost);
            return new Result(r.pages(), r.truncated() || truncated || !lost.isEmpty(), warnings,
                    r.pageLimitReached());
        } finally {
            OfficeToPdf.deleteQuietly(xlsx);
        }
    }
}
