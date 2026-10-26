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

    @Test
    void singleByteCyrillicIsToldFromWesternText() throws IOException {
        String russian = "\u041f\u0440\u0438\u0432\u0435\u0442, \u043c\u0438\u0440! \u042d\u0442\u043e \u043f\u0440\u043e\u0441\u0442\u043e\u0439 \u0442\u0435\u043a\u0441\u0442.\n";
        String french = "Le caf\u00e9 est d\u00e9j\u00e0 pr\u00eat, \u00e0 bient\u00f4t \u00e9t\u00e9 na\u00efve.\n";
        Path cp1251 = Files.write(dir.resolve("ru1251.txt"), russian.repeat(10).getBytes(TextEncoding.WINDOWS_1251));
        Path koi8 = Files.write(dir.resolve("ru-koi8.txt"), russian.repeat(10).getBytes(TextEncoding.KOI8_R));
        Path western = Files.write(dir.resolve("fr.txt"), french.repeat(10).getBytes(TextEncoding.WINDOWS_1252));
        assertEquals(TextEncoding.WINDOWS_1251, TextEncoding.detect(cp1251).charset());
        assertEquals(TextEncoding.KOI8_R, TextEncoding.detect(koi8).charset());
        assertEquals(TextEncoding.WINDOWS_1252, TextEncoding.detect(western).charset());
    }
}
