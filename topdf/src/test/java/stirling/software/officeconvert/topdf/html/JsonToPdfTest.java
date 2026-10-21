package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class JsonToPdfTest {

    @TempDir
    Path dir;

    String convert(String json) throws IOException {
        Path in = Files.writeString(dir.resolve("doc.json"), json, StandardCharsets.UTF_8);
        Path out = dir.resolve("doc.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(612f, doc.getPage(0).getMediaBox().getWidth(), 1f);
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aHandWrittenDocumentIsDrawn() throws IOException {
        String text = convert("""
                {"format":"stirling-office-convert","version":1,
                 "page":{"width":612,"height":792,"marginTop":72,"marginBottom":72,"marginLeft":72,"marginRight":72},
                 "styles":{"normal":{"font":"Arial","size":11},
                           "styles":[{"id":"Heading1","name":"heading 1","run":{"font":"Arial","size":20,"bold":true}}]},
                 "lists":[{"id":7,"levels":[{"format":"decimal","text":"%1."}]}],
                 "body":[
                  {"type":"paragraph","style":"Heading1","content":[{"type":"text","text":"Hand made"}]},
                  {"type":"paragraph","list":{"id":7,"level":0},"content":[{"type":"text","text":"Item one"}]},
                  {"type":"table","columns":[100,100],"rows":[{"cells":[
                    {"top":{"width":1},"paragraphs":[{"content":[{"text":"A1"}]}]},
                    {"paragraphs":[{"content":[{"text":"B1","style":{"bold":true}}]}]}]}]}
                 ]}""");
        for (String s : new String[] {"Hand made", "1.", "Item one", "A1", "B1"}) {
            assertTrue(text.contains(s), s + " missing from " + text);
        }
    }

    @Test
    void anEmptyTableIsLeftOutWithAWarning() throws IOException {
        Path in = Files.writeString(dir.resolve("empty.json"), "{\"body\":[{\"type\":\"table\",\"rows\":[{\"cells\":[]}]},"
                + "{\"content\":[{\"text\":\"after\"}]}]}", StandardCharsets.UTF_8);
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("empty.pdf"),
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("table")), r.warnings().toString());
    }

    @Test
    void badDocumentsFailWithAReason() {
        assertThrows(IOException.class, () -> convert("{\"format\":\"something-else\",\"body\":[]}"));
        assertThrows(IOException.class, () -> convert("{\"format\":\"stirling-office-convert\",\"version\":99}"));
        assertThrows(IOException.class, () -> convert("{\"body\":[{\"type\":\"paragraph\",\"content\":\"text\"}]}"));
        assertThrows(IOException.class, () -> convert("{\"body\":[" + "[".repeat(1_000)));
        assertThrows(IOException.class, () -> convert("not json"));
    }
}
