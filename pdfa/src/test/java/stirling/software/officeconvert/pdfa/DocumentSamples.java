package stirling.software.officeconvert.pdfa;

import static stirling.software.officeconvert.pdfa.RuleSamples.doc;
import static stirling.software.officeconvert.pdfa.RuleSamples.tagged;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

final class DocumentSamples {

    private static final String XMP_HEAD = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\" bytes=\"1234\" "
            + "encoding=\"UTF-8\"?>";

    private DocumentSamples() {}

    static void register() {
        doc("d01_annotations_and_actions", Set.of("1:6.5.3-4", "1:6.5.3-5", "1:6.5.3-6", "1:6.6.1-2", "1:6.6.1-3",
                "2:6.3.3-2", "2:6.3.3-3", "2:6.3.3-4", "2:6.5.1-2", "2:6.4.2-1", "2:6.4.2-2", "2:6.1.12-1",
                "2:6.10-1", "2:6.10-2", "2:6.11-1"), d -> {
                    PDPage p = page(d, "Annotation appearances and actions");
                    COSArray annots = new COSArray();
                    COSDictionary link = annot("Link", 50, 700);
                    COSDictionary print = new COSDictionary();
                    print.setItem(COSName.S, COSName.getPDFName("Named"));
                    print.setItem(COSName.N, COSName.getPDFName("Print"));
                    link.setItem(COSName.A, print);
                    COSDictionary ap = new COSDictionary();
                    ap.setItem(COSName.N, ObjectSamples.form(d, "0 0 1 RG 0 0 100 20 re S"));
                    ap.setItem(COSName.D, ObjectSamples.form(d, "1 0 0 RG 0 0 100 20 re S"));
                    link.setItem(COSName.AP, ap);
                    annots.add(link);
                    COSDictionary check = annot("Widget", 50, 650);
                    check.setItem(COSName.FT, COSName.getPDFName("Btn"));
                    check.setString(COSName.T, "check");
                    COSDictionary checkAp = new COSDictionary();
                    checkAp.setItem(COSName.N, ObjectSamples.form(d, "0 0 100 20 re S"));
                    check.setItem(COSName.AP, checkAp);
                    COSDictionary go = new COSDictionary();
                    go.setItem(COSName.S, COSName.getPDFName("Named"));
                    go.setItem(COSName.N, COSName.getPDFName("NextPage"));
                    check.setItem(COSName.A, go);
                    annots.add(check);
                    COSDictionary text = annot("Widget", 50, 600);
                    text.setItem(COSName.FT, COSName.getPDFName("Tx"));
                    text.setString(COSName.T, "text");
                    COSDictionary states = new COSDictionary();
                    states.setItem("On", ObjectSamples.form(d, "0 0 100 20 re S"));
                    COSDictionary textAp = new COSDictionary();
                    textAp.setItem(COSName.N, states);
                    text.setItem(COSName.AP, textAp);
                    annots.add(text);
                    p.getCOSObject().setItem(COSName.ANNOTS, annots);
                    COSDictionary acro = new COSDictionary();
                    COSArray fields = new COSArray();
                    fields.add(check);
                    fields.add(text);
                    acro.setItem(COSName.FIELDS, fields);
                    acro.setItem(COSName.XFA, ObjectSamples.form(d, "<xdp/>"));
                    COSDictionary cat = d.getDocumentCatalog().getCOSObject();
                    cat.setItem(COSName.ACRO_FORM, acro);
                    cat.setBoolean(COSName.getPDFName("NeedsRendering"), true);
                    COSDictionary perms = new COSDictionary();
                    perms.setItem("UR", new COSDictionary());
                    cat.setItem(COSName.PERMS, perms);
                    COSDictionary names = new COSDictionary();
                    names.setItem("AlternatePresentations", new COSDictionary());
                    cat.setItem(COSName.NAMES, names);
                    cat.setItem(COSName.getPDFName("Requirements"), new COSArray());
                    p.getCOSObject().setItem(COSName.getPDFName("PresSteps"), new COSDictionary());
                });
        doc("d02_signature", Set.of("2:6.1.12-2", "2:6.4.3-1"), d -> {
            PDPage p = page(d, "A signed document");
            COSDictionary sig = new COSDictionary();
            sig.setItem(COSName.TYPE, COSName.SIG);
            sig.setItem(COSName.FILTER, COSName.getPDFName("Adobe.PPKLite"));
            sig.setItem(COSName.SUB_FILTER, COSName.getPDFName("adbe.pkcs7.detached"));
            COSArray range = new COSArray();
            for (int v : new int[] {0, 100, 200, 50}) {
                range.add(COSInteger.get(v));
            }
            sig.setItem(COSName.BYTERANGE, range);
            sig.setItem(COSName.CONTENTS, new COSString(new byte[64]));
            COSDictionary ref = new COSDictionary();
            ref.setItem(COSName.TYPE, COSName.getPDFName("SigRef"));
            ref.setItem(COSName.getPDFName("TransformMethod"), COSName.getPDFName("DocMDP"));
            ref.setItem(COSName.getPDFName("DigestLocation"), new COSArray());
            ref.setItem(COSName.getPDFName("DigestMethod"), COSName.getPDFName("MD5"));
            COSArray refs = new COSArray();
            refs.add(ref);
            sig.setItem(COSName.getPDFName("Reference"), refs);
            COSDictionary field = annot("Widget", 50, 700);
            field.setItem(COSName.FT, COSName.SIG);
            field.setString(COSName.T, "signature");
            field.setItem(COSName.V, sig);
            COSDictionary ap = new COSDictionary();
            ap.setItem(COSName.N, ObjectSamples.form(d, "0 0 100 20 re S"));
            field.setItem(COSName.AP, ap);
            COSArray annots = new COSArray();
            annots.add(field);
            p.getCOSObject().setItem(COSName.ANNOTS, annots);
            COSDictionary acro = new COSDictionary();
            COSArray fields = new COSArray();
            fields.add(field);
            acro.setItem(COSName.FIELDS, fields);
            acro.setInt(COSName.getPDFName("SigFlags"), 3);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, acro);
            COSDictionary perms = new COSDictionary();
            perms.setItem("DocMDP", sig);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.PERMS, perms);
        });
        doc("d03_metadata", Set.of("1:6.7.2-2", "1:6.7.5-1", "1:6.7.5-2", "1:6.7.9-2", "1:6.7.11-4",
                "2:6.6.2.1-2", "2:6.6.2.1-3", "2:6.6.2.1-4", "2:6.6.2.3.1-1", "2:6.6.4-4"), d -> {
            PDPage p = page(d, "Metadata that PDF/A does not accept");
            String xmp = XMP_HEAD + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                    + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\" "
                    + "xmlns:id=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:my=\"http://example.com/my/\">"
                    + "<id:part>1</id:part><id:conformance>B</id:conformance><my:thing>value</my:thing>"
                    + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
            COSStream m = d.getDocument().createCOSStream();
            m.setItem(COSName.TYPE, COSName.METADATA);
            m.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
            try (OutputStream o = m.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(xmp.getBytes(StandardCharsets.UTF_8));
            }
            d.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA, m);
            COSStream broken = d.getDocument().createCOSStream();
            broken.setItem(COSName.TYPE, COSName.METADATA);
            broken.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
            try (OutputStream o = broken.createOutputStream()) {
                o.write((XMP_HEAD + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><unclosed>")
                        .getBytes(StandardCharsets.UTF_8));
            }
            p.getCOSObject().setItem(COSName.METADATA, broken);
            COSStream custom = d.getDocument().createCOSStream();
            custom.setItem(COSName.TYPE, COSName.METADATA);
            custom.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
            try (OutputStream o = custom.createOutputStream()) {
                o.write(("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description "
                        + "rdf:about=\"\" xmlns:prism=\"http://prismstandard.org/namespaces/basic/2.0/\">"
                        + "<prism:issn>1234-5678</prism:issn></rdf:Description></rdf:RDF></x:xmpmeta>"
                        + "<?xpacket end=\"w\"?>").getBytes(StandardCharsets.UTF_8));
            }
            COSStream image = ObjectSamples.image(d, 8, false);
            image.setItem(COSName.METADATA, custom);
            COSDictionary xo = new COSDictionary();
            xo.setItem("Im1", image);
            p.getResources().getCOSObject().setItem(COSName.XOBJECT, xo);
            Samples.raw(p, d, "BT /F1 14 Tf 50 780 Td (Metadata that PDF/A does not accept) Tj ET "
                    + "q 100 0 0 100 50 500 cm /Im1 Do Q");
        });
        doc("d04_optional_content_configs", Set.of("2:6.9-1", "2:6.9-2", "2:6.9-3"), d -> {
            PDPage p = page(d, "Optional content configurations");
            COSDictionary a = ocg("A");
            COSDictionary b = ocg("B");
            COSArray all = new COSArray();
            all.add(a);
            all.add(b);
            COSDictionary def = new COSDictionary();
            COSArray order = new COSArray();
            order.add(a);
            def.setItem(COSName.getPDFName("Order"), order);
            COSDictionary other = new COSDictionary();
            other.setString(COSName.NAME, "Same");
            COSDictionary third = new COSDictionary();
            third.setString(COSName.NAME, "Same");
            COSArray configs = new COSArray();
            configs.add(other);
            configs.add(third);
            COSDictionary props = new COSDictionary();
            props.setItem(COSName.OCGS, all);
            props.setItem(COSName.D, def);
            props.setItem(COSName.getPDFName("Configs"), configs);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.OCPROPERTIES, props);
            COSDictionary propsRes = new COSDictionary();
            propsRes.setItem("A", a);
            p.getResources().getCOSObject().setItem(COSName.PROPERTIES, propsRes);
        });
        RuleSamples.raw("d05_unicode_names", Set.of("2:6.1.8-1"), () -> RawPdf.page(
                "/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helv#E9tica/Encoding/WinAnsiEncoding>>>>"
                        + "/ColorSpace<</S1[/Separation/Sp#E9cial/DeviceRGB<</FunctionType 2/Domain[0 1]/C0[1 1 1]"
                        + "/C1[1 0 0]/N 1>>]>>",
                "BT /F1 14 Tf 50 780 Td (Names that are not UTF-8) Tj ET /S1 cs 1 scn 50 600 100 100 re f").bytes());
        tagged("d06_role_map", Set.of("1:6.8.3.4-2", "2:6.7.3.4-2", "2:6.7.3.4-3"), d -> {
            Samples.ALL.get("s21_tagged").make(d);
            COSDictionary root = d.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
            COSDictionary roles = new COSDictionary();
            roles.setName("Paragraph", "Para");
            roles.setName("Para", "Paragraph");
            roles.setName("Table", "Grid");
            root.setItem(COSName.getPDFName("RoleMap"), roles);
            COSDictionary doc = (COSDictionary) root.getDictionaryObject(COSName.K);
            COSDictionary table = new COSDictionary();
            table.setItem(COSName.TYPE, COSName.getPDFName("StructElem"));
            table.setItem(COSName.S, COSName.getPDFName("Table"));
            table.setItem(COSName.P, doc);
            ((COSArray) doc.getDictionaryObject(COSName.K)).add(table);
        });
    }

    static PDPage page(PDDocument d, String title) throws Exception {
        PDPage p = Samples.page(d);
        PDResources res = new PDResources();
        res.put(COSName.getPDFName("F1"), Samples.std(Standard14Fonts.FontName.HELVETICA));
        p.setResources(res);
        Samples.raw(p, d, "BT /F1 14 Tf 50 780 Td (" + title + ") Tj ET");
        return p;
    }

    static COSDictionary annot(String subtype, float x, float y) {
        COSDictionary a = new COSDictionary();
        a.setItem(COSName.TYPE, COSName.ANNOT);
        a.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype));
        a.setItem(COSName.RECT, ColourSamples.floats(x, y, x + 100, y + 20));
        a.setInt(COSName.F, 4);
        return a;
    }

    static COSDictionary ocg(String name) {
        COSDictionary g = new COSDictionary();
        g.setItem(COSName.TYPE, COSName.OCG);
        g.setString(COSName.NAME, name);
        return g;
    }
}
