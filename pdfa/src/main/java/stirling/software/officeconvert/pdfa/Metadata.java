package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.util.DateConverter;

final class Metadata {

    private static final String[] KEYS = {"Title", "Author", "Subject", "Keywords", "Creator", "Producer"};

    private Metadata() {}

    static void run(PDDocument doc, PdfALevel level) throws IOException {
        COSDictionary trailer = doc.getDocument().getTrailer();
        COSDictionary info = ContentGraph.dict(trailer.getDictionaryObject(COSName.INFO));
        if (info == null) {
            info = new COSDictionary();
            trailer.setItem(COSName.INFO, info);
        }
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            COSName k = COSName.getPDFName(KEYS[i]);
            String v = info.getDictionaryObject(k) instanceof COSString s ? clean(s.getString()) : null;
            if (v == null || v.isEmpty()) {
                info.removeItem(k);
                values[i] = null;
            } else {
                info.setItem(k, new COSString(v));
                values[i] = v;
            }
        }
        info.removeItem(COSName.TRAPPED);
        Calendar now = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        now.set(Calendar.MILLISECOND, 0);
        Calendar created = date(info, COSName.CREATION_DATE);
        Calendar modified = now;
        if (created != null) {
            info.setDate(COSName.CREATION_DATE, created);
        }
        info.setDate(COSName.MOD_DATE, modified);
        String xmp = xmp(level, values, created, modified, carried(doc));
        COSStream s = doc.getDocument().createCOSStream();
        s.setItem(COSName.TYPE, COSName.METADATA);
        s.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
        try (OutputStream out = s.createOutputStream()) {
            out.write(xmp.getBytes(StandardCharsets.UTF_8));
        }
        doc.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA, s);
    }

    private static List<String> carried(PDDocument doc) {
        if (!(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.METADATA) instanceof COSStream old)) {
            return List.of();
        }
        try {
            return XmpCarryOver.descriptions(Decoded.bytes(old, StreamFixer.MAX_METADATA_BYTES, "XMP metadata"));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Calendar date(COSDictionary info, COSName key) {
        COSBase v = info.getDictionaryObject(key);
        Calendar c = v instanceof COSString s ? DateConverter.toCalendar(s.getString()) : null;
        if (c == null) {
            info.removeItem(key);
            return null;
        }
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD || cp >= 0x20 && cp <= 0xD7FF
                    || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF;
            if (ok) {
                b.appendCodePoint(cp);
            }
        });
        return b.toString().strip();
    }

    private static String xmp(PdfALevel level, String[] v, Calendar created, Calendar modified,
            List<String> carried) {
        StringBuilder x = new StringBuilder();
        x.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
                .append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
                .append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
                .append("<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n")
                .append("<pdfaid:part>").append(level.part()).append("</pdfaid:part>\n")
                .append("<pdfaid:conformance>").append(level.conformance()).append("</pdfaid:conformance>\n")
                .append("</rdf:Description>\n");
        x.append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                .append("<dc:format>application/pdf</dc:format>\n");
        if (v[0] != null) {
            x.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(v[0]))
                    .append("</rdf:li></rdf:Alt></dc:title>\n");
        }
        if (v[1] != null) {
            x.append("<dc:creator><rdf:Seq><rdf:li>").append(esc(v[1])).append("</rdf:li></rdf:Seq></dc:creator>\n");
        }
        if (v[2] != null) {
            x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(esc(v[2]))
                    .append("</rdf:li></rdf:Alt></dc:description>\n");
        }
        x.append("</rdf:Description>\n");
        x.append("<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n");
        if (v[3] != null) {
            x.append("<pdf:Keywords>").append(esc(v[3])).append("</pdf:Keywords>\n");
        }
        if (v[5] != null) {
            x.append("<pdf:Producer>").append(esc(v[5])).append("</pdf:Producer>\n");
        }
        x.append("</rdf:Description>\n");
        x.append("<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n");
        if (v[4] != null) {
            x.append("<xmp:CreatorTool>").append(esc(v[4])).append("</xmp:CreatorTool>\n");
        }
        if (created != null) {
            x.append("<xmp:CreateDate>").append(iso(created)).append("</xmp:CreateDate>\n");
        }
        x.append("<xmp:ModifyDate>").append(iso(modified)).append("</xmp:ModifyDate>\n");
        x.append("<xmp:MetadataDate>").append(iso(modified)).append("</xmp:MetadataDate>\n");
        x.append("</rdf:Description>\n");
        for (String d : carried) {
            x.append(d).append('\n');
        }
        x.append("</rdf:RDF>\n</x:xmpmeta>\n");
        x.append("<?xpacket end=\"w\"?>");
        return x.toString();
    }

    private static String iso(Calendar c) {
        int offset = c.getTimeZone().getOffset(c.getTimeInMillis()) / 1000;
        OffsetDateTime t = OffsetDateTime.ofInstant(c.toInstant(), ZoneOffset.ofTotalSeconds(offset));
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
