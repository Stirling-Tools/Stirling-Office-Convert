package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class StreamBoundsAndCacheTest {

    @Test
    void jpxPrefixFiltersCannotExpandPastTheirLimit() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            byte[] bytes = new byte[8192];
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(bytes);
            }
            COSArray filters = new COSArray();
            filters.add(COSName.FLATE_DECODE);
            filters.add(COSName.JPX_DECODE);
            stream.setItem(COSName.FILTER, filters);
            assertThrows(Decoded.TooLarge.class, () -> Decoded.before(stream, COSName.JPX_DECODE, 4096, "JPX"));
            assertArrayEquals(bytes, Decoded.before(stream, COSName.JPX_DECODE, 8192, "JPX"));
        }
    }

    @Test
    void fontProgramsAreBoundedBeforeFontParsing() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
                byte[] chunk = new byte[8192];
                for (long n = 0; n <= FontProgramBounds.MAX_BYTES; n += chunk.length) {
                    out.write(chunk);
                }
            }
            COSDictionary descriptor = new COSDictionary();
            descriptor.setItem(COSName.FONT_FILE2, stream);
            page.getCOSObject().setItem(COSName.getPDFName("TestFontDescriptor"), descriptor);
            assertThrows(Decoded.TooLarge.class, () -> FontProgramBounds.run(doc));
        }
    }

    @Test
    void contentCacheReusesTokensAndInvalidatesEveryListContainingAChangedStream() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            COSStream first = doc.getDocument().createCOSStream();
            COSStream second = doc.getDocument().createCOSStream();
            ContentTokens.write(first, List.of(COSInteger.ONE, Operator.getOperator("w")));
            ContentTokens.write(second, List.of(COSInteger.ZERO, Operator.getOperator("J")));
            ContentCache cache = ContentCache.open();
            try {
                List<COSStream> streams = List.of(first, second);
                List<Object> old = ContentTokens.parse(streams);
                assertSame(cache.tokens(streams), ContentTokens.parse(streams));
                ContentTokens.write(first, List.of(COSInteger.get(2), Operator.getOperator("w")));
                assertNull(cache.tokens(streams));
                List<Object> current = ContentTokens.parse(streams);
                assertNotSame(old, current);
                assertEquals(COSInteger.get(2), current.get(0));
                ContentCache nested = ContentCache.open();
                try {
                    assertNotSame(cache, ContentCache.current());
                    assertNull(nested.tokens(streams));
                } finally {
                    nested.close();
                }
                assertSame(cache, ContentCache.current());
            } finally {
                cache.close();
            }
            assertNull(ContentCache.current());
        }
    }
}
