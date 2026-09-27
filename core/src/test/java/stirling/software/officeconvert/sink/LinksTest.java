package stirling.software.officeconvert.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LinksTest {

    @Test
    void schemesThatRunOrReachFilesAreDropped() {
        for (String url : new String[] {"javascript:alert(1)", " JavaScript:alert(1)", "vbscript:x", "data:text/html,x",
                "file:///etc/passwd", "\\\\attacker\\share\\x", "file://attacker/share", "ms-word:ofe|u|http://x",
                "search-ms:query=x", "smb://attacker/x", "\u0000javascript:x"}) {
            assertNull(Links.safeUrl(url), url);
        }
    }

    @Test
    void webLinksAreEscapedNotChanged() {
        assertEquals("https://example.com/a%20b?q=1#top", Links.safeUrl("  https://example.com/a b?q=1#top "));
        assertEquals("http://example.com/%C3%BCber", Links.safeUrl("http://example.com/über"));
        assertEquals("http://c.example/%01x%1B[31m%09y", Links.safeUrl("http://c.example/\u0001x\u001b[31m\ty"));
        assertEquals("https://example.com/%22%3E%3Cx", Links.safeUrl("https://example.com/\"><x"));
    }

    @Test
    void mailLinksKeepAddressesSubjectAndBodyOnly() {
        assertEquals("mailto:a@example.com?subject=Hi&body=Text",
                Links.safeUrl("mailto:a@example.com?attach=/etc/passwd&subject=Hi&%61ttach=x&body=Text"));
        assertEquals("mailto:a@example.com?cc=b@example.com", Links.safeUrl("mailto:a@example.com?cc=b@example.com"));
        assertEquals("mailto:a@example.com", Links.safeUrl("mailto:a@example.com"));
    }
}
