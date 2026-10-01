package stirling.software.officeconvert.topdf.doc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.poi.hpsf.CustomProperties;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;

final class DocProps {

    record Part(String name, String contentType, String relType, String xml) {}

    private static final int MAX_CUSTOM = 1000;

    private static final int MAX_VALUE = 4096;

    private static final String REL = "http://schemas.openxmlformats.org/";

    private DocProps() {}

    static List<Part> parts(HWPFDocument doc) {
        List<Part> out = new ArrayList<>();
        SummaryInformation info = safe(() -> doc.getSummaryInformation());
        DocumentSummaryInformation more = safe(() -> doc.getDocumentSummaryInformation());
        String core = info == null ? null : core(info, more);
        if (core != null) {
            out.add(new Part("docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml",
                    REL + "package/2006/relationships/metadata/core-properties", core));
        }
        String app = app(info, more);
        if (app != null) {
            out.add(new Part("docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml",
                    REL + "officeDocument/2006/relationships/extended-properties", app));
        }
        String custom = more == null ? null : custom(more);
        if (custom != null) {
            out.add(new Part("docProps/custom.xml", "application/vnd.openxmlformats-officedocument.custom-properties+xml",
                    REL + "officeDocument/2006/relationships/custom-properties", custom));
        }
        return out;
    }

    private interface Read<T> {
        T get();
    }

    private static <T> T safe(Read<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String core(SummaryInformation info, DocumentSummaryInformation more) {
        StringBuilder b = new StringBuilder();
        element(b, "dc:title", safe(info::getTitle));
        element(b, "dc:creator", safe(info::getAuthor));
        element(b, "dc:subject", safe(info::getSubject));
        element(b, "cp:keywords", safe(info::getKeywords));
        element(b, "dc:description", safe(info::getComments));
        element(b, "cp:lastModifiedBy", safe(info::getLastAuthor));
        element(b, "cp:revision", safe(info::getRevNumber));
        element(b, "cp:category", more == null ? null : safe(more::getCategory));
        element(b, "cp:lastPrinted", w3c(safe(info::getLastPrinted)));
        element(b, "dcterms:created", w3c(safe(info::getCreateDateTime)));
        element(b, "dcterms:modified", w3c(safe(info::getLastSaveDateTime)));
        if (b.isEmpty()) {
            return null;
        }
        return Xml.HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/"
                + "core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/"
                + "terms/\">" + b + "</cp:coreProperties>";
    }

    private static String app(SummaryInformation info, DocumentSummaryInformation more) {
        StringBuilder b = new StringBuilder();
        if (info != null) {
            element(b, "Template", safe(info::getTemplate));
            Integer words = safe(info::getWordCount);
            element(b, "Words", words == null || words <= 0 ? null : words.toString());
            Integer chars = safe(info::getCharCount);
            element(b, "Characters", chars == null || chars <= 0 ? null : chars.toString());
            Long edit = safe(info::getEditTime);
            element(b, "TotalTime", edit == null || edit <= 0 ? null : Long.toString(edit / 600_000_000L));
        }
        if (more != null) {
            element(b, "Company", safe(more::getCompany));
            element(b, "Manager", safe(more::getManager));
        }
        if (b.isEmpty()) {
            return null;
        }
        return Xml.HEAD + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "extended-properties\">" + b + "</Properties>";
    }

    private static String custom(DocumentSummaryInformation more) {
        CustomProperties props = safe(more::getCustomProperties);
        if (props == null || props.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        int pid = 2;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object v = e.getValue();
            String text = v instanceof Date d ? w3c(d) : v == null ? null : v.toString();
            if (e.getKey() == null || text == null || text.length() > MAX_VALUE || pid - 2 >= MAX_CUSTOM) {
                continue;
            }
            b.append("<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"").append(pid++)
                    .append("\" name=\"").append(Xml.esc(e.getKey())).append("\"><vt:lpwstr>").append(Xml.esc(text))
                    .append("</vt:lpwstr></property>");
        }
        if (b.isEmpty()) {
            return null;
        }
        return Xml.HEAD + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\""
                + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" + b
                + "</Properties>";
    }

    static String w3c(Date d) {
        if (d == null) {
            return null;
        }
        ZonedDateTime t = Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC);
        if (t.getYear() < 1900 || t.getYear() > 9999) {
            return null;
        }
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
    }

    private static void element(StringBuilder b, String name, String value) {
        if (value != null && !value.isBlank() && value.length() < MAX_VALUE) {
            b.append('<').append(name).append('>').append(Xml.esc(value.strip())).append("</").append(name).append('>');
        }
    }
}
