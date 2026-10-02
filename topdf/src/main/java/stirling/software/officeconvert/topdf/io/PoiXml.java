package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.xmlbeans.XmlOptions;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

public final class PoiXml {

    private static final String SAX = "http://xml.org/sax/";

    private static final Reader READER = new Reader();

    private static volatile boolean installed;

    private PoiXml() {}

    public static void install() {
        if (installed) {
            return;
        }
        synchronized (PoiXml.class) {
            if (installed) {
                return;
            }
            XmlOptions options = POIXMLTypeLoader.DEFAULT_XML_OPTIONS;
            options.setDisallowDocTypeDeclaration(true);
            if (options.getLoadUseXMLReader() == null) {
                options.setLoadUseXMLReader(READER);
            }
            installed = true;
        }
    }

    public static final List<String[]> PROCESS_LIMITS = List.of(new String[] {"jdk.xml.maxElementDepth", "1000"},
            new String[] {"jdk.xml.totalEntitySizeLimit", "0"}, new String[] {"jdk.xml.maxGeneralEntitySizeLimit", "0"},
            new String[] {"jdk.xml.elementAttributeLimit", "10000"});

    /** Sets the jdk.xml limits POI's slide and relationship parsers need, JVM-wide and only where unset; returns
     * the properties it set. The library never calls it on its own, since it also loosens the host's other parsers. */
    public static List<String> raiseProcessLimits() {
        List<String> set = new ArrayList<>();
        synchronized (System.class) {
            for (String[] p : PROCESS_LIMITS) {
                if (System.getProperty(p[0]) == null) {
                    System.setProperty(p[0], p[1]);
                    set.add(p[0]);
                }
            }
        }
        return set;
    }

    public static XMLReader reader() {
        return READER;
    }

    // Built on first use: checking each feature makes a whole parser, and a Word conversion never needs one
    private static final class Factory {
        static final SAXParserFactory INSTANCE = factory();
    }

    private static SAXParser newParser() throws SAXException {
        try {
            synchronized (Factory.INSTANCE) {
                return Factory.INSTANCE.newSAXParser();
            }
        } catch (ParserConfigurationException e) {
            throw new SAXException("The XML parser cannot be made safe", e);
        }
    }

    private static SAXParser secure(SAXParser p) throws SAXException {
        XMLReader r = p.getXMLReader();
        r.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        r.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (String[] limit : SecureXml.limits()) {
            r.setProperty(limit[0], limit[1]);
        }
        r.setEntityResolver(Reader.NO_ENTITIES);
        return p;
    }

    private static SAXParserFactory factory() {
        SAXParserFactory f = SAXParserFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        f.setXIncludeAware(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(SAX + "features/external-general-entities", false);
            f.setFeature(SAX + "features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("The XML parser cannot be made safe", e);
        }
        return f;
    }

    private static final class Reader implements XMLReader {

        static final EntityResolver NO_ENTITIES = (publicId, systemId) -> {
            throw new SAXException("External entities are not allowed: " + systemId);
        };

        private final ThreadLocal<SAXParser> current = new ThreadLocal<>();

        // A parser that finished cleanly is reset to the factory's state and made safe again for the next part
        private final ThreadLocal<SAXParser> idle = new ThreadLocal<>();

        private XMLReader delegate() throws SAXNotSupportedException {
            SAXParser p = current.get();
            if (p == null) {
                p = idle.get();
                idle.remove();
                try {
                    p = p != null ? p : secure(newParser());
                } catch (SAXException e) {
                    throw new SAXNotSupportedException(e.getMessage());
                }
                current.set(p);
            }
            try {
                return p.getXMLReader();
            } catch (SAXException e) {
                throw new SAXNotSupportedException(e.getMessage());
            }
        }

        private XMLReader quietly() {
            try {
                return delegate();
            } catch (SAXNotSupportedException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        @Override
        public boolean getFeature(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
            return delegate().getFeature(name);
        }

        @Override
        public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
            if (weakens(name, value)) {
                return;
            }
            delegate().setFeature(name, value);
        }

        @Override
        public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
            return delegate().getProperty(name);
        }

        @Override
        public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
            if (name.equals(SAX + "properties/lexical-handler") || name.equals(SAX + "properties/declaration-handler")) {
                delegate().setProperty(name, value);
            }
        }

        @Override
        public void setEntityResolver(EntityResolver resolver) {}

        @Override
        public EntityResolver getEntityResolver() {
            return NO_ENTITIES;
        }

        @Override
        public void setDTDHandler(DTDHandler handler) {
            quietly().setDTDHandler(handler);
        }

        @Override
        public DTDHandler getDTDHandler() {
            return quietly().getDTDHandler();
        }

        @Override
        public void setContentHandler(ContentHandler handler) {
            quietly().setContentHandler(handler);
        }

        @Override
        public ContentHandler getContentHandler() {
            return quietly().getContentHandler();
        }

        @Override
        public void setErrorHandler(ErrorHandler handler) {
            quietly().setErrorHandler(handler);
        }

        @Override
        public ErrorHandler getErrorHandler() {
            return quietly().getErrorHandler();
        }

        @Override
        public void parse(InputSource input) throws IOException, SAXException {
            XMLReader r = delegate();
            SAXParser p = current.get();
            boolean clean = false;
            try {
                r.parse(input);
                clean = true;
            } finally {
                current.remove();
                if (clean) {
                    p.reset();
                    idle.set(secure(p));
                }
            }
        }

        @Override
        public void parse(String systemId) throws SAXException {
            current.remove();
            throw new SAXException("Parsing by system id is not allowed: " + systemId);
        }

        private static boolean weakens(String name, boolean value) {
            return switch (name) {
                case XMLConstants.FEATURE_SECURE_PROCESSING, "http://apache.org/xml/features/disallow-doctype-decl" -> !value;
                case SAX + "features/external-general-entities", SAX + "features/external-parameter-entities",
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd", SAX + "features/validation",
                        "http://apache.org/xml/features/xinclude" -> value;
                default -> false;
            };
        }
    }
}
