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
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
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
                images += images(page.getResources(), 0);
            }
            return new Converted(new PDFTextStripper().getText(doc).replace('\u00A0', ' '), doc.getNumberOfPages(),
                    images);
        }
    }

    private static int images(PDResources resources, int depth) throws IOException {
        int n = 0;
        if (resources == null || depth > 8) {
            return 0;
        }
        for (var x : resources.getXObjectNames()) {
            var o = resources.getXObject(x);
            if (o instanceof PDImageXObject) {
                n++;
            } else if (o instanceof PDFormXObject f) {
                n += images(f.getResources(), depth + 1);
            }
        }
        return n;
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
    void aBodyBackgroundPictureNamedByContentIdIsDrawn() throws IOException {
        String mail = "From: a@example.org\nTo: b@example.org\nSubject: Card\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/related; boundary=\"rel\"\n\n"
                + "--rel\nContent-Type: text/html; charset=us-ascii\n\n"
                + "<html><body background=\"cid:../BG/PAPER.GIF\"><table width=\"300\"><tr>"
                + "<td height=\"40\">Greetings</td></tr></table></body></html>\n"
                + "--rel\nContent-Type: image/png\nContent-ID: <../BG/PAPER.GIF>\nContent-Transfer-Encoding: base64\n\n"
                + png(Color.YELLOW) + "\n--rel--\n";
        Converted c = convert("card.eml", ascii(mail));
        assertTrue(c.text().contains("Greetings"), c.text());
        assertEquals(1, c.images(), c.text());
    }

    @Test
    void anHtmlPartWithoutACharsetUsesItsMetaCharset() throws IOException {
        Charset big5 = Charset.forName("Big5");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("From: a@example.org\nTo: b@example.org\nSubject: Form\nMIME-Version: 1.0\n"
                + "Content-Type: text/html;;;\nContent-Transfer-Encoding: 8bit\n\n"
                + "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html;charset=big5\"><html><body><p>"));
        out.writeBytes("\u7522\u54c1\u540d\u7a31".getBytes(big5));
        out.writeBytes(ascii("</p></body></html>\n"));
        Converted c = convert("form.eml", out.toByteArray());
        assertTrue(c.text().contains("\u7522\u54c1\u540d\u7a31"), c.text());
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

    @Test
    void anOutlookMessageDrawsPicturesItsBodyNamesByFileName() throws IOException {
        byte[] picture = Base64.getMimeDecoder().decode(png(Color.ORANGE));
        byte[] msg = msg("Welcome", "Team", "team@example.org", "New User",
                "<HTML><BODY><TABLE><TR><TD><IMG SRC=\"1.jpg\" ALT=\"\"></TD><TD>Welcome aboard</TD></TR></TABLE>"
                        + "<P><IMG SRC=\"2.jpg\"></P></BODY></HTML>",
                null, Map.of(), Map.of("1.jpg", picture, "2.jpg", picture, "notes.txt", new byte[10]));
        Converted c = convert("welcome.msg", msg);
        assertTrue(c.text().contains("Welcome aboard"), c.text());
        assertEquals(2, c.images(), c.text());
        assertTrue(c.text().contains("notes.txt"), c.text());
        assertFalse(c.text().contains("1.jpg"), c.text());
    }

    @Test
    void anOutlookMessageSentOnBehalfOfSomeoneShowsBoth() throws IOException {
        byte[] msg = msg("Agenda", "Pat Assistant", "pat@example.org", "Board", "<p>Agenda attached</p>", null,
                Map.of("0042", "Lee Director", "0065", "lee@example.org", "5D02", "lee@example.org"), Map.of());
        Converted c = convert("agenda.msg", msg);
        assertTrue(c.text().contains("From: Lee Director <lee@example.org>"), c.text());
        assertTrue(c.text().contains("Pat Assistant <pat@example.org>"), c.text());
    }

    @Test
    void outlookAppointmentsTasksAndContactsShowTheirOwnFields() throws IOException {
        String appointment = "00062002-0000-0000-C000-000000000046";
        byte[] meeting = msg("Design review", "Ana Ruiz", "ana@example.org", "Bob Smith", "<p>Bring the drafts</p>",
                null, Map.of("001A", "IPM.Appointment", "8000", "Room 4", "8004", "Every Tuesday"), Map.of(),
                new String[][] {{appointment, "0x8208"}, {appointment, "0x820D"}, {appointment, "0x820E"},
                        {appointment, "0x8205"}, {appointment, "0x8232"}},
                Map.of(0x8001, java.time.Instant.parse("2026-11-03T14:00:00Z"), 0x8002,
                        java.time.Instant.parse("2026-11-03T15:30:00Z"), 0x8003, 2));
        Converted c = convert("meeting.msg", meeting);
        assertTrue(c.text().contains("Appointment"), c.text());
        assertTrue(c.text().contains("Organizer: Ana Ruiz <ana@example.org>"), c.text());
        assertTrue(c.text().contains("Location: Room 4"), c.text());
        assertTrue(c.text().contains("Start: Tue, 3 Nov 2026 14:00 UTC"), c.text());
        assertTrue(c.text().contains("End: Tue, 3 Nov 2026 15:30 UTC"), c.text());
        assertTrue(c.text().contains("Recurrence: Every Tuesday"), c.text());
        assertTrue(c.text().contains("Show as: Busy"), c.text());
        assertTrue(c.text().contains("Required attendees: Bob Smith"), c.text());

        byte[] reply = msg("Accepted: Design review", "Bob Smith", "bob@example.org", "Ana Ruiz", "<p>See you</p>",
                null, Map.of("001A", "IPM.Schedule.Meeting.Resp.Pos", "8000", "Room 4"), Map.of(),
                new String[][] {{appointment, "0x8208"}}, Map.of());
        c = convert("reply.msg", reply);
        assertTrue(c.text().contains("Meeting accepted"), c.text());
        assertTrue(c.text().contains("From: Bob Smith <bob@example.org>"), c.text());
        assertTrue(c.text().contains("To: Ana Ruiz"), c.text());
        assertFalse(c.text().contains("Organizer"), c.text());

        String task = "00062003-0000-0000-C000-000000000046";
        byte[] todo = msg("File the report", "Ana Ruiz", "ana@example.org", "Bob Smith", "<p>Before Friday</p>", null,
                Map.of("001A", "IPM.Task", "8003", "Ana Ruiz"), Map.of(),
                new String[][] {{task, "0x8101"}, {task, "0x8102"}, {task, "0x8105"}, {task, "0x811F"}},
                Map.of(0x8000, 1, 0x8001, 0.25, 0x8002, java.time.Instant.parse("2026-11-06T00:00:00Z")));
        c = convert("todo.msg", todo);
        assertTrue(c.text().contains("Status: In progress"), c.text());
        assertTrue(c.text().contains("% Complete: 25%"), c.text());
        assertTrue(c.text().contains("Due date: Fri, 6 Nov 2026"), c.text());
        assertTrue(c.text().contains("Owner: Ana Ruiz"), c.text());

        String address = "00062004-0000-0000-C000-000000000046";
        byte[] card = msg("Quick Fox", "Ana Ruiz", "ana@example.org", "", null, null,
                Map.of("001A", "IPM.Contact", "3001", "Dr Quick Fox", "3A16", "Fence Co", "3A08", "(123) 456-7890",
                        "3A1C", "(456) 789-0123", "8000", "quick@example.org", "8001", "12 Lazy Dog Lane\r\nSpringfield"),
                Map.of(), new String[][] {{address, "0x8083"}, {address, "0x801B"}}, Map.of());
        c = convert("card.msg", card);
        assertTrue(c.text().contains("Contact"), c.text());
        assertTrue(c.text().contains("Company: Fence Co"), c.text());
        assertTrue(c.text().contains("Business phone: (123) 456-7890"), c.text());
        assertTrue(c.text().contains("Mobile: (456) 789-0123"), c.text());
        assertTrue(c.text().contains("E-mail: quick@example.org"), c.text());
        assertTrue(c.text().contains("Business address: 12 Lazy Dog Lane, Springfield"), c.text());
    }

    static byte[] msg(String subject, String fromName, String fromAddress, String to, String html, String rtf)
            throws IOException {
        return msg(subject, fromName, fromAddress, to, html, rtf, Map.of(), Map.of());
    }

    static byte[] msg(String subject, String fromName, String fromAddress, String to, String html, String rtf,
            Map<String, String> strings, Map<String, byte[]> files) throws IOException {
        return msg(subject, fromName, fromAddress, to, html, rtf, strings, files, new String[0][], Map.of());
    }

    static byte[] msg(String subject, String fromName, String fromAddress, String to, String html, String rtf,
            Map<String, String> strings, Map<String, byte[]> files, String[][] named, Map<Integer, Object> fixed)
            throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryEntry root = fs.getRoot();
            string(root, "001A", strings.getOrDefault("001A", "IPM.Note"));
            for (Map.Entry<String, String> e : new TreeMap<>(strings).entrySet()) {
                if (!e.getKey().equals("001A")) {
                    string(root, e.getKey(), e.getValue());
                }
            }
            int n = 0;
            for (Map.Entry<String, byte[]> f : new TreeMap<>(files).entrySet()) {
                DirectoryEntry a = root.createDirectory(String.format("__attach_version1.0_#%08X", n++));
                string(a, "3707", f.getKey());
                a.createDocument("__substg1.0_37010102", new ByteArrayInputStream(f.getValue()));
                a.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[8]));
            }
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
            if (named.length > 0) {
                Map<String, Integer> sets = new java.util.LinkedHashMap<>();
                ByteBuffer entries = ByteBuffer.allocate(8 * named.length).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < named.length; i++) {
                    int set = sets.computeIfAbsent(named[i][0], k -> sets.size());
                    entries.putInt(Integer.decode(named[i][1])).putShort((short) ((set + 3) << 1)).putShort((short) i);
                }
                ByteBuffer guids = ByteBuffer.allocate(16 * sets.size());
                for (String g : sets.keySet()) {
                    java.util.UUID u = java.util.UUID.fromString(g);
                    ByteBuffer be = ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                            .putLong(u.getLeastSignificantBits());
                    byte[] b = be.array();
                    guids.put(new byte[] {b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6]}).put(b, 8, 8);
                }
                DirectoryEntry ids = root.createDirectory("__nameid_version1.0");
                ids.createDocument("__substg1.0_00020102", new ByteArrayInputStream(guids.array()));
                ids.createDocument("__substg1.0_00030102", new ByteArrayInputStream(entries.array()));
                ids.createDocument("__substg1.0_00040102", new ByteArrayInputStream(new byte[0]));
            }
            ByteBuffer props = ByteBuffer.allocate(32 + 16 * fixed.size()).order(ByteOrder.LITTLE_ENDIAN);
            props.position(32);
            for (Map.Entry<Integer, Object> e : new TreeMap<>(fixed).entrySet()) {
                Object v = e.getValue();
                int type = v instanceof Double ? 0x0005 : v instanceof java.time.Instant ? 0x0040
                        : v instanceof Boolean ? 0x000B : 0x0003;
                props.putShort((short) type).putShort((short) (int) e.getKey()).putInt(6);
                if (v instanceof Double d) {
                    props.putDouble(d);
                } else if (v instanceof java.time.Instant t) {
                    props.putLong((t.getEpochSecond() + 11_644_473_600L) * 10_000_000L);
                } else if (v instanceof Boolean b) {
                    props.putLong(b ? 1 : 0);
                } else {
                    props.putLong(((Number) v).longValue());
                }
            }
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(props.array()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void string(DirectoryEntry root, String id, String value) throws IOException {
        root.createDocument("__substg1.0_" + id + "001F",
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }

    @Test
    void aMailStreamedAsHtmlIsStillDrawnAsAMail() throws IOException {
        byte[] eml = ascii("From: Ana <ana@example.org>\nTo: bob@example.org\nSubject: Streamed\nMIME-Version: 1.0\n"
                + "Content-Type: text/plain\n\nHello there\n");
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.convert(new ByteArrayInputStream(eml), OfficeToPdf.Format.HTML, pdf, OfficeToPdf.Options.defaults());
        try (PDDocument doc = Loader.loadPDF(pdf.toByteArray())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Hello there"), text);
            assertFalse(text.contains("MIME-Version"), text);
        }
    }
}
