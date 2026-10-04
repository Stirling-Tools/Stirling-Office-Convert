package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class ParserReuseConcurrencyTest {
    @Test
    void concurrentReusePreservesNamespacesAndRejectsEntitiesAfterFailures() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 256; i++) {
                int request = i;
                tasks.add(executor.submit(() -> {
                    assertThrows(IOException.class, () -> SecureXml.parse(input("<broken>")));
                    assertThrows(IOException.class, () -> SecureXml.parse(input(
                            "<!DOCTYPE a [<!ENTITY x 'EXPANDED'>]><a>&x;</a>")));
                    try {
                        var doc = SecureXml.parse(input("<a xmlns='urn:" + request + "'>" + request + "</a>"));
                        assertEquals("urn:" + request, doc.getDocumentElement().getNamespaceURI());
                        assertEquals(Integer.toString(request), doc.getDocumentElement().getTextContent());
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }));
            }
            for (var task : tasks) {
                task.get();
            }
        }
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
