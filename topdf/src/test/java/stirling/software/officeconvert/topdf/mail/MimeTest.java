package stirling.software.officeconvert.topdf.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MimeTest {

    @Test
    void base64AndQuotedPrintableDecodeWhatTheyEncode() {
        java.util.Random random = new java.util.Random(7);
        for (int size : new int[] {0, 1, 2, 3, 57, 1000, 70_001}) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            byte[] encoded = java.util.Base64.getMimeEncoder().encode(data);
            assertTrue(java.util.Arrays.equals(data, Mime.base64(encoded, 0, encoded.length)), "size " + size);
        }
        byte[] qp = "caf=C3=A9 =\r\nbar=3D\nlast  \n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("caf\u00e9 bar=\r\nlast\r\n",
                new String(Mime.quotedPrintable(qp, 0, qp.length), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void plainTextTabsStopEveryEightColumns() {
        assertEquals("a       b\n        c\nabcdefgh        d", MailPage.expandTabs("a\tb\n\tc\nabcdefgh\td"));
        assertEquals("no tabs", MailPage.expandTabs("no tabs"));
    }

    @Test
    void encodedWordsJoinAcrossCharsetSplitsAndKeepPlainGaps() {
        assertEquals("Grüße aus Köln", MimeWords.decode("=?UTF-8?Q?Gr=C3=BC?= =?UTF-8?B?w59l?= aus =?ISO-8859-1?Q?K=F6ln?=",
                StandardCharsets.UTF_8));
        assertEquals("a =?x b", MimeWords.decode("a =?x b", StandardCharsets.UTF_8));
    }

    @Test
    void manyUnclosedEncodedWordsDecodeInLinearTime() {
        String hostile = "=?a?Q?x".repeat(200_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(hostile, MimeWords.decode(hostile, StandardCharsets.UTF_8));
            Mime.parse(("Content-Type: text/plain; x=\"" + hostile + "\"\r\n\r\nbody").getBytes(StandardCharsets.US_ASCII));
        });
    }

    @Test
    void extendedParametersJoinTheirSegments() {
        assertEquals("résumé final.pdf", MimeWords.params(
                "attachment; filename*0*=UTF-8''r%C3%A9sum%C3%A9; filename*1=\" final.pdf\"").get("filename"));
    }

    @Test
    void aFloodOfEmptyHeadersStopsBeingKept() {
        StringBuilder sb = new StringBuilder("Content-Type: multipart/mixed; boundary=b\r\n\r\n");
        for (int p = 0; p < 50; p++) {
            sb.append("--b\r\n").append("a:\r\n".repeat(20_000)).append("\r\nx\r\n");
        }
        sb.append("--b--\r\n");
        Mime.Part top = Mime.parse(sb.toString().getBytes(StandardCharsets.US_ASCII));
        assertEquals(50, top.children().size());
        int kept = 0;
        for (Mime.Part c : top.children()) {
            assertTrue(c.header("a") != null || c.header("A") == null);
            kept += c.header("a") != null ? 1 : 0;
        }
        assertTrue(kept > 0);
    }

    @Test
    void aContentIdMatchesOnlyItsOwnReference() {
        Set<String> ids = new HashSet<>();
        Set<String> values = new HashSet<>();
        MailPage.references("<img src=\"cid:12\"><img src='a/b/logo.png'>", ids, values);
        assertTrue(ids.contains("12"));
        assertTrue(!ids.contains("1"));
        assertTrue(values.contains("logo.png"));
    }

    @Test
    void foldedLinesOfOnlySpacesStayWithinTheHeaderLimit() {
        String hostile = "Subject: x\r\n" + " \r\n".repeat(1_500_000) + "\r\nbody";
        Mime.Part top = Mime.parse(hostile.getBytes(StandardCharsets.US_ASCII));
        assertTrue(top.header("Subject").length() <= Mime.MAX_HEADER_BYTES, () -> "" + top.header("Subject").length());
    }

    @Test
    void aMetaCharsetMayHaveSpacesAroundItsEqualsSign() {
        assertEquals("windows-1251", MailCharsets.metaCharset(
                "<!-- charsets --><meta charset = \"windows-1251\">".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void anAttachmentTheBodyOnlyLinksToIsKept() {
        Mail mail = new Mail();
        mail.body.add(new Mail.Html("<a href=\"report.pdf\">Download</a><img src=\"logo.png\">"));
        mail.attachments.add(new Mail.Attachment("report.pdf", "application/octet-stream", 1, new byte[1], null,
                "report.pdf"));
        mail.attachments.add(new Mail.Attachment("logo.png", "image/png", 1, new byte[1], null, "logo.png"));
        MailPage.dropInline(mail);
        assertEquals(List.of("report.pdf"), mail.attachments.stream().map(Mail.Attachment::name).toList());
    }

    @Test
    void aSignatureSeparatorEndsAFlowedParagraph() {
        assertTrue(EmlReader.unflow("Thanks for the \r\nnotes \r\n-- \r\nAna", false).contains("notes \n-- \nAna"));
    }

    @Test
    void plainResetsTheFontBeforeLaterBytesAreDecoded() {
        String rtf = "{\\rtf1\\ansi\\deff0\\fromtext{\\fonttbl{\\f0\\fcharset0 Arial;}{\\f1\\fcharset204 Arial;}}"
                + "\\f1\\'e9\\plain\\'e9}";
        RtfBody.Body body = RtfBody.read(rtf.getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(body.content().contains("\u0439\u00e9"), body.content());
    }
}
