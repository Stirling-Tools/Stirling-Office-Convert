package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureCheckTest {

    @TempDir
    Path dir;

    private interface Edit {
        void apply(PDDocument d, COSDictionary paragraph) throws Exception;
    }

    private Path tagged(String name, Edit edit) throws Exception {
        Path base = Samples.write(dir, "s21_tagged");
        Path out = dir.resolve(name + ".pdf");
        try (PDDocument d = Loader.loadPDF(base.toFile())) {
            COSDictionary root = d.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
            COSDictionary doc = (COSDictionary) root.getDictionaryObject(COSName.K);
            COSDictionary para = (COSDictionary) ((COSArray) doc.getDictionaryObject(COSName.K)).getObject(0);
            edit.apply(d, para);
            d.save(out.toFile());
        }
        return out;
    }

    private static void append(PDDocument d, String content) throws IOException {
        PDStream s = new PDStream(d);
        try (OutputStream o = s.createOutputStream()) {
            o.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        COSDictionary page = d.getPage(0).getCOSObject();
        COSArray contents = (COSArray) page.getDictionaryObject(COSName.CONTENTS);
        contents.add(s.getCOSObject());
    }

    private static String content(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile()); InputStream in = d.getPage(0).getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private IOException refused(Path in) {
        return assertThrows(IOException.class, () -> PdfToPdfA.convert(in, dir.resolve("refused.pdf"),
                PdfToPdfA.Options.defaults().level(PdfALevel.A2A)));
    }

    private Path converted(Path in) throws Exception {
        Path out = dir.resolve("a-" + in.getFileName());
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2A));
        VeraPdf.assertCompliant(out, PdfALevel.A2A);
        return out;
    }

    @Test
    void untaggedTextIsRefusedWithThePage() throws Exception {
        Path in = tagged("untagged-text", (d, p) -> append(d, "BT /F1 12 Tf 50 700 Td (loose) Tj ET"));
        IOException e = refused(in);
        assertTrue(e.getMessage().contains("1 piece of text outside the structure tree (page 1)"), e.getMessage());
        assertTrue(e.getMessage().contains("use level b or u"), e.getMessage());
    }

    @Test
    void aFigureWithoutAlternativeTextIsRefused() throws Exception {
        Path in = tagged("figure", (d, p) -> p.setItem(COSName.S, COSName.getPDFName("Figure")));
        IOException e = refused(in);
        assertTrue(e.getMessage().contains("1 figure without alternative text (page 1)"), e.getMessage());
        Path fixed = tagged("figure-alt", (d, p) -> {
            p.setItem(COSName.S, COSName.getPDFName("Figure"));
            p.setString(COSName.getPDFName("Alt"), "A paragraph drawn as a picture");
        });
        converted(fixed);
    }

    @Test
    void untaggedDrawingsBecomeArtifacts() throws Exception {
        Path in = tagged("drawing", (d, p) -> append(d, "0 0 1 rg 10 10 50 50 re f"));
        Path out = converted(in);
        assertTrue(content(out).replaceAll("\\s+", " ").contains("/Artifact BMC 10 10 50 50 re f EMC"),
                content(out));
    }

    @Test
    void orphanedMarkedDrawingsBecomeArtifactsAndOrphanedTextIsRefused() throws Exception {
        Path drawing = tagged("orphan-drawing",
                (d, p) -> append(d, "/Shape <</MCID 7>> BDC 0 0 1 rg 10 10 50 50 re f EMC"));
        assertTrue(content(converted(drawing)).replaceAll("\\s+", " ").contains("/Artifact BMC 0 0 1 rg"));
        Path text = tagged("orphan-text",
                (d, p) -> append(d, "/Span <</MCID 7>> BDC BT /F1 12 Tf 50 700 Td (lost) Tj ET EMC"));
        IOException e = refused(text);
        assertTrue(e.getMessage().contains("1 marked content item that no structure element refers to (page 1)"),
                e.getMessage());
    }

    @Test
    void theLanguageComesFromTheStructureAndTheParentTreeIsRebuilt() throws Exception {
        Path in = tagged("language", (d, p) -> {
            d.getDocumentCatalog().getCOSObject().removeItem(COSName.LANG);
            ((COSDictionary) p.getDictionaryObject(COSName.P)).setString(COSName.LANG, "de-DE");
        });
        Path out = converted(in);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals("de-DE", d.getDocumentCatalog().getLanguage());
            COSDictionary root = d.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
            COSDictionary tree = (COSDictionary) root.getDictionaryObject(COSName.PARENT_TREE);
            COSArray nums = (COSArray) tree.getDictionaryObject(COSName.NUMS);
            int key = d.getPage(0).getCOSObject().getInt(COSName.STRUCT_PARENTS);
            assertEquals(key, nums.getInt(0));
            COSDictionary doc = (COSDictionary) root.getDictionaryObject(COSName.K);
            assertSame(((COSArray) doc.getDictionaryObject(COSName.K)).getObject(0),
                    ((COSArray) nums.getObject(1)).getObject(0));
        }
    }

    @Test
    void partOneMapsStructureTypesAddedAfterPdf14() throws Exception {
        Path in = tagged("tbody", (d, p) -> p.setItem(COSName.S, COSName.getPDFName("TBody")));
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1A, PdfALevel.A2A}) {
            Path out = dir.resolve("tbody-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                COSDictionary roles = (COSDictionary) d.getDocumentCatalog().getStructureTreeRoot().getCOSObject()
                        .getDictionaryObject(COSName.getPDFName("RoleMap"));
                assertEquals(level.part() == 1 ? "NonStruct" : null, roles.getNameAsString("TBody"));
            }
        }
    }

    @Test
    void aDocumentWithNoLanguageAnywhereIsRefused() throws Exception {
        Path in = tagged("no-language", (d, p) -> d.getDocumentCatalog().getCOSObject().removeItem(COSName.LANG));
        IOException e = refused(in);
        assertTrue(e.getMessage().contains("no document language"), e.getMessage());
    }
}
