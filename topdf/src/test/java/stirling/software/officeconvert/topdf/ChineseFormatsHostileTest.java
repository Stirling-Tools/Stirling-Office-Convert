package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.testing.Allocation;
import stirling.software.officeconvert.topdf.testing.NoNetwork;
import stirling.software.officeconvert.topdf.testing.ZipBytes;

class ChineseFormatsHostileTest {

    private static final String NS = "xmlns:ofd=\"http://www.ofdspec.org/2016\"";

    private static final String CANARY = "CANARY-7f3a9c";

    private static final Set<String> ACTIVE = Set.of("JavaScript", "JS", "Launch", "URI", "SubmitForm", "ImportData",
            "RichMedia", "Sound", "Movie", "Rendition", "OpenAction", "AA", "EmbeddedFile", "GoToR", "GoToE");

    @TempDir
    Path dir;

    private Path canary() throws IOException {
        return Files.writeString(dir.resolve("secret.txt"), CANARY);
    }

    private static String ofdRoot(String extra) {
        return "<ofd:OFD " + NS + " Version=\"1.0\"><ofd:DocBody><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>" + extra
                + "</ofd:DocBody></ofd:OFD>";
    }

    private static String ofdDocument(String common, String pages, String extra) {
        return "<ofd:Document " + NS + "><ofd:CommonData><ofd:PageArea><ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox>"
                + "</ofd:PageArea>" + common + "</ofd:CommonData><ofd:Pages>" + pages + "</ofd:Pages>" + extra
                + "</ofd:Document>";
    }

    private static String ofdPage(String body) {
        return "<ofd:Page " + NS + "><ofd:Content><ofd:Layer ID=\"9\">" + body + "</ofd:Layer></ofd:Content></ofd:Page>";
    }

    private static ZipBytes ofd(String common, String body) {
        return new ZipBytes().add("OFD.xml", ofdRoot(""))
                .add("Doc_0/Document.xml", ofdDocument(common, "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>", ""))
                .add("Doc_0/Pages/P0.xml", ofdPage(body));
    }

    private Path convert(String name, byte[] data) throws IOException {
        Path in = Files.write(dir.resolve(name), data);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        return out;
    }

    private static String text(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static List<String> activeContent(Path pdf) throws IOException {
        List<String> found = new ArrayList<>();
        String raw = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
        if (raw.contains(CANARY)) {
            found.add("canary text in the file");
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (COSObjectKey key : new ArrayList<>(doc.getDocument().getXrefTable().keySet())) {
                COSObject object = doc.getDocument().getObjectFromPool(key);
                COSBase base = object == null ? null : object.getObject();
                if (base instanceof COSDictionary d) {
                    for (COSName k : d.keySet()) {
                        if (ACTIVE.contains(k.getName())) {
                            found.add("key " + k.getName());
                        }
                    }
                    COSBase s = d.getDictionaryObject(COSName.S);
                    if (s instanceof COSName n && ACTIVE.contains(n.getName())) {
                        found.add("action " + n.getName());
                    }
                }
            }
        }
        return found;
    }

    @Test
    void ofdReferencesOutsideThePackageAreNeverOpened() throws IOException {
        Path secret = canary();
        try (NoNetwork net = NoNetwork.start()) {
            List<String> targets = new ArrayList<>(net.hostileTargets("x"));
            targets.add(secret.toUri().toString());
            targets.add(secret.toString());
            targets.add("../../../../../../.." + secret);
            targets.add("..\\..\\..\\..\\secret.txt");
            StringBuilder media = new StringBuilder();
            StringBuilder fonts = new StringBuilder();
            StringBuilder body = new StringBuilder();
            StringBuilder templates = new StringBuilder();
            StringBuilder pages = new StringBuilder();
            int id = 100;
            for (String t : targets) {
                String loc = t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
                media.append("<ofd:MultiMedia ID=\"").append(++id).append("\" Type=\"Image\"><ofd:MediaFile>")
                        .append(loc).append("</ofd:MediaFile></ofd:MultiMedia>");
                body.append("<ofd:ImageObject ID=\"").append(++id).append("\" Boundary=\"0 0 10 10\" CTM=\"10 0 0 10 0 0\""
                        + " ResourceID=\"").append(id - 1).append("\"/>");
                fonts.append("<ofd:Font ID=\"").append(++id).append("\" FontName=\"X\"><ofd:FontFile>").append(loc)
                        .append("</ofd:FontFile></ofd:Font>");
                body.append("<ofd:TextObject ID=\"").append(++id).append("\" Boundary=\"0 20 100 10\" Font=\"")
                        .append(id - 1).append("\" Size=\"5\"><ofd:TextCode X=\"0\" Y=\"5\">A</ofd:TextCode>"
                                + "</ofd:TextObject>");
                templates.append("<ofd:TemplatePage ID=\"").append(++id).append("\" BaseLoc=\"").append(loc)
                        .append("\"/>");
                pages.append("<ofd:Page ID=\"").append(++id).append("\" BaseLoc=\"").append(loc).append("\"/>");
            }
            String res = "<ofd:Res " + NS + "><ofd:Fonts>" + fonts + "</ofd:Fonts><ofd:MultiMedias>" + media
                    + "</ofd:MultiMedias></ofd:Res>";
            String signatures = "<ofd:Signatures " + NS + "><ofd:Signature ID=\"1\" BaseLoc=\"" + net.url("sig.xml")
                    + "\"/></ofd:Signatures>";
            byte[] data = new ZipBytes()
                    .add("OFD.xml", ofdRoot("<ofd:Signatures>Doc_0/Signs.xml</ofd:Signatures>"))
                    .add("Doc_0/Document.xml", ofdDocument("<ofd:DocumentRes>Res.xml</ofd:DocumentRes>" + templates,
                            "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>" + pages, "<ofd:Annotations>"
                                    + net.url("a.xml") + "</ofd:Annotations><ofd:Attachments>" + net.url("b.xml")
                                    + "</ofd:Attachments>"))
                    .add("Doc_0/Res.xml", res).add("Doc_0/Signs.xml", signatures)
                    .add("Doc_0/Pages/P0.xml", ofdPage(body.toString())).bytes();
            Path pdf = convert("remote.ofd", data);
            net.assertNothingConnected();
            assertFalse(text(pdf).contains(CANARY));
            assertEquals(List.of(), activeContent(pdf));
        }
    }

    @Test
    void ofdXmlWithEntitiesIsNeverExpanded() throws IOException {
        Path secret = canary();
        try (NoNetwork net = NoNetwork.start()) {
            String page = "<?xml version=\"1.0\"?><!DOCTYPE p [<!ENTITY s SYSTEM \"" + secret.toUri()
                    + "\"><!ENTITY r SYSTEM \"" + net.url("dtd") + "\">]><ofd:Page " + NS + "><ofd:Content><ofd:Layer"
                    + " ID=\"9\"><ofd:TextObject ID=\"1\" Boundary=\"0 0 100 10\" Size=\"5\"><ofd:TextCode X=\"0\""
                    + " Y=\"5\">&s;&r;</ofd:TextCode></ofd:TextObject></ofd:Layer></ofd:Content></ofd:Page>";
            byte[] patched = new ZipBytes().add("OFD.xml", ofdRoot(""))
                    .add("Doc_0/Document.xml", ofdDocument("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>", ""))
                    .add("Doc_0/Pages/P0.xml", page).bytes();
            Path pdf = convert("xxe.ofd", patched);
            net.assertNothingConnected();
            assertFalse(text(pdf).contains(CANARY));
            String root = "<?xml version=\"1.0\"?><!DOCTYPE o SYSTEM \"" + net.url("ofd.dtd") + "\">" + ofdRoot("");
            Path in = Files.write(dir.resolve("dtd.ofd"), new ZipBytes().add("OFD.xml", root)
                    .add("Doc_0/Document.xml", ofdDocument("", "", "")).bytes());
            assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("dtd.pdf")));
            net.assertNothingConnected();
        }
    }

    @Test
    void anOfdPartThatInflatesPastItsDeclaredSizeIsRefused() {
        byte[] bomb = new ZipBytes().add("OFD.xml", ofdRoot(""))
                .add("Doc_0/Document.xml", ofdDocument("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>", ""))
                .repeat("Doc_0/Pages/P0.xml", "<ofd:Page " + NS + ">", new byte[1 << 20], 400, "</ofd:Page>").bytes();
        byte[] lying = ZipBytes.declareSize(bomb, "Doc_0/Pages/P0.xml", 1000);
        Allocation.Measured m = Allocation.measure(() -> convert("bomb.ofd", lying));
        assertTrue(m.bytes() < 300L << 20, "allocated " + m.megabytes() + " MB");
        assertTrue(m.failure() == null || m.failure() instanceof IOException, String.valueOf(m.failure()));
        assertFalse(m.failure() instanceof OutOfMemoryError);
    }

    @Test
    void aPictureClaimingBillionsOfPixelsIsLeftOut() throws IOException {
        String media = "<ofd:Res " + NS + " BaseLoc=\"Res\"><ofd:MultiMedias><ofd:MultiMedia ID=\"5\" Type=\"Image\">"
                + "<ofd:MediaFile>big.png</ofd:MediaFile></ofd:MultiMedia><ofd:MultiMedia ID=\"6\" Type=\"Image\""
                + " Format=\"GBIG2\"><ofd:MediaFile>big.jb2</ofd:MediaFile></ofd:MultiMedia></ofd:MultiMedias></ofd:Res>";
        String body = "<ofd:ImageObject ID=\"7\" Boundary=\"0 0 50 50\" CTM=\"50 0 0 50 0 0\" ResourceID=\"5\"/>"
                + "<ofd:ImageObject ID=\"8\" Boundary=\"0 60 50 50\" CTM=\"50 0 0 50 0 0\" ResourceID=\"6\"/>";
        byte[] data = ofd("<ofd:DocumentRes>Res.xml</ofd:DocumentRes>", body).add("Doc_0/Res.xml", media)
                .add("Doc_0/Res/big.png", pngHeader(100_000, 100_000)).add("Doc_0/Res/big.jb2", jbig2Page(100_000, 100_000))
                .bytes();
        Allocation.Measured m = Allocation.measure(() -> convert("pixels.ofd", data));
        assertNull(m.failure());
        assertTrue(m.bytes() < 300L << 20, "allocated " + m.megabytes() + " MB");
    }

    @Test
    void deeplyNestedOfdBlocksNeitherOverflowNorHang() throws IOException {
        for (int depth : new int[] {900, 20_000}) {
            String body = "<ofd:PageBlock ID=\"1\">".repeat(depth) + "<ofd:PathObject ID=\"2\" Boundary=\"0 0 1 1\">"
                    + "<ofd:AbbreviatedData>M 0 0 L 1 1</ofd:AbbreviatedData></ofd:PathObject>"
                    + "</ofd:PageBlock>".repeat(depth);
            byte[] data = ofd("", body).bytes();
            Allocation.Measured m = Allocation.measure(() -> convert("deep" + depth + ".ofd", data));
            assertFalse(m.failure() instanceof StackOverflowError, String.valueOf(m.failure()));
            assertNull(m.failure());
        }
    }

    @Test
    void tinyPatternStepsAndHugeMeshesStayBounded() throws IOException {
        String tiles = "<ofd:PathObject ID=\"2\" Boundary=\"0 0 200 200\" Fill=\"true\"><ofd:FillColor>"
                + "<ofd:Pattern Width=\"0.02\" Height=\"0.02\" XStep=\"0.02\" YStep=\"0.02\"><ofd:CellContent>"
                + "<ofd:PathObject ID=\"3\" Boundary=\"0 0 1 1\" Fill=\"true\"><ofd:FillColor Value=\"0 0 0\"/>"
                + "<ofd:AbbreviatedData>M 0 0 L 1 0 L 1 1 C</ofd:AbbreviatedData></ofd:PathObject></ofd:CellContent>"
                + "</ofd:Pattern></ofd:FillColor><ofd:AbbreviatedData>M 0 0 L 200 0 L 200 200 C</ofd:AbbreviatedData>"
                + "</ofd:PathObject>";
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < 60_000; i++) {
            points.append("<ofd:Point X=\"").append(i % 300).append("\" Y=\"").append(i / 300)
                    .append("\"><ofd:Color Value=\"1 2 3\"/></ofd:Point>");
        }
        String mesh = "<ofd:PathObject ID=\"4\" Boundary=\"0 0 200 200\" Fill=\"true\"><ofd:FillColor>"
                + "<ofd:LaGouraudShd VerticesPerRow=\"300\">" + points + "</ofd:LaGouraudShd></ofd:FillColor>"
                + "<ofd:AbbreviatedData>M 0 0 L 200 0 L 200 200 C</ofd:AbbreviatedData></ofd:PathObject>";
        byte[] data = ofd("", tiles + mesh).bytes();
        long start = System.nanoTime();
        Allocation.Measured m = Allocation.measure(() -> convert("bounded.ofd", data));
        assertNull(m.failure());
        assertTrue(System.nanoTime() - start < 20_000_000_000L, "took too long");
        assertTrue(Files.size(dir.resolve("bounded.ofd.pdf")) < 16L << 20, "output too large");
    }

    @Test
    void theOfdMemoryEstimateCoversPageXmlThatCompressesWell() throws IOException {
        String path = "<ofd:PathObject ID=\"2\" Boundary=\"0 0 1 1\"><ofd:AbbreviatedData>M 0 0 L 1 1"
                + "</ofd:AbbreviatedData></ofd:PathObject>";
        String body = path.repeat(100_000);
        byte[] data = ofd("", body).bytes();
        Path in = Files.write(dir.resolve("dense.ofd"), data);
        long page = ofdPage(body).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(data.length * 20L < page, "the page should compress well");
        assertTrue(OfficeToPdf.memoryEstimate(in) >= page * 14, "estimate " + OfficeToPdf.memoryEstimate(in));
    }

    @Test
    void ofdAttachmentsOutsideThePackageOrPastTheBudgetAreLeftOut() throws IOException {
        Path secret = canary();
        String list = "<ofd:Attachments " + NS + "><ofd:Attachment ID=\"1\" Name=\"secret\"><ofd:FileLoc>file://"
                + secret.toAbsolutePath() + "</ofd:FileLoc></ofd:Attachment><ofd:Attachment ID=\"2\" Name=\"up\">"
                + "<ofd:FileLoc>../../../../" + secret.toAbsolutePath() + "</ofd:FileLoc></ofd:Attachment>"
                + "<ofd:Attachment ID=\"3\" Name=\"zeros.bin\"><ofd:FileLoc>zeros.bin</ofd:FileLoc></ofd:Attachment>"
                + "</ofd:Attachments>";
        byte[] data = new ZipBytes().add("OFD.xml", ofdRoot(""))
                .add("Doc_0/Document.xml", ofdDocument("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>",
                        "<ofd:Attachments>Attachments.xml</ofd:Attachments>"))
                .add("Doc_0/Pages/P0.xml", ofdPage("")).add("Doc_0/Attachments.xml", list)
                .add("Doc_0/zeros.bin", new byte[24 << 20]).bytes();
        try (NoNetwork net = NoNetwork.start()) {
            Path pdf = convert("attach.ofd", data);
            assertTrue(activeContent(pdf).isEmpty(), activeContent(pdf).toString());
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                var names = doc.getDocumentCatalog().getNames();
                assertTrue(names == null || names.getEmbeddedFiles() == null
                        || names.getEmbeddedFiles().getNames() == null
                        || names.getEmbeddedFiles().getNames().isEmpty());
            }
            net.assertNothingConnected();
        }
    }

    @Test
    void ofdActionsLinksAndMediaAreNeverCarriedIntoThePdf() throws IOException {
        String actions = "<ofd:Actions><ofd:Action Event=\"DO\"><ofd:URI URI=\"javascript:app.alert(1)\"/></ofd:Action>"
                + "<ofd:Action Event=\"DO\"><ofd:Sound ResourceID=\"1\"/></ofd:Action><ofd:Action Event=\"DO\"><ofd:Movie"
                + " ResourceID=\"1\"/></ofd:Action><ofd:Action Event=\"CLICK\"><ofd:URI URI=\"http://example.invalid\"/>"
                + "</ofd:Action></ofd:Actions>";
        String annots = "<ofd:Annotations " + NS + "><ofd:Page PageID=\"1\"><ofd:FileLoc>A.xml</ofd:FileLoc></ofd:Page>"
                + "</ofd:Annotations>";
        String annot = "<ofd:PageAnnot " + NS + "><ofd:Annot ID=\"3\" Type=\"Link\"><ofd:Parameters><ofd:Parameter"
                + " Name=\"URI\">javascript:x()</ofd:Parameter></ofd:Parameters>" + actions + "<ofd:Appearance"
                + " Boundary=\"0 0 10 10\"/></ofd:Annot></ofd:PageAnnot>";
        String page = "<ofd:Page " + NS + "><ofd:Content><ofd:Layer ID=\"9\"/></ofd:Content>" + actions + "</ofd:Page>";
        byte[] data = new ZipBytes().add("OFD.xml", ofdRoot(""))
                .add("Doc_0/Document.xml", ofdDocument("", "<ofd:Page ID=\"1\" BaseLoc=\"Pages/P0.xml\"/>",
                        "<ofd:Actions>" + actions.substring("<ofd:Actions>".length()) + "<ofd:Annotations>Annots.xml"
                                + "</ofd:Annotations>"))
                .add("Doc_0/Pages/P0.xml", page).add("Doc_0/Annots.xml", annots).add("Doc_0/A.xml", annot).bytes();
        assertEquals(List.of(), activeContent(convert("actions.ofd", data)));
    }

    private static final String UOF = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><uof:UOF"
            + " xmlns:uof=\"http://schemas.uof.org/cn/2003/uof\" xmlns:字=\"http://schemas.uof.org/cn/2003/uof-wordproc\""
            + " xmlns:表=\"http://schemas.uof.org/cn/2003/uof-spreadsheet\""
            + " xmlns:演=\"http://schemas.uof.org/cn/2003/uof-slideshow\" xmlns:图=\"http://schemas.uof.org/cn/2003/graph\""
            + " uof:version=\"1.0\" uof:mimetype=\"";

    private static String para(String text) {
        return "<字:段落><字:句><字:文本串>" + text + "</字:文本串></字:句></字:段落>";
    }

    @Test
    void uofRemoteObjectsLinksAndEntitiesAreNeverResolved() throws IOException {
        Path secret = canary();
        try (NoNetwork net = NoNetwork.start()) {
            StringBuilder objects = new StringBuilder();
            StringBuilder anchors = new StringBuilder();
            StringBuilder links = new StringBuilder();
            List<String> targets = new ArrayList<>(net.hostileTargets("p.png"));
            targets.add(secret.toUri().toString());
            targets.add(secret.toString());
            int n = 0;
            for (String t : targets) {
                String loc = t.replace("&", "&amp;").replace("\"", "&quot;");
                objects.append("<uof:其他对象 uof:标识符=\"o").append(n).append("\" uof:内嵌=\"false\" uof:公共类型=\"png\">"
                        + "<uof:路径>").append(loc).append("</uof:路径></uof:其他对象><图:图形 图:标识符=\"g").append(n)
                        .append("\" 图:其他对象=\"o").append(n).append("\"/>");
                anchors.append("<字:句><字:锚点><字:图形 字:图形引用=\"g").append(n).append("\"/></字:锚点></字:句>");
                links.append("<uof:超级链接 uof:目标=\"").append(loc).append("\" uof:链源=\"x\"/>");
                n++;
            }
            links.append("<uof:超级链接 uof:目标=\"javascript:alert(1)\" uof:链源=\"x\"/>");
            String xml = UOF + "vnd.uof.text\"><uof:链接集>" + links + "</uof:链接集><uof:对象集>" + objects
                    + "</uof:对象集><uof:文字处理><字:主体><字:段落>" + anchors + "<字:句><字:文本串>Visible text"
                    + "</字:文本串></字:句></字:段落></字:主体></uof:文字处理></uof:UOF>";
            Path pdf = convert("remote.uot", xml.getBytes(StandardCharsets.UTF_8));
            net.assertNothingConnected();
            assertTrue(text(pdf).contains("Visible text"));
            assertEquals(List.of(), activeContent(pdf));
            String xxe = "<?xml version=\"1.0\"?><!DOCTYPE u [<!ENTITY s SYSTEM \"" + secret.toUri() + "\"><!ENTITY r"
                    + " SYSTEM \"" + net.url("e") + "\">]>" + UOF.substring(UOF.indexOf("<uof:UOF")) + "vnd.uof.text\">"
                    + "<uof:文字处理><字:主体>" + para("&s;&r;") + "</字:主体></uof:文字处理></uof:UOF>";
            Path in = Files.writeString(dir.resolve("xxe.uot"), xxe, StandardCharsets.UTF_8);
            IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("xxe.pdf")));
            assertFalse(String.valueOf(e.getMessage()).contains(CANARY));
            net.assertNothingConnected();
        }
    }

    @Test
    void uofFormulasAreNeverEvaluated() throws IOException {
        String xml = UOF + "vnd.uof.spreadsheet\"><uof:电子表格><表:主体><表:工作表 表:名称=\"S\"><表:工作表内容><表:行>"
                + "<表:单元格><表:数据 表:数据类型=\"text\"><表:公式>=WEBSERVICE(\"http://example.invalid\")&amp;(1+1)"
                + "</表:公式><字:句><字:文本串>cached-result</字:文本串></字:句></表:数据></表:单元格></表:行>"
                + "</表:工作表内容></表:工作表></表:主体></uof:电子表格></uof:UOF>";
        try (NoNetwork net = NoNetwork.start()) {
            Path pdf = convert("formula.uos", xml.getBytes(StandardCharsets.UTF_8));
            net.assertNothingConnected();
            String text = text(pdf);
            assertTrue(text.contains("cached-result"), text);
            assertFalse(text.contains("WEBSERVICE"), text);
        }
    }

    @Test
    void deeplyNestedUofTablesNeitherOverflowNorHang() throws IOException {
        for (int depth : new int[] {300, 20_000}) {
            String open = "<字:文字表><字:行><字:单元格>";
            String close = "</字:单元格></字:行></字:文字表>";
            String xml = UOF + "vnd.uof.text\"><uof:文字处理><字:主体>" + open.repeat(depth) + para("Core")
                    + close.repeat(depth) + "</字:主体></uof:文字处理></uof:UOF>";
            Allocation.Measured m = Allocation.measure(() -> convert("deep" + depth + ".uot",
                    xml.getBytes(StandardCharsets.UTF_8)));
            assertFalse(m.failure() instanceof StackOverflowError, String.valueOf(m.failure()));
            assertTrue(m.failure() == null || m.failure() instanceof IOException, String.valueOf(m.failure()));
        }
    }

    @Test
    void aSmallUofDeckThatRepeatsHugeMasterTextIsRefusedWithinBounds() {
        String text = "重复的母版文字".repeat(30_000);
        StringBuilder slides = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            slides.append("<演:幻灯片 演:母版引用=\"M\"/>");
        }
        String xml = UOF + "vnd.uof.presentation\"><uof:对象集><图:图形 图:标识符=\"big\"><图:文本内容>" + para(text)
                + "</图:文本内容></图:图形></uof:对象集><uof:演示文稿><演:主体><演:母版集><演:母版 演:标识符=\"M\""
                + " 演:类型=\"slide\"><uof:锚点 uof:x坐标=\"0\" uof:y坐标=\"0\" uof:宽度=\"10\" uof:高度=\"10\""
                + " uof:图形引用=\"big\"/></演:母版></演:母版集><演:幻灯片集>" + slides + "</演:幻灯片集></演:主体>"
                + "</uof:演示文稿></uof:UOF>";
        Allocation.Measured m = Allocation.measure(() -> convert("amplify.uop", xml.getBytes(StandardCharsets.UTF_8)));
        assertInstanceOf(IOException.class, m.failure());
        assertTrue(m.failure().getMessage().contains("too large"), m.failure().getMessage());
    }

    @Test
    void aUofRunRepeatingALongFontNameIsRefusedWithinBounds() {
        String name = "A".repeat(4_096);
        String xml = UOF + "vnd.uof.text\"><uof:文字处理><字:主体><字:段落><字:句><字:句属性><字:字体 字:西文字体引用=\""
                + name + "\"/></字:句属性>" + "<字:制表符/>".repeat(100_000) + "</字:句></字:段落></字:主体>"
                + "</uof:文字处理></uof:UOF>";
        Allocation.Measured m = Allocation.measure(() -> convert("tabs.uot", xml.getBytes(StandardCharsets.UTF_8)));
        assertInstanceOf(IOException.class, m.failure());
        assertTrue(m.failure().getMessage().contains("too large"), m.failure().getMessage());
    }

    @Test
    void wpsFilesWithMacrosConvertWithoutRunningOrCarryingThem() throws IOException {
        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Macro carrier");
            doc.write(docx);
        }
        byte[] withMacro = ZipBytesCopy.withEntry(docx.toByteArray(), "word/vbaProject.bin",
                "Sub AutoOpen()\nShell \"calc\"\nEnd Sub".getBytes(StandardCharsets.UTF_8));
        Path pdf = convert("macro.wps", withMacro);
        assertTrue(text(pdf).contains("Macro carrier"));
        assertEquals(List.of(), activeContent(pdf));
    }

    @Test
    void contentDecidesTheReaderSoANameCannotPickALaxerOne() throws IOException {
        byte[] uofInZip = new ZipBytes().add("content.uot", UOF + "vnd.uof.text\"/>").bytes();
        Path in = Files.write(dir.resolve("fake.uot"), uofInZip);
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("fake.pdf")));
        assertFalse(e instanceof OfficeZip.Oversized);
        Path wps = Files.write(dir.resolve("ofd-named.wps"), ofd("", "").bytes());
        assertEquals(1, OfficeToPdf.convert(wps, dir.resolve("ofd-named.pdf")).pages());
    }

    private static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer out = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        out.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}).putInt(13).put(ihdr.array())
                .putInt((int) crc.getValue());
        out.putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt(0xAE426082);
        return out.array();
    }

    private static byte[] jbig2Page(int width, int height) {
        ByteBuffer b = ByteBuffer.allocate(11 + 19);
        b.putInt(0).put((byte) 48).put((byte) 0).put((byte) 1).putInt(19);
        b.putInt(width).putInt(height).putInt(0).putInt(0).put((byte) 0).putShort((short) 0);
        return b.array();
    }

    private static final class ZipBytesCopy {

        static byte[] withEntry(byte[] zip, String name, byte[] data) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                    ZipOutputStream z = new ZipOutputStream(out)) {
                for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                    z.putNextEntry(new ZipEntry(e.getName()));
                    in.transferTo(z);
                    z.closeEntry();
                }
                z.putNextEntry(new ZipEntry(name));
                z.write(data);
                z.closeEntry();
            }
            return out.toByteArray();
        }
    }
}
