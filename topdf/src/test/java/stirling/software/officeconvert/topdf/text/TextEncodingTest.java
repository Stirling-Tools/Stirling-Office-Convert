package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextEncodingTest {

    @TempDir
    Path dir;

    @Test
    void utf16WithoutAByteOrderMarkIsFoundByItsNuls() throws IOException {
        String text = "name,price\r\ncafé,3.50\r\n日本,12\r\n";
        Path le = Files.write(dir.resolve("le.csv"), text.getBytes(StandardCharsets.UTF_16LE));
        Path be = Files.write(dir.resolve("be.csv"), text.getBytes(StandardCharsets.UTF_16BE));
        Path utf8 = Files.write(dir.resolve("u8.csv"), text.getBytes(StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_16LE, TextEncoding.detect(le).charset());
        assertEquals(StandardCharsets.UTF_16BE, TextEncoding.detect(be).charset());
        assertEquals(StandardCharsets.UTF_8, TextEncoding.detect(utf8).charset());
    }
}
