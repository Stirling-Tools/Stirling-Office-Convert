package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailToPdfTest {

    @TempDir
    Path dir;

    private record Converted(String text, int pages, int images) {}

    private Converted convert(String name, byte[] bytes) throws IOException {
        Path in = Files.write(dir.resolve(name), bytes);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.convert(in, out);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int images = 0;
            for (PDPage page : doc.getPages()) {
                for (var x : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(x) instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            return new Converted(new PDFTextStripper().getText(doc).replace('\u00A0', ' '), doc.getNumberOfPages(),
                    images);
        }
    }

    private static String png(Color color) throws IOException {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 40, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(out.toByteArray());
    }

    private static byte[] ascii(String s) {
        return s.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void anHtmlMailShowsItsHeadersBodyInlinePictureAndAttachments() throws IOException {
        String mail = "From: =?UTF-8?B?Wm/DqyBOZXdz?= <news@example.org>\n"
                + "To: \"Smith, Bob\" <bob@example.org>, ana@example.org\n"
                + "Cc: =?ISO-8859-1?Q?Chlo=E9?= <chloe@example.net>\n"
                + "Subject: =?UTF-8?Q?Quarterly_r=C3=A9sum=C3=A9?= =?UTF-8?Q?_and_plans?=\n"
                + "Date: Thu, 29 Oct 2026 14:02:11 +0100\n"
                + "MIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"outer\"\n\n"
                + "--outer\n"
                + "Content-Type: multipart/alternative; boundary=\"alt\"\n\n"
                + "--alt\nContent-Type: text/plain; charset=us-ascii\n\nPlain text fallback only\n"
                + "--alt\nContent-Type: multipart/related; boundary=\"rel\"; type=\"text/html\"\n\n"
                + "--rel\nContent-Type: text/html; charset=utf-8\nContent-Transfer-Encoding: quoted-printable\n\n"
                + "<html><body><p>Hello =C3=A9quipe, the numbers are in.</p><img src=3D\"cid:logo@x\" width=3D\"40\""
                + " height=3D\"20\"></body></html>\n"
                + "--rel\nContent-Type: image/png\nContent-ID: <logo@x>\nContent-Transfer-Encoding: base64\n\n"
                + png(Color.BLUE) + "\n--rel--\n--alt--\n"
                + "--outer\nContent-Type: application/pdf; name=\"report.pdf\"\n"
                + "Content-Disposition: attachment; filename*=UTF-8''r%C3%A9port%202026.pdf\n"
                + "Content-Transfer-Encoding: base64\n\n" + Base64.getEncoder().encodeToString(new byte[2048]) + "\n"
                + "--outer--\n";
        Converted c = convert("news.eml", ascii(mail));
        assertTrue(c.text().contains("Quarterly résumé and plans"), c.text());
        assertTrue(c.text().contains("Zoë News <news@example.org>"), c.text());
        assertTrue(c.text().contains("Smith, Bob <bob@example.org>"), c.text());
        assertTrue(c.text().contains("Chloé <chloe@example.net>"), c.text());
        assertTrue(c.text().contains("Thu, 29 Oct 2026 14:02:11 +0100"), c.text());
        assertTrue(c.text().contains("Hello équipe, the numbers are in."), c.text());
        assertFalse(c.text().contains("Plain text fallback"), c.text());
        assertTrue(c.text().contains("réport 2026.pdf (application/pdf, 2.0 KB)"), c.text());
        assertEquals(1, c.images());
    }

    @Test
    void legacyCharsetsAndFlowedTextAreDecoded() throws IOException {
        Charset koi8 = Charset.forName("KOI8-R");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("From: a@example.org\nTo: b@example.org\nSubject: =?koi8-r?B?"
                + Base64.getEncoder().encodeToString("Привет".getBytes(koi8)) + "?=\nMIME-Version: 1.0\n"
                + "Content-Type: text/plain; charset=koi8-r; format=flowed\nContent-Transfer-Encoding: 8bit\n\n"));
        out.writeBytes("Добрый день, это \r\nпервая строка.\r\n".getBytes(koi8));
        Converted c = convert("letter.eml", out.toByteArray());
        assertTrue(c.text().contains("Привет"), c.text());
        assertTrue(c.text().contains("Добрый день, это первая строка."), c.text());
    }

    @Test
    void aForwardedMessageInsideAMailIsShownBelowIt() throws IOException {
        String inner = "From: inner@example.org\r\nTo: outer@example.org\r\nSubject: The original\r\n\r\n"
                + "Text of the forwarded message\r\n";
        String mail = "From: outer@example.org\nTo: you@example.org\nSubject: Fwd: The original\n"
                + "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=b\n\n"
                + "--b\nContent-Type: text/plain\n\nSee below.\n"
                + "--b\nContent-Type: message/rfc822\nContent-Transfer-Encoding: base64\n\n"
                + Base64.getMimeEncoder().encodeToString(inner.getBytes(StandardCharsets.US_ASCII)) + "\n--b--\n";
        Converted c = convert("fwd.eml", ascii(mail));
        assertTrue(c.text().contains("See below."), c.text());
        assertTrue(c.text().contains("Attached message"), c.text());
        assertTrue(c.text().contains("The original"), c.text());
        assertTrue(c.text().contains("Text of the forwarded message"), c.text());
    }

    @Test
    void anOutlookMessageShowsItsHtmlBodyAndRecipients() throws IOException {
        byte[] msg = msg("Budget review", "Ana Ruiz", "ana@example.org", "Bob Smith",
                "<html><body><p>The <b>budget</b> is approved.</p></body></html>", null);
        Converted c = convert("budget.msg", msg);
        assertTrue(c.text().contains("Budget review"), c.text());
        assertTrue(c.text().contains("From: Ana Ruiz <ana@example.org>"), c.text());
        assertTrue(c.text().contains("To: Bob Smith"), c.text());
        assertTrue(c.text().contains("The budget is approved."), c.text());
    }

    @Test
    void anOutlookMessageWithHtmlInsideItsRtfShowsTheHtml() throws IOException {
        String rtf = "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 \\deff0{\\fonttbl{\\f0\\fswiss Arial;}}"
                + "{\\*\\htmltag19 <html>}{\\*\\htmltag34 <head>}{\\*\\htmltag41 </head>}{\\*\\htmltag50 <body>}"
                + "{\\*\\htmltag64 <p>}\\htmlrtf {\\htmlrtf0 Caf\\'e9 menu \\u8364?5 today"
                + "{\\*\\htmltag84 &nbsp;}\\htmlrtf\\'a0\\htmlrtf0 only\\htmlrtf\\par}\\htmlrtf0"
                + "{\\*\\htmltag72 </p>}{\\*\\htmltag58 </body>}{\\*\\htmltag27 </html>}}";
        byte[] msg = msg("Lunch", "Chef", "chef@example.org", "Team", null, rtf);
        Converted c = convert("lunch.msg", msg);
        assertTrue(c.text().contains("Café menu €5 today only"), c.text());
        assertFalse(c.text().contains("htmltag"), c.text());
    }

    @Test
    void anOutlookMessageWithOnlyRtfIsDrawnAsRtfUnderItsHeaders() throws IOException {
        String rtf = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}"
                + "{\\*\\generator Riched20;}\\viewkind4\\uc1\\pard\\f0\\fs20 The {\\b quarterly} figures are"
                + " attached.\\par}";
        byte[] msg = msg("Figures", "Finance", "finance@example.org", "Board", null, rtf);
        Converted c = convert("figures.msg", msg);
        int subject = c.text().indexOf("Figures");
        int from = c.text().indexOf("From: Finance <finance@example.org>");
        int body = c.text().indexOf("The quarterly figures are attached.");
        assertTrue(subject >= 0 && from > subject && body > from, c.text());
    }

    @Test
    void aWebArchiveDrawsItsPicturesAndStyleSheetsFromItsParts() throws IOException {
        String archive = "From: <Saved by Blink>\nSnapshot-Content-Location: https://example.org/a/page.html\n"
                + "Subject: Page\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----b\"\n\n"
                + "------b\nContent-Type: text/html\nContent-ID: <frame-1@mhtml.blink>\n"
                + "Content-Transfer-Encoding: quoted-printable\nContent-Location: https://example.org/a/page.html\n\n"
                + "<html><head><link rel=3D\"stylesheet\" href=3D\"https://example.org/load.php?m=3Da%7Cb&amp;x=3D1\">"
                + "<link rel=3D\"stylesheet\" href=3D\"cid:css-1@mhtml.blink\"></head>"
                + "<body><p class=3D\"gone\">Hidden by the sheet</p><p class=3D\"also\">Hidden too</p>"
                + "<p>Archived page text</p><img src=3D\"img/dot.png\"></body></html>\n"
                + "------b\nContent-Type: text/css\nContent-Location: https://example.org/load.php?m=a|b&x=1\n\n"
                + ".gone{display:none}\n"
                + "------b\nContent-Type: text/css\nContent-ID: <css-1@mhtml.blink>\n\n.also{display:none}\n"
                + "------b\nContent-Type: image/png\nContent-Transfer-Encoding: base64\n"
                + "Content-Location: https://example.org/a/img/dot.png\n\n" + png(Color.RED) + "\n------b--\n";
        Converted c = convert("page.mhtml", ascii(archive));
        assertTrue(c.text().contains("Archived page text"), c.text());
        assertFalse(c.text().contains("Hidden by the sheet"), c.text());
        assertFalse(c.text().contains("Hidden too"), c.text());
        assertFalse(c.text().contains("Saved by Blink"), c.text());
        assertEquals(1, c.images());
    }

    @Test
    void aWebArchiveShowsItsFramesAndShadowRootsButNotTheirStyles() throws IOException {
        String archive = "From: <Saved by Blink>\nSnapshot-Content-Location: https://example.org/\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/related; type=\"text/html\"; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/html\nContent-Location: https://example.org/\n\n"
                + "<html><body><p>Outer page</p><demo-box><template shadowmode=\"open\"><style>p{display:none}</style>"
                + "<div>Shadow content</div><slot></slot></template><span>Light content</span></demo-box>"
                + "<iframe src=\"cid:frame-2@mhtml.blink\" width=\"300\"></iframe></body></html>\n"
                + "--b\nContent-Type: text/html\nContent-ID: <frame-2@mhtml.blink>\n"
                + "Content-Location: https://runner.example.org/\n\n"
                + "<html><head><style>p{display:none}</style></head><body><table><tr><td>Framed cell</td></tr>"
                + "</table></body></html>\n--b--\n";
        Converted c = convert("frames.mhtml", ascii(archive));
        assertTrue(c.text().contains("Outer page"), c.text());
        assertTrue(c.text().contains("Shadow content"), c.text());
        assertTrue(c.text().contains("Light content"), c.text());
        assertTrue(c.text().contains("Framed cell"), c.text());
    }

    @Test
    void aWordPageSavedAsSingleFileWebPageConvertsUnderADocName() throws IOException {
        String archive = "MIME-Version: 1.0\nX-Document-Type: Document\n"
                + "Content-Type: multipart/related; boundary=\"next\"\n\n"
                + "--next\nContent-Location: file:///C:/D/saved.htm\nContent-Type: text/html; charset=\"windows-1252\"\n"
                + "Content-Transfer-Encoding: quoted-printable\n\n<html><body><p>Word saved this =E9t=E9</p>"
                + "<img src=3D\"saved_files/image001.png\"></body></html>\n"
                + "--next\nContent-Location: file:///C:/D/saved_files/image001.png\nContent-Type: image/png\n"
                + "Content-Transfer-Encoding: base64\n\n" + png(Color.GREEN) + "\n--next--\n";
        Converted c = convert("saved.doc", ascii(archive));
        assertTrue(c.text().contains("Word saved this été"), c.text());
        assertEquals(1, c.images());
    }

    static byte[] msg(String subject, String fromName, String fromAddress, String to, String html, String rtf)
            throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryEntry root = fs.getRoot();
            string(root, "001A", "IPM.Note");
            string(root, "0037", subject);
            string(root, "0C1A", fromName);
            string(root, "5D01", fromAddress);
            string(root, "0E04", to);
            string(root, "1000", "Plain body that should not be shown");
            if (html != null) {
                root.createDocument("__substg1.0_10130102",
                        new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            if (rtf != null) {
                byte[] raw = rtf.getBytes(StandardCharsets.ISO_8859_1);
                ByteBuffer b = ByteBuffer.allocate(16 + raw.length).order(ByteOrder.LITTLE_ENDIAN);
                b.putInt(raw.length + 12).putInt(raw.length).putInt(0x414C454D).putInt(0).put(raw);
                root.createDocument("__substg1.0_10090102", new ByteArrayInputStream(b.array()));
            }
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void string(DirectoryEntry root, String id, String value) throws IOException {
        root.createDocument("__substg1.0_" + id + "001F",
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }
}
