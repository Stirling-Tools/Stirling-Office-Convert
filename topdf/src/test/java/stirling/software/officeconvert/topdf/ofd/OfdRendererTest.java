package stirling.software.officeconvert.topdf.ofd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.ZipBytes;

class OfdRendererTest {

    private static final String NS = "xmlns:ofd=\"http://www.ofdspec.org/2016\"";

    private static final float MM = 72f / 25.4f;

    @TempDir
    Path dir;

    private static String root(String extra) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ofd:OFD " + NS + " Version=\"1.0\" DocType=\"OFD\">"
                + "<ofd:DocBody><ofd:DocInfo><ofd:Title>Receipt</ofd:Title><ofd:Author>Tax office</ofd:Author>"
                + "</ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>" + extra + "</ofd:DocBody></ofd:OFD>";
    }

    private static String document(String common, String pages, String extra) {
        return "<ofd:Document " + NS + "><ofd:CommonData><ofd:MaxUnitID>100</ofd:MaxUnitID><ofd:PageArea>"
                + "<ofd:PhysicalBox>0 0 210 140</ofd:PhysicalBox></ofd:PageArea>"
                + "<ofd:PublicRes>PublicRes.xml</ofd:PublicRes><ofd:DocumentRes>DocumentRes.xml</ofd:DocumentRes>"
                + common + "</ofd:CommonData><ofd:Pages>" + pages + "</ofd:Pages>" + extra + "</ofd:Document>";
    }

    private static String page(String body) {
        return "<ofd:Page " + NS + "><ofd:Content><ofd:Layer ID=\"9\">" + body + "</ofd:Layer></ofd:Content></ofd:Page>";
    }

    private static final String PUBLIC_RES = "<ofd:Res " + NS + " BaseLoc=\"Res\"><ofd:Fonts>"
            + "<ofd:Font ID=\"2\" FontName=\"Courier New\" FamilyName=\"Courier New\"/></ofd:Fonts>"
            + "<ofd:ColorSpaces><ofd:ColorSpace ID=\"3\" Type=\"CMYK\"/></ofd:ColorSpaces></ofd:Res>";

    private static String documentRes(String media) {
        return "<ofd:Res " + NS + " BaseLoc=\"Res\"><ofd:MultiMedias>" + media + "</ofd:MultiMedias></ofd:Res>";
    }

    private ZipBytes ofd(String pageBody) {
        return new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(""))
                .add("Doc_0/Pages/Page_0/Content.xml", page(pageBody));
    }

    private Path convert(byte[] data) throws IOException {
        Path in = Files.write(dir.resolve("in.ofd"), data);
        Path out = dir.resolve("out.pdf");
        OfficeToPdf.convert(in, out);
        return out;
    }

    private static BufferedImage render(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFRenderer(doc).renderImageWithDPI(0, 72);
        }
    }

    private static Color at(BufferedImage image, float xMm, float yMm) {
        return new Color(image.getRGB(Math.round(xMm * MM), Math.round(yMm * MM)));
    }

    private static boolean reddish(Color c) {
        return c.getRed() > 180 && c.getGreen() < 90 && c.getBlue() < 90;
    }

    @Test
    void pagesTakeThePhysicalBoxInMillimetres() throws IOException {
        Path pdf = convert(ofd("").bytes());
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDRectangle box = doc.getPage(0).getMediaBox();
            assertEquals(210 * MM, box.getWidth(), 0.5);
            assertEquals(140 * MM, box.getHeight(), 0.5);
            assertEquals("Receipt", doc.getDocumentInformation().getTitle());
            assertEquals("Tax office", doc.getDocumentInformation().getAuthor());
        }
    }

    @Test
    void textIsPlacedAtEachGlyphPosition() throws IOException {
        String body = "<ofd:TextObject ID=\"10\" Boundary=\"20 30 100 10\" Font=\"2\" Size=\"5\">"
                + "<ofd:TextCode X=\"0\" Y=\"5\" DeltaX=\"g 2 20\">ABC</ofd:TextCode></ofd:TextObject>";
        Path pdf = convert(ofd(body).bytes());
        List<TextPosition> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    glyphs.add(text);
                }
            };
            stripper.getText(doc);
        }
        assertEquals("ABC", glyphs.stream().map(TextPosition::getUnicode).reduce("", String::concat));
        assertEquals(20 * MM, glyphs.get(0).getXDirAdj(), 1);
        assertEquals(40 * MM, glyphs.get(1).getXDirAdj(), 1);
        assertEquals(60 * MM, glyphs.get(2).getXDirAdj(), 1);
        assertEquals(35 * MM, glyphs.get(0).getYDirAdj(), 1);
    }

    @Test
    void pathsAreFilledInTheirColourSpace() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 50 40\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor Value=\"0 255 255 0\" ColorSpace=\"3\"/>"
                + "<ofd:AbbreviatedData>M 0 0 L 50 0 L 50 40 L 0 40 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        assertTrue(reddish(at(image, 30, 30)), "inside: " + at(image, 30, 30));
        assertEquals(Color.WHITE, at(image, 80, 30));
    }

    @Test
    void aPathWithoutAFillColourIsOnlyStroked() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 50 40\" Fill=\"true\" LineWidth=\"1\">"
                + "<ofd:AbbreviatedData>M 0 0 L 50 0 L 50 40 L 0 40 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        assertEquals(Color.WHITE, at(image, 30, 30));
        assertTrue(at(image, 10, 30).getRed() < 100);
    }

    @Test
    void arcsAndCubicCurvesAreDrawn() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"0 0 200 100\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor Value=\"255 0 0\"/><ofd:AbbreviatedData>M 20 50 A 30 30 0 1 1 80 50"
                + " A 30 30 0 1 1 20 50 C M 120 20 B 180 20 180 80 120 80 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        assertTrue(reddish(at(image, 50, 50)));
        assertTrue(reddish(at(image, 50, 25)));
        assertEquals(Color.WHITE, at(image, 22, 25));
        assertTrue(reddish(at(image, 140, 50)));
    }

    @Test
    void templatesAreDrawnUnderThePageAndDrawParametersAreInherited() throws IOException {
        String template = "<ofd:Page " + NS + "><ofd:Content><ofd:Layer ID=\"20\">"
                + "<ofd:PathObject ID=\"21\" Boundary=\"0 0 210 140\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor Value=\"0 0 255\"/><ofd:AbbreviatedData>M 0 0 L 210 0 L 210 140 L 0 140 C"
                + "</ofd:AbbreviatedData></ofd:PathObject></ofd:Layer></ofd:Content></ofd:Page>";
        String res = "<ofd:Res " + NS + " BaseLoc=\"Res\"><ofd:DrawParams><ofd:DrawParam ID=\"30\">"
                + "<ofd:FillColor Value=\"255 0 0\"/></ofd:DrawParam><ofd:DrawParam ID=\"31\" Relative=\"30\"/>"
                + "</ofd:DrawParams></ofd:Res>";
        String body = "<ofd:PathObject ID=\"12\" Boundary=\"50 50 20 20\" Fill=\"true\" Stroke=\"false\""
                + " DrawParam=\"31\"><ofd:AbbreviatedData>M 0 0 L 20 0 L 20 20 L 0 20 C</ofd:AbbreviatedData>"
                + "</ofd:PathObject>";
        String pageXml = "<ofd:Page " + NS + "><ofd:Template TemplateID=\"5\"/><ofd:Content><ofd:Layer ID=\"9\">"
                + body + "</ofd:Layer></ofd:Content></ofd:Page>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("<ofd:TemplatePage ID=\"5\" BaseLoc=\"Tpls/Tpl_0/Content.xml\"/>",
                        "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", res)
                .add("Doc_0/Tpls/Tpl_0/Content.xml", template).add("Doc_0/Pages/Page_0/Content.xml", pageXml).bytes();
        BufferedImage image = render(convert(data));
        assertTrue(reddish(at(image, 60, 60)));
        Color back = at(image, 10, 10);
        assertTrue(back.getBlue() > 200 && back.getRed() < 60, "template: " + back);
    }

    @Test
    void picturesFillTheUnitSquareOfTheirTransform() throws IOException {
        String media = "<ofd:MultiMedia ID=\"40\" Type=\"Image\" Format=\"PNG\"><ofd:MediaFile>red.png</ofd:MediaFile>"
                + "</ofd:MultiMedia>";
        String body = "<ofd:ImageObject ID=\"13\" Boundary=\"100 20 40 30\" CTM=\"40 0 0 30 0 0\" ResourceID=\"40\"/>";
        byte[] data = ofd(body).add("Doc_0/Res/red.png", png(Color.RED)).bytes();
        data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(media))
                .add("Doc_0/Pages/Page_0/Content.xml", page(body)).add("Doc_0/Res/red.png", png(Color.RED)).bytes();
        BufferedImage image = render(convert(data));
        assertTrue(reddish(at(image, 120, 35)));
        assertEquals(Color.WHITE, at(image, 95, 35));
    }

    @Test
    void clipsInTheObjectSpaceFollowItsTransform() throws IOException {
        String media = "<ofd:MultiMedia ID=\"40\" Type=\"Image\"><ofd:MediaFile>red.png</ofd:MediaFile></ofd:MultiMedia>";
        String body = "<ofd:ImageObject ID=\"13\" Boundary=\"20 20 100 80\" CTM=\"100 0 0 80 0 0\" ResourceID=\"40\">"
                + "<ofd:Clips TransFlag=\"true\"><ofd:Clip><ofd:Area><ofd:Path Boundary=\"0 0 1 1\">"
                + "<ofd:AbbreviatedData>M 0 0 L 0.5 0 L 0.5 1 L 0 1 C</ofd:AbbreviatedData></ofd:Path></ofd:Area>"
                + "</ofd:Clip></ofd:Clips></ofd:ImageObject>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(media))
                .add("Doc_0/Pages/Page_0/Content.xml", page(body)).add("Doc_0/Res/red.png", png(Color.RED)).bytes();
        BufferedImage image = render(convert(data));
        assertTrue(reddish(at(image, 50, 60)));
        assertEquals(Color.WHITE, at(image, 100, 60));
    }

    @Test
    void sealAppearancesAreDrawnFromTheSignedValue() throws IOException {
        byte[] picture = png(Color.RED);
        byte[] der = der(0x30, concat(der(0x16, "png".getBytes()), der(0x04, picture)));
        String signatures = "<ofd:Signatures " + NS + "><ofd:Signature ID=\"1\" Type=\"Seal\""
                + " BaseLoc=\"/Doc_0/Signs/Sign_0/Signature.xml\"/></ofd:Signatures>";
        String signature = "<ofd:Signature " + NS + "><ofd:SignedInfo><ofd:StampAnnot ID=\"1\" PageRef=\"1\""
                + " Boundary=\"150 90 40 40\"/></ofd:SignedInfo><ofd:SignedValue>SignedValue.dat</ofd:SignedValue>"
                + "</ofd:Signature>";
        byte[] data = new ZipBytes().add("OFD.xml", root("<ofd:Signatures>Doc_0/Signs/Signatures.xml</ofd:Signatures>"))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(""))
                .add("Doc_0/Pages/Page_0/Content.xml", page("")).add("Doc_0/Signs/Signatures.xml", signatures)
                .add("Doc_0/Signs/Sign_0/Signature.xml", signature).add("Doc_0/Signs/Sign_0/SignedValue.dat", der)
                .bytes();
        BufferedImage image = render(convert(data));
        assertTrue(reddish(at(image, 170, 110)));
        assertEquals(Color.WHITE, at(image, 140, 110));
    }

    @Test
    void annotationsAndOutlinesAreKept() throws IOException {
        String annots = "<ofd:Annotations " + NS + "><ofd:Page PageID=\"1\"><ofd:FileLoc>Page_0/Annot.xml</ofd:FileLoc>"
                + "</ofd:Page></ofd:Annotations>";
        String annot = "<ofd:PageAnnot " + NS + "><ofd:Annot ID=\"1\" Type=\"Stamp\"><ofd:Appearance"
                + " Boundary=\"10 10 30 30\"><ofd:PathObject ID=\"2\" Boundary=\"0 0 30 30\" Fill=\"true\""
                + " Stroke=\"false\"><ofd:FillColor Value=\"255 0 0\"/><ofd:AbbreviatedData>M 0 0 L 30 0 L 30 30"
                + " L 0 30 C</ofd:AbbreviatedData></ofd:PathObject></ofd:Appearance></ofd:Annot>"
                + "<ofd:Annot ID=\"3\" Type=\"Stamp\" Visible=\"false\"><ofd:Appearance Boundary=\"100 10 30 30\">"
                + "<ofd:PathObject ID=\"4\" Boundary=\"0 0 30 30\" Fill=\"true\"><ofd:AbbreviatedData>M 0 0 L 30 0"
                + " L 30 30 L 0 30 C</ofd:AbbreviatedData></ofd:PathObject></ofd:Appearance></ofd:Annot>"
                + "</ofd:PageAnnot>";
        String outlines = "<ofd:Outlines><ofd:OutlineElem Title=\"Second page\"><ofd:Actions><ofd:Action"
                + " Event=\"CLICK\"><ofd:Goto><ofd:Dest Type=\"XYZ\" PageID=\"2\" Top=\"10\"/></ofd:Goto></ofd:Action>"
                + "</ofd:Actions></ofd:OutlineElem></ofd:Outlines><ofd:Annotations>Annots/Annotations.xml"
                + "</ofd:Annotations>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>"
                        + "<ofd:Page ID=\"2\" BaseLoc=\"Pages/Page_1/Content.xml\"/>", outlines))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(""))
                .add("Doc_0/Pages/Page_0/Content.xml", page("")).add("Doc_0/Pages/Page_1/Content.xml", page(""))
                .add("Doc_0/Annots/Annotations.xml", annots).add("Doc_0/Annots/Page_0/Annot.xml", annot).bytes();
        Path pdf = convert(data);
        BufferedImage image = render(pdf);
        assertTrue(reddish(at(image, 25, 25)));
        assertEquals(Color.WHITE, at(image, 115, 25));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            assertEquals("Second page", doc.getDocumentCatalog().getDocumentOutline().getFirstChild().getTitle());
        }
    }

    @Test
    void partNamesAreMatchedWithoutCaseAndBackslashes() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 50 40\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor Value=\"255 0 0\"/><ofd:AbbreviatedData>M 0 0 L 50 0 L 50 40 L 0 40 C"
                + "</ofd:AbbreviatedData></ofd:PathObject>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"pages\\page_0\\content.xml\"/>",
                        ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(""))
                .add("Doc_0/Pages/Page_0/Content.xml", page(body)).bytes();
        assertTrue(reddish(at(render(convert(data)), 30, 30)));
    }

    @Test
    void aDocumentWithoutPagesIsRefused() {
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "", "")).add("Doc_0/PublicRes.xml", PUBLIC_RES)
                .add("Doc_0/DocumentRes.xml", documentRes("")).bytes();
        IOException e = assertThrows(IOException.class, () -> convert(data));
        assertTrue(e.getMessage().contains("no pages"), e.getMessage());
    }

    @Test
    void aZipWithoutOfdXmlIsNotTakenForOfd() throws IOException {
        Path in = Files.write(dir.resolve("other.ofd"), new ZipBytes().add("a.txt", "x").bytes());
        assertFalse(OfdRenderer.is(in));
    }

    @Test
    void sealPicturesAreFoundInsideNestedDer() {
        byte[] picture = png(Color.RED);
        byte[] der = der(0x30, concat(der(0x02, new byte[] {1}), der(0x30, concat(der(0x16, "png".getBytes()),
                der(0x04, picture)))));
        assertEquals(picture.length, OfdSeals.picture(der).length);
        assertEquals(null, OfdSeals.picture(new byte[] {0x30, (byte) 0x84, 0x7F, 0x7F, 0x7F, 0x7F, 0, 0}));
    }

    @Test
    void attachmentsAreEmbeddedAsTheyAre() throws IOException {
        byte[] payload = {0x50, 0x4B, 3, 4, 9, 9, 9};
        String list = "<ofd:Attachments " + NS + "><ofd:Attachment ID=\"1\" Name=\"../发票明细\" Usage=\"清单\">"
                + "<ofd:FileLoc>Attachs/a.zip</ofd:FileLoc></ofd:Attachment>"
                + "<ofd:Attachment ID=\"2\" Name=\"gone\"><ofd:FileLoc>Attachs/missing.bin</ofd:FileLoc>"
                + "</ofd:Attachment></ofd:Attachments>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>",
                        "<ofd:Attachments>Attachments.xml</ofd:Attachments>"))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(""))
                .add("Doc_0/Pages/Page_0/Content.xml", page("")).add("Doc_0/Attachments.xml", list)
                .add("Doc_0/Attachs/a.zip", payload).bytes();
        Path pdf = convert(data);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var files = doc.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertEquals(1, files.size());
            String name = files.keySet().iterator().next();
            assertEquals(".._发票明细.zip", name);
            assertEquals("清单", files.get(name).getFileDescription());
            assertTrue(java.util.Arrays.equals(payload, files.get(name).getEmbeddedFile().toByteArray()));
        }
    }

    @Test
    void patternCellsAreRepeatedInsideThePath() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 100 20\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor><ofd:Pattern Width=\"10\" Height=\"10\" XStep=\"20\" YStep=\"20\"><ofd:CellContent>"
                + "<ofd:PathObject ID=\"12\" Boundary=\"0 0 10 10\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor Value=\"255 0 0\"/><ofd:AbbreviatedData>M 0 0 L 10 0 L 10 10 L 0 10 C"
                + "</ofd:AbbreviatedData></ofd:PathObject></ofd:CellContent></ofd:Pattern></ofd:FillColor>"
                + "<ofd:AbbreviatedData>M 0 0 L 100 0 L 100 20 L 0 20 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        assertTrue(reddish(at(image, 15, 15)));
        assertTrue(reddish(at(image, 55, 15)));
        assertTrue(reddish(at(image, 95, 15)));
        assertEquals(Color.WHITE, at(image, 25, 15));
        assertEquals(Color.WHITE, at(image, 115, 15));
    }

    @Test
    void gouraudMeshesBlendTheirCornerColours() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 100 100\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor><ofd:LaGouraudShd VerticesPerRow=\"2\">"
                + point(0, 0, "255 0 0") + point(100, 0, "255 0 0") + point(0, 100, "0 0 255") + point(100, 100, "0 0 255")
                + "</ofd:LaGouraudShd></ofd:FillColor>"
                + "<ofd:AbbreviatedData>M 0 0 L 100 0 L 100 100 L 0 100 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        Color top = at(image, 60, 14);
        Color bottom = at(image, 60, 106);
        Color middle = at(image, 60, 60);
        assertTrue(top.getRed() > 200 && top.getBlue() < 60, "top: " + top);
        assertTrue(bottom.getBlue() > 200 && bottom.getRed() < 60, "bottom: " + bottom);
        assertTrue(middle.getRed() > 80 && middle.getBlue() > 80, "middle: " + middle);
        assertEquals(Color.WHITE, at(image, 150, 60));
    }

    @Test
    void freeFormGouraudTrianglesFollowTheirEdgeFlags() throws IOException {
        String body = "<ofd:PathObject ID=\"11\" Boundary=\"10 10 100 100\" Fill=\"true\" Stroke=\"false\">"
                + "<ofd:FillColor><ofd:GouraudShd>" + flagged(0, 0, 0) + flagged(100, 0, 0) + flagged(0, 100, 0)
                + flagged(100, 100, 1) + "</ofd:GouraudShd></ofd:FillColor>"
                + "<ofd:AbbreviatedData>M 0 0 L 100 0 L 100 100 L 0 100 C</ofd:AbbreviatedData></ofd:PathObject>";
        BufferedImage image = render(convert(ofd(body).bytes()));
        assertTrue(reddish(at(image, 30, 30)));
        assertTrue(reddish(at(image, 90, 90)));
    }

    @Test
    void imageMasksHideTheBlackPartOfAPicture() throws IOException {
        String media = "<ofd:MultiMedia ID=\"40\" Type=\"Image\"><ofd:MediaFile>red.png</ofd:MediaFile></ofd:MultiMedia>"
                + "<ofd:MultiMedia ID=\"41\" Type=\"Image\"><ofd:MediaFile>mask.png</ofd:MediaFile></ofd:MultiMedia>";
        String body = "<ofd:ImageObject ID=\"13\" Boundary=\"20 20 100 80\" CTM=\"100 0 0 80 0 0\" ResourceID=\"40\""
                + " ImageMask=\"41\"/>";
        byte[] data = new ZipBytes().add("OFD.xml", root(""))
                .add("Doc_0/Document.xml", document("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/Page_0/Content.xml\"/>", ""))
                .add("Doc_0/PublicRes.xml", PUBLIC_RES).add("Doc_0/DocumentRes.xml", documentRes(media))
                .add("Doc_0/Pages/Page_0/Content.xml", page(body)).add("Doc_0/Res/red.png", png(Color.RED))
                .add("Doc_0/Res/mask.png", halves()).bytes();
        BufferedImage image = render(convert(data));
        assertTrue(reddish(at(image, 40, 60)), "kept: " + at(image, 40, 60));
        assertEquals(Color.WHITE, at(image, 100, 60));
    }

    @Test
    void verticalTextAdvancesDownByTheEm() throws IOException {
        String body = "<ofd:TextObject ID=\"10\" Boundary=\"20 30 100 100\" Font=\"2\" Size=\"8\" ReadDirection=\"90\">"
                + "<ofd:TextCode X=\"0\" Y=\"8\">中文字</ofd:TextCode></ofd:TextObject>";
        Path pdf = convert(ofd(body).bytes());
        List<TextPosition> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    glyphs.add(text);
                }
            };
            stripper.getText(doc);
        }
        assertEquals(3, glyphs.size());
        assertEquals(glyphs.get(0).getXDirAdj(), glyphs.get(2).getXDirAdj(), 0.5);
        assertEquals(8 * MM, glyphs.get(1).getYDirAdj() - glyphs.get(0).getYDirAdj(), 0.5);
        assertEquals(16 * MM, glyphs.get(2).getYDirAdj() - glyphs.get(0).getYDirAdj(), 0.5);
    }

    private static String point(double x, double y, String rgb) {
        return "<ofd:Point X=\"" + x + "\" Y=\"" + y + "\"><ofd:Color Value=\"" + rgb + "\"/></ofd:Point>";
    }

    private static String flagged(double x, double y, int flag) {
        return "<ofd:Point X=\"" + x + "\" Y=\"" + y + "\" EdgeFlag=\"" + flag + "\"><ofd:Color Value=\"255 0 0\"/>"
                + "</ofd:Point>";
    }

    private static byte[] halves() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, x < 4 ? Color.WHITE.getRGB() : Color.BLACK.getRGB());
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png(Color color) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = content.length;
        if (n < 128) {
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
