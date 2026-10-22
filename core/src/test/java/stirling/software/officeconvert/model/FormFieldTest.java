package stirling.software.officeconvert.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class FormFieldTest {

    @Test
    void aTagReadsBackAsTheSameField() {
        FormField f = new FormField(FormField.Kind.COMBO, "a.b & c=d?", "Pick, one: üß", "v,2",
                List.of(new FormField.Choice("v,1", "One & \"only\"", false), new FormField.Choice("v,2", "Two\nlines", true),
                        new FormField.Choice("", "", false)),
                false, false, true, true, 0);
        assertEquals(f, FormField.fromTag(f.tag()));
        FormField text = new FormField(FormField.Kind.MULTILINE, "", "", "x\r\ny\t%20+", null, false, true, false, false, 140);
        assertEquals(text, FormField.fromTag(text.tag()));
        FormField radio = new FormField(FormField.Kind.RADIO, "g", "", "on", null, true, false, false, false, 0);
        assertEquals(radio, FormField.fromTag(radio.tag()));
    }

    @Test
    void aTagKeepsAtMostTheChoiceLimit() {
        String tag = "pdf-form-field?kind=combo" + "&choice=a,b,0".repeat(FormField.MAX_CHOICES + 500);
        assertEquals(FormField.MAX_CHOICES, FormField.fromTag(tag).choices().size());
    }

    @Test
    void anyOtherTagIsNotAField() {
        assertNull(FormField.fromTag(null));
        assertNull(FormField.fromTag("CustomerName"));
        assertNull(FormField.fromTag("pdf-form-field?name=x"));
        assertNull(FormField.fromTag("pdf-form-field?kind=slider&name=x"));
        assertNull(FormField.fromTag("pdf-form-field?kind=text&maxlength=lots"));
        assertNull(FormField.fromTag("pdf-form-field?kind=text&name=%zz"));
    }
}
