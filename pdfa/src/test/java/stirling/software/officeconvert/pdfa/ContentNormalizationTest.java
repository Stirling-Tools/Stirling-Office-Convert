package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentNormalizationTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "ABZC"})
    void malformedHexStringsInPropertiesAreWrittenAsValidStrings(String hex) throws Exception {
        RawPdf pdf = RawPdf.page("", "/Span <</ActualText <" + hex + ">>> BDC 0 0 50 50 re f EMC");
        Path input = Hostile.write(dir, "hex", pdf);
        Path output = dir.resolve("hex-out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            String content = new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("<" + hex + ">"), content);
            assertTrue(content.contains("ActualText"), content);
        }
    }

    @Test
    void subnormalContentRealsAreSerialisedAsZero() throws Exception {
        String tiny = "0." + "0".repeat(39) + "1";
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            Samples.raw(page, doc, tiny + " 0 m 10 10 l S");
            ContentFixer.run(ContentGraph.of(doc), PdfALevel.A2B, new Report(), new FontUsage(), new DeviceColours());
            String content = new String(page.getContents().readAllBytes(), StandardCharsets.US_ASCII);
            assertFalse(content.contains(tiny), content);
        }
    }

    @Test
    void identicalContentAndResourcesStaySharedAfterNormalisation() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage();
            PDPage second = new PDPage();
            doc.addPage(first);
            doc.addPage(second);
            PDResources resources = new PDResources();
            first.setResources(resources);
            second.setResources(resources);
            Samples.raw(first, doc, "0 0 50 50 re f");
            second.getCOSObject().setItem(COSName.CONTENTS, first.getCOSObject().getItem(COSName.CONTENTS));
            ContentFixer.run(ContentGraph.of(doc), PdfALevel.A2B, new Report(), new FontUsage(), new DeviceColours());
            assertSame(first.getCOSObject().getItem(COSName.CONTENTS), second.getCOSObject().getItem(COSName.CONTENTS));
        }
    }

    @Test
    void missingAndStringStructureTypesAreRepairedAndTheirChildrenVisited() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSDictionary root = new COSDictionary();
            COSDictionary parent = new COSDictionary();
            COSDictionary child = new COSDictionary();
            parent.setItem(COSName.TYPE, COSName.STRUCT_ELEM);
            child.setItem(COSName.TYPE, COSName.STRUCT_ELEM);
            child.setString(COSName.S, "P");
            parent.setItem(COSName.K, child);
            root.setItem(COSName.K, parent);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, root);
            Tagging.run(doc, PdfALevel.A2A, new Report());
            org.junit.jupiter.api.Assertions.assertEquals("NonStruct", parent.getNameAsString(COSName.S));
            org.junit.jupiter.api.Assertions.assertEquals("NonStruct", child.getNameAsString(COSName.S));
        }
    }

    @Test
    void markedContentLanguagesAndUnnamedLayersAreRepaired() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            Samples.raw(page, doc, "/P <</Lang (en_US!!)>> BDC 0 0 50 50 re f EMC");
            ContentFixer.run(ContentGraph.of(doc), PdfALevel.A2A, new Report(), new FontUsage(), new DeviceColours());
            String content = new String(page.getContents().readAllBytes(), StandardCharsets.US_ASCII);
            assertFalse(content.contains("Lang"), content);
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.OCG);
            org.apache.pdfbox.cos.COSArray groups = new org.apache.pdfbox.cos.COSArray();
            groups.add(group);
            COSDictionary properties = new COSDictionary();
            properties.setItem(COSName.OCGS, groups);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.OCPROPERTIES, properties);
            OptionalContent.configure(doc);
            assertTrue(group.getString(COSName.NAME).startsWith("Layer"));
        }
    }
}
