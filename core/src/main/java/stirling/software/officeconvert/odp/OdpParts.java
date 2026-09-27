package stirling.software.officeconvert.odp;

import java.util.Collection;
import java.util.List;

final class OdpParts {

    private OdpParts() {}

    static String styles(float width, float height, String fontDecls, String font, Collection<Integer> backgrounds) {
        String f = Odf.esc(font);
        StringBuilder pageStyles = new StringBuilder();
        StringBuilder masters = new StringBuilder();
        for (int bg : backgrounds) {
            String name = OdpSlideXml.master(bg);
            pageStyles.append("<style:style style:name=\"M").append(name).append("\" style:family=\"drawing-page\">")
                    .append("<style:drawing-page-properties draw:background-size=\"border\" draw:fill=\"solid\"")
                    .append(" draw:fill-color=\"").append(Odf.colour(bg)).append("\"/></style:style>");
            masters.append("<style:master-page style:name=\"").append(name).append("\" style:display-name=\"Background ")
                    .append(Odf.colour(bg)).append("\" style:page-layout-name=\"PM1\" draw:style-name=\"M").append(name)
                    .append("\"/>");
        }
        return Odf.HEADER + "<office:document-styles" + Odf.NAMESPACES + ">" + fontDecls
                + "<office:styles><style:default-style style:family=\"graphic\"><style:graphic-properties"
                + " draw:shadow=\"hidden\" draw:stroke=\"none\" draw:fill=\"none\"/><style:paragraph-properties"
                + " fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:line-height=\"100%\"/><style:text-properties"
                + " style:font-name=\"" + f + "\" fo:font-size=\"18pt\" fo:color=\"#000000\" fo:language=\"en\""
                + " fo:country=\"US\"/></style:default-style></office:styles>"
                + "<office:automatic-styles><style:page-layout style:name=\"PM1\"><style:page-layout-properties"
                + " fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" fo:margin-left=\"0cm\" fo:margin-right=\"0cm\""
                + " fo:page-width=\"" + Odf.cm(width) + "\" fo:page-height=\"" + Odf.cm(height) + "\""
                + " style:print-orientation=\"" + (width >= height ? "landscape" : "portrait") + "\"/></style:page-layout>"
                + "<style:style style:name=\"Mdp1\" style:family=\"drawing-page\"><style:drawing-page-properties"
                + " draw:fill=\"none\"/></style:style>" + pageStyles + "</office:automatic-styles>"
                + "<office:master-styles><style:master-page style:name=\"Default\" style:page-layout-name=\"PM1\""
                + " draw:style-name=\"Mdp1\"/>" + masters + "</office:master-styles></office:document-styles>";
    }

    static String meta(String title, String author, String now) {
        return Odf.HEADER + "<office:document-meta" + Odf.NAMESPACES + "><office:meta>"
                + "<meta:generator>Stirling-PDF</meta:generator>"
                + (title == null || title.isBlank() ? "" : "<dc:title>" + Odf.esc(title) + "</dc:title>")
                + (author == null || author.isBlank() ? "" : "<meta:initial-creator>" + Odf.esc(author) + "</meta:initial-creator>")
                + "<meta:creation-date>" + now + "</meta:creation-date><dc:date>" + now + "</dc:date>"
                + "</office:meta></office:document-meta>";
    }

    static String manifest(List<String[]> pictures) {
        StringBuilder sb = new StringBuilder(Odf.HEADER)
                .append("<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"")
                .append(" manifest:version=\"1.2\"><manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.2\"")
                .append(" manifest:media-type=\"application/vnd.oasis.opendocument.presentation\"/>")
                .append("<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>")
                .append("<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>")
                .append("<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>");
        for (String[] p : pictures) {
            sb.append("<manifest:file-entry manifest:full-path=\"").append(p[0]).append("\" manifest:media-type=\"")
                    .append(p[1]).append("\"/>");
        }
        return sb.append("</manifest:manifest>").toString();
    }
}
