package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Outlines;

class PptxOutlineTest {

    @TempDir
    Path dir;

    @Test
    void eachShownSlideIsBookmarkedByItsTitle() throws IOException {
        byte[] pptx = Decks.deck(ppt -> {
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            XSLFSlideLayout title = master.getLayout(SlideLayout.TITLE);
            XSLFSlideLayout content = master.getLayout(SlideLayout.TITLE_AND_CONTENT);
            titled(ppt.createSlide(title), "Quarterly\u000Breview");
            titled(ppt.createSlide(content), "Agenda");
            ppt.createSlide();
            titled(ppt.createSlide(content), "Agenda");
            XSLFSlide hidden = ppt.createSlide(content);
            titled(hidden, "Backup");
            hidden.setHidden(true);
            titled(ppt.createSlide(content), "  Wrap   up ");
        });
        Decks.Converted c = Decks.convert(dir, "deck.pptx", pptx);
        assertEquals(List.of("1 p1 Quarterly review", "1 p2 Agenda", "1 p3 Slide 3", "1 p4 Agenda (2)",
                "1 p5 Wrap up"), Outlines.titles(c.pdf()));
    }

    private static void titled(XSLFSlide slide, String text) {
        XSLFTextShape t = slide.getPlaceholder(0);
        t.setText(text);
    }
}
