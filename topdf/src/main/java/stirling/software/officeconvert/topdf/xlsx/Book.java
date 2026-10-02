package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.poi.xssf.model.StylesTable;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.PictureDecoder;

final class Book {

    private final RenderJob job;

    private final WorkbookModel workbook;

    private final ExcelColors colors;

    private final StyleCache styles;

    private final ValueFormatter formatter;

    private final Typesetter typesetter;

    private final PrintMetrics metrics;

    private double indent = Double.NaN;

    private double screenIndent = Double.NaN;

    private final Map<String, DecodedPicture> pictures = new HashMap<>();

    private int pictureFailures;

    Book(RenderJob job, WorkbookModel workbook) {
        this.job = job;
        this.workbook = workbook;
        StylesTable st = workbook.styles;
        this.colors = new ExcelColors(workbook.theme, st == null ? null : st.getIndexedColors());
        this.styles = new StyleCache(st, colors);
        this.formatter = new ValueFormatter(colors, workbook.date1904, workbook.strings, SystemDates.host());
        this.typesetter = new Typesetter(job.fonts());
        FontSpec def = styles.defaultFont();
        this.metrics = new PrintMetrics(typesetter.measure(def), def.size());
    }

    // One indent level is three spaces of the Normal style's font, measured only once a cell needs it
    double indentPoints() {
        if (Double.isNaN(indent)) {
            FontSpec def = styles.defaultFont();
            indent = 3 * typesetter.width(" ", def, def.size());
        }
        return indent;
    }

    double screenIndentPixels() {
        if (Double.isNaN(screenIndent)) {
            FontSpec def = styles.defaultFont();
            screenIndent = 3 * typesetter.screenWidth(" ", def, FontMeasure.ppem(def.size(), 96));
        }
        return screenIndent;
    }

    RenderJob job() {
        return job;
    }

    WorkbookModel workbook() {
        return workbook;
    }

    ExcelColors colors() {
        return colors;
    }

    StyleCache styles() {
        return styles;
    }

    ValueFormatter formatter() {
        return formatter;
    }

    Typesetter typesetter() {
        return typesetter;
    }

    PrintMetrics metrics() {
        return metrics;
    }

    DecodedPicture picture(String part) {
        if (pictures.containsKey(part)) {
            return pictures.get(part);
        }
        DecodedPicture p = null;
        try {
            byte[] data = job.zip().read(part);
            p = PictureDecoder.decode(job.document(), data);
        } catch (IOException | RuntimeException e) {
            if (pictureFailures++ == 0) {
                job.warn("A picture could not be drawn (" + part + ")");
            }
        }
        pictures.put(part, p);
        return p;
    }
}
