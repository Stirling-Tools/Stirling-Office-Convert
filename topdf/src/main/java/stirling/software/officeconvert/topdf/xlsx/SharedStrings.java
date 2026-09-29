package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.SecureXml;

final class SharedStrings {

    static final SharedStrings NONE = new SharedStrings(List.of());

    private final List<RichText> items;

    private SharedStrings(List<RichText> items) {
        this.items = items;
    }

    RichText get(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : RichText.EMPTY;
    }

    int size() {
        return items.size();
    }

    static SharedStrings read(InputStream in, RenderJob job) throws IOException {
        List<RichText> items = new ArrayList<>();
        XMLStreamReader r = SecureXml.reader(in);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("si")) {
                    items.add(RichText.read(r));
                    if ((items.size() & 4095) == 0) {
                        job.checkpoint();
                    }
                }
            }
        } catch (XMLStreamException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            job.warn("The shared strings are damaged; " + items.size() + " were read");
        } finally {
            WorksheetReader.close(r);
        }
        return new SharedStrings(items);
    }
}
