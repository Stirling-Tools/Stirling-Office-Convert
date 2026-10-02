package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

final class UndrawnParts {

    private static final String PRESENTATION = "http://schemas.openxmlformats.org/presentationml/2006/main";

    private static final Map<String, String> NEVER_DRAWN = Map.of(".notesslide+xml", "notes", ".notesmaster+xml",
            "notesMaster", ".handoutmaster+xml", "handoutMaster");

    private UndrawnParts() {}

    static Set<String> of(OfficeZip zip) throws InterruptedIOException {
        try {
            return search(zip);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    private static Set<String> search(OfficeZip zip) throws IOException {
        Set<String> layouts = new LinkedHashSet<>();
        Set<String> masters = new HashSet<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (String part : zip.partNames()) {
            String type = zip.contentType(part);
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            int plus = t.lastIndexOf('.');
            if (t.endsWith(".slidelayout+xml")) {
                layouts.add(lower(part));
            } else if (t.endsWith(".slidemaster+xml")) {
                masters.add(lower(part));
            } else if (plus >= 0 && NEVER_DRAWN.containsKey(t.substring(plus))) {
                hide(zip, hidden, part, NEVER_DRAWN.get(t.substring(plus)));
            }
        }
        if (layouts.isEmpty()) {
            return hidden;
        }
        Set<String> used = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String part : zip.partNames()) {
            String p = lower(part);
            if (!layouts.contains(p) && !masters.contains(p) && !hidden.contains(p) && !p.endsWith(".rels")) {
                reach(zip, part, layouts, used, pending);
            }
        }
        while (!pending.isEmpty()) {
            reach(zip, pending.pop(), layouts, used, pending);
        }
        for (String part : zip.partNames()) {
            String p = lower(part);
            if (layouts.contains(p) && !used.contains(p)) {
                hide(zip, hidden, part, "sldLayout");
            }
        }
        return hidden;
    }

    private static void hide(OfficeZip zip, Set<String> hidden, String part, String root)
            throws InterruptedIOException {
        if (readable(zip, part, root)) {
            hidden.add(lower(part));
            hidden.add(lower(OfficeZip.relsPartFor(part)));
        }
    }

    private static boolean readable(OfficeZip zip, String part, String root) throws InterruptedIOException {
        try {
            zip.peekRelationships(part);
            XMLReader reader = PoiXml.reader();
            Root found = new Root();
            reader.setContentHandler(found);
            reader.setErrorHandler(found);
            try (InputStream in = zip.open(part)) {
                reader.parse(new InputSource(in));
            }
            return root.equals(found.name) && PRESENTATION.equals(found.uri);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | SAXException | RuntimeException e) {
            return false;
        }
    }

    private static final class Root extends DefaultHandler {

        String name;

        String uri;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (name == null) {
                name = localName;
                this.uri = uri;
            }
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }

    private static void reach(OfficeZip zip, String part, Set<String> layouts, Set<String> used, Deque<String> pending)
            throws IOException {
        for (Relationship r : zip.peekRelationships(part).all()) {
            String target = r.part() == null ? null : lower(r.part());
            if (target != null && layouts.contains(target) && used.add(target)) {
                pending.push(r.part());
            }
        }
    }

    private static String lower(String part) {
        return OfficeZip.canonical(part).toLowerCase(Locale.ROOT);
    }
}
