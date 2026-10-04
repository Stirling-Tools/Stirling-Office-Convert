package stirling.software.officeconvert.topdf.testing;

import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.print.PrinterJob;
import java.beans.XMLDecoder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.invoke.MethodHandles;
import java.sql.DriverManager;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.xml.validation.SchemaFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.sl.draw.Drawable;
import org.apache.poi.ss.util.ImageUtils;
import org.apache.poi.xslf.draw.SVGImageRenderer;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.xmlbeans.XmlObject;

public final class ForbiddenProbe {

    private ForbiddenProbe() {}

    static void formulas(XSSFWorkbook wb) {
        wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        wb.setForceFormulaRecalculation(true);
        XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
    }

    static List<Object> scripts(InputStream in, File schema) throws Exception {
        Object decoded = new XMLDecoder(in).readObject();
        Object serialized = new ObjectInputStream(in).readObject();
        SchemaFactory.newDefaultInstance().newSchema(schema);
        MethodHandles.lookup().findClass("x");
        XmlObject.Factory.newInstance().execQuery("x");
        return List.of(decoded, serialized);
    }

    static void pictures(byte[] data, PDDocument doc, XSSFPicture picture, XSLFPictureData slidePicture, XSLFSlide slide,
            Graphics2D g) throws Exception {
        Toolkit.getDefaultToolkit().getImage("x.png");
        ImageIO.read(new ByteArrayInputStream(data));
        ImageIO.getImageReadersByFormatName("png");
        PDImageXObject.createFromByteArray(doc, data, "x");
        JPEGFactory.createFromByteArray(doc, data);
        picture.resize();
        picture.getImageDimension();
        slidePicture.getImageDimension();
        ImageUtils.getImageDimension(new ByteArrayInputStream(data), XSSFWorkbook.PICTURE_TYPE_PNG);
        slide.draw(g);
        g.setRenderingHint(Drawable.IMAGE_RENDERER, new SVGImageRenderer());
    }

    static void elsewhere() throws Exception {
        DriverManager.getConnection("jdbc:x");
        PrinterJob.getPrinterJob();
        new JEditorPane().setText("x");
        new ImageIcon("x.png").getImage();
    }
}
