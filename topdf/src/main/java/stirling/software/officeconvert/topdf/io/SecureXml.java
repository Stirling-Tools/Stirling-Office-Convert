package stirling.software.officeconvert.topdf.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.ArrayBlockingQueue;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;

import org.apache.xmlbeans.XmlException;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public final class SecureXml {

    public static final int MAX_ELEMENT_DEPTH = 1000;

    private static final String ORACLE = "http://www.oracle.com/xml/jaxp/properties/";

    private static final String[][] LIMITS = {
        {ORACLE + "entityExpansionLimit", "64"},
        // DOCTYPE is refused, so these two only count built-in references such as &amp; in the part; 0 = no limit
        {ORACLE + "maxGeneralEntitySizeLimit", "0"},
        {ORACLE + "totalEntitySizeLimit", "0"},
        {ORACLE + "maxParameterEntitySizeLimit", "4096"},
        {ORACLE + "maxElementDepth", Integer.toString(MAX_ELEMENT_DEPTH)},
        {ORACLE + "elementAttributeLimit", "10000"},
    };

    private static final DocumentBuilderFactory DOM = domFactory();

    private static final XMLInputFactory STAX = staxFactory();

    // Conversions use fresh threads; an exclusive, bounded pool avoids rebuilding parsers for each request.
    private static final ArrayBlockingQueue<DocumentBuilder> REUSED = new ArrayBlockingQueue<>(8);

    private static final EntityResolver NO_ENTITIES = (publicId, systemId) -> {
        throw new SAXException("External entities are not allowed: " + systemId);
    };

    private static final ErrorHandler STRICT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    private SecureXml() {}

    // Our StAX readers refuse a DOCTYPE with this type; parsers built on the JDK's feature name the feature instead
    static final class DoctypeRefused extends XMLStreamException {
        private static final long serialVersionUID = 1L;

        DoctypeRefused() {
            super("DOCTYPE and entities are not allowed in Office XML");
        }
    }

    // A refused DOCTYPE anywhere in the causes, told by the parser's own exception, never by what a message says
    public static boolean refusedDoctype(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof DoctypeRefused) {
                return true;
            }
            boolean parser = t instanceof SAXException || t instanceof XMLStreamException || t instanceof XmlException;
            if (parser && t.getMessage() != null && t.getMessage().contains(DISALLOW_DOCTYPE)) {
                return true;
            }
        }
        return false;
    }

    static String[][] limits() {
        String[][] copy = new String[LIMITS.length][];
        for (int i = 0; i < LIMITS.length; i++) {
            copy[i] = LIMITS[i].clone();
        }
        return copy;
    }

    public static DocumentBuilder documentBuilder() {
        DocumentBuilder builder;
        try {
            synchronized (DOM) {
                builder = DOM.newDocumentBuilder();
            }
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The XML parser cannot be made safe", e);
        }
        builder.setEntityResolver(NO_ENTITIES);
        builder.setErrorHandler(STRICT);
        return builder;
    }

    public static Document parse(InputStream in) throws IOException {
        DocumentBuilder builder = REUSED.poll();
        if (builder == null) {
            builder = documentBuilder();
        }
        boolean clean = false;
        try {
            Document doc = builder.parse(new InputSource(in));
            clean = true;
            return doc;
        } catch (SAXException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed XML", e);
        } finally {
            if (clean) {
                builder.reset();
                builder.setEntityResolver(NO_ENTITIES);
                builder.setErrorHandler(STRICT);
                REUSED.offer(builder);
            }
        }
    }

    public static XMLStreamReader reader(InputStream in) throws IOException {
        String part = OfficeZip.partName(in);
        XMLStreamReader raw;
        try {
            synchronized (STAX) {
                raw = STAX.createXMLStreamReader(in);
            }
        } catch (XMLStreamException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), damaged(part, e));
        }
        NoDoctype reader = new NoDoctype(raw, part);
        try {
            reader.refuse(raw.getEventType());
        } catch (XMLStreamException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        }
        return reader;
    }

    // A part of the package that is not well-formed says which part it is, so the conversion can go on without it
    private static XMLStreamException damaged(String part, XMLStreamException e) {
        if (part == null) {
            return e;
        }
        int depth = 0;
        for (Throwable t = e.getCause(); t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof InterruptedIOException || t instanceof OfficeZip.DamagedPart) {
                return e;
            }
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (refusedDoctype(e)) {
            return e;
        }
        String why = m.replaceFirst("^ParseError at \\[row,col\\]:\\[\\d+,\\d+\\]\\s*Message: ", "");
        return new XMLStreamException(m, new OfficeZip.DamagedPart(part, "The document is damaged: " + part
                + " is not well-formed XML (" + why + ")", e));
    }

    private static final class NoDoctype extends StreamReaderDelegate {

        private final String part;

        NoDoctype(XMLStreamReader reader, String part) {
            super(reader);
            this.part = part;
        }

        void refuse(int event) throws XMLStreamException {
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
                    || event == XMLStreamConstants.ENTITY_DECLARATION) {
                throw new DoctypeRefused();
            }
        }

        @Override
        public int next() throws XMLStreamException {
            int event;
            try {
                event = super.next();
            } catch (XMLStreamException e) {
                throw damaged(part, e);
            }
            refuse(event);
            return event;
        }

        @Override
        public int nextTag() throws XMLStreamException {
            int event;
            do {
                event = next();
            } while (event == XMLStreamConstants.SPACE || event == XMLStreamConstants.COMMENT
                    || event == XMLStreamConstants.PROCESSING_INSTRUCTION
                    || (event == XMLStreamConstants.CHARACTERS && isWhiteSpace()));
            if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                throw new XMLStreamException("Expected a start or end tag", getLocation());
            }
            return event;
        }

        @Override
        public String getElementText() throws XMLStreamException {
            if (getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("Not at a start tag", getLocation());
            }
            StringBuilder text = new StringBuilder();
            int event = next();
            while (event != XMLStreamConstants.END_ELEMENT) {
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE) {
                    text.append(getText());
                } else if (event == XMLStreamConstants.START_ELEMENT) {
                    throw new XMLStreamException("Element text holds a child element", getLocation());
                } else if (event == XMLStreamConstants.END_DOCUMENT) {
                    throw new XMLStreamException("Unexpected end of the document", getLocation());
                }
                event = next();
            }
            return text.toString();
        }
    }

    private static DocumentBuilderFactory domFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newDefaultInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setCoalescing(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature(DISALLOW_DOCTYPE, true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The XML parser cannot be made safe", e);
        }
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (String[] limit : LIMITS) {
            f.setAttribute(limit[0], limit[1]);
        }
        return f;
    }

    private static XMLInputFactory staxFactory() {
        XMLInputFactory f = XMLInputFactory.newDefaultFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External entities are not allowed: " + systemId);
        });
        for (String[] limit : LIMITS) {
            f.setProperty(limit[0], limit[1]);
        }
        return f;
    }
}
