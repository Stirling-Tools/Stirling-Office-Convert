package stirling.software.officeconvert.topdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;

class HtmlFormsTest {

    @TempDir
    Path dir;

    private Path convert(String html) throws IOException {
        Path in = Files.writeString(dir.resolve("form.html"), html, StandardCharsets.UTF_8);
        Path out = dir.resolve("form.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)));
        return out;
    }

    private static String page(String body) {
        return "<!DOCTYPE html><html><head><meta charset=utf-8><style>body{font-family:Arial;font-size:11pt}</style>"
                + "</head><body><form>" + body + "</form></body></html>";
    }

    @Test
    void controlsBecomeFieldsOfTheirKindWithTheirValues() throws IOException {
        Path pdf = convert(page("""
                <label>Name <input name=name value="Sam Taylor" maxlength=20 required></label>
                <input type=email name=mail placeholder="you@example.com">
                <input type=password name=pw value=secret>
                <textarea name=note>First line
                Second line</textarea>
                <select name=country><option>UK</option><option value=ie selected>Ireland</option></select>
                <select name=extras multiple><option selected>Wifi</option><option>Parking</option><option selected>Breakfast</option></select>
                <input type=checkbox name=news checked> <input type=checkbox name=terms value=agreed>
                <input type=radio name=plan value=annual> <input type=radio name=plan value=monthly checked>
                <input name=ref value=R-1 readonly> <input name=old value=x disabled>
                <input type=hidden name=secret value=h>
                """));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertNotNull(form);
            PDTextField name = assertInstanceOf(PDTextField.class, form.getField("name"));
            assertEquals("Sam Taylor", name.getValue());
            assertEquals(20, name.getMaxLen());
            assertTrue(name.isRequired());
            assertEquals("Name", name.getAlternateFieldName());
            PDTextField mail = assertInstanceOf(PDTextField.class, form.getField("mail"));
            assertEquals("", mail.getValue());
            assertTrue(appearanceText(mail).contains("you@example.com"), "the placeholder is shown, not filled in");
            assertTrue(assertInstanceOf(PDTextField.class, form.getField("pw")).isPassword());
            assertFalse(appearanceText(form.getField("pw")).contains("secret"));
            PDTextField note = assertInstanceOf(PDTextField.class, form.getField("note"));
            assertTrue(note.isMultiline());
            assertEquals("First line\nSecond line", note.getValue());
            PDComboBox country = assertInstanceOf(PDComboBox.class, form.getField("country"));
            assertEquals(List.of("UK", "ie"), country.getOptionsExportValues());
            assertEquals(List.of("UK", "Ireland"), country.getOptionsDisplayValues());
            assertEquals(List.of("ie"), country.getValue());
            PDListBox extras = assertInstanceOf(PDListBox.class, form.getField("extras"));
            assertTrue(extras.isMultiSelect());
            assertEquals(List.of("Wifi", "Breakfast"), extras.getValue());
            assertEquals("on", assertInstanceOf(PDCheckBox.class, form.getField("news")).getValue());
            PDCheckBox terms = assertInstanceOf(PDCheckBox.class, form.getField("terms"));
            assertFalse(terms.isChecked());
            assertEquals("agreed", terms.getOnValue());
            PDRadioButton plan = assertInstanceOf(PDRadioButton.class, form.getField("plan"));
            assertEquals(2, plan.getWidgets().size());
            assertEquals("monthly", plan.getValue());
            assertEquals(java.util.Set.of("annual", "monthly"), plan.getOnValues());
            assertTrue(form.getField("ref").isReadOnly());
            assertTrue(form.getField("old").isReadOnly());
            assertNull(form.getField("secret"));
            int widgets = 0;
            for (PDField f : form.getFieldTree()) {
                for (PDAnnotationWidget w : f.getWidgets()) {
                    assertNotNull(w.getAppearance(), f.getFullyQualifiedName() + " shows without regenerating");
                    assertNotNull(w.getPage());
                    widgets++;
                }
            }
            assertEquals(12, widgets);
            assertEquals(widgets, doc.getPage(0).getAnnotations().size());
        }
    }

    @Test
    void valuesAreDrawnInTheFieldsNotUnderThem() throws IOException {
        Path pdf = convert(page("<p>Name <input name=name value=Zebedee></p><p><button>Send it</button></p>"));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Name") && text.contains("Send it"), text);
            assertFalse(text.contains("Zebedee"), "a value drawn on the page would show through an edited field");
            assertTrue(appearanceText(doc.getDocumentCatalog().getAcroForm().getField("name")).contains("Zebedee"));
            assertEquals(1, doc.getDocumentCatalog().getAcroForm().getFields().size(), "a button is drawn, not a field");
        }
    }

    @Test
    void filledInFieldsKeepTheirNewValues() throws IOException {
        Path pdf = convert(page("""
                <input name=name value=Before> <select name=size><option>S</option><option>M</option></select>
                <input type=checkbox name=ok> <input type=radio name=r value=a checked><input type=radio name=r value=b>
                """));
        Path filled = dir.resolve("filled.pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            form.getField("name").setValue("After");
            form.getField("size").setValue("M");
            ((PDCheckBox) form.getField("ok")).check();
            form.getField("r").setValue("b");
            doc.save(filled.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(filled.toFile())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            PDField name = form.getField("name");
            assertEquals("After", name.getValueAsString());
            String shown = appearanceText(name);
            assertTrue(shown.contains("After") && !shown.contains("Before"), shown);
            assertEquals(List.of("M"), ((PDComboBox) form.getField("size")).getValue());
            assertTrue(((PDCheckBox) form.getField("ok")).isChecked());
            assertEquals("b", ((PDRadioButton) form.getField("r")).getValue());
        }
    }

    @Test
    void dottedNamesNestAndRepeatedNamesStayApart() throws IOException {
        Path pdf = convert(page("""
                <input name=user.first value=Ann> <input name=user.last value=Lee>
                <input name=dup value=one> <input name=dup value=two> <input value=unnamed>
                """));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertEquals("Ann", form.getField("user.first").getValueAsString());
            assertEquals("Lee", form.getField("user.last").getValueAsString());
            assertEquals("one", form.getField("dup").getValueAsString());
            assertEquals("two", form.getField("dup_2").getValueAsString());
            assertEquals("unnamed", form.getField("unnamed-1").getValueAsString());
        }
    }

    @Test
    void aPageWithoutControlsHasNoForm() throws IOException {
        Path pdf = convert(page("<p>Just text, <b>bold</b> and a <a href='https://example.com'>link</a>.</p>"));
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertNull(doc.getDocumentCatalog().getAcroForm());
            assertFalse(doc.getDocumentCatalog().getCOSObject().containsKey("AcroForm"));
        }
    }

    @Test
    void theWordPackageHoldsTheControlsAsContentControls() throws IOException {
        Path in = Files.writeString(dir.resolve("form.html"), page("""
                <input name=a value=x> <select name=s><option>One</option></select> <input type=checkbox name=c checked>
                """), StandardCharsets.UTF_8);
        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        HtmlDocument.write(in, docx);
        String xml = part(docx.toByteArray(), "word/document.xml");
        assertTrue(xml.contains("<w:text/>"), xml);
        assertTrue(xml.contains("<w:dropDownList><w:listItem w:displayText=\"One\" w:value=\"One\"/>"), xml);
        assertTrue(xml.contains("<w14:checked w14:val=\"1\"/>"), xml);
        assertEquals(3, xml.split("<wp:inline ", -1).length - 1);
    }

    private static String part(byte[] zip, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException(name + " is missing");
    }

    private static String appearanceText(PDField field) throws IOException {
        PDAppearanceStream ap = field.getWidgets().get(0).getNormalAppearanceStream();
        assertNotNull(ap);
        try (PDDocument one = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                    new org.apache.pdfbox.pdmodel.common.PDRectangle(ap.getBBox().getWidth() + 10,
                            ap.getBBox().getHeight() + 10));
            one.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(one, page)) {
                cs.drawForm(ap);
            }
            String text = new PDFTextStripper().getText(one);
            try (InputStream s = ap.getContentStream().createInputStream()) {
                return text + new String(s.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }
}
