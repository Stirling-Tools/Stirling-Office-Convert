package stirling.software.officeconvert.pdfa;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.extract.PdfFiles;

final class ScaledResources {

    private final PDDocument doc;

    private final float scale;

    private ScaledResources(PDDocument doc, float scale) {
        this.doc = doc;
        this.scale = scale;
    }

    static PDResources of(PDDocument doc, PDResources resources, float scale) throws IOException {
        if (resources == null) {
            return new PDResources();
        }
        return new PDResources(new ScaledResources(doc, scale).resources(resources.getCOSObject()));
    }

    private COSDictionary resources(COSDictionary original) throws IOException {
        PdfFiles.stopIfInterrupted();
        COSDictionary copy = new COSDictionary(original);
        COSDictionary patterns = ContentGraph.dict(original.getDictionaryObject(COSName.PATTERN));
        if (patterns != null) {
            COSDictionary adjusted = new COSDictionary(patterns);
            copy.setItem(COSName.PATTERN, adjusted);
            for (COSName name : patterns.keySet()) {
                COSDictionary pattern = ContentGraph.dict(patterns.getDictionaryObject(name));
                if (pattern == null) {
                    continue;
                }
                COSDictionary scaled = duplicate(pattern);
                AffineTransform transform = AffineTransform.getScaleInstance(scale, scale);
                transform.concatenate(PDAbstractPattern.create(pattern, null).getMatrix().createAffineTransform());
                scaled.setItem(COSName.MATRIX, new Matrix(transform).toCOSArray());
                adjusted.setItem(name, scaled);
            }
        }
        COSDictionary objects = ContentGraph.dict(original.getDictionaryObject(COSName.XOBJECT));
        if (objects != null) {
            COSDictionary adjusted = new COSDictionary(objects);
            copy.setItem(COSName.XOBJECT, adjusted);
            for (COSName name : objects.keySet()) {
                COSDictionary object = ContentGraph.dict(objects.getDictionaryObject(name));
                if (!(object instanceof COSStream) || !COSName.FORM.equals(object.getCOSName(COSName.SUBTYPE))
                        || object.containsKey(COSName.RESOURCES)) {
                    continue;
                }
                COSDictionary form = duplicate(object);
                form.setItem(COSName.RESOURCES, original);
                adjusted.setItem(name, form);
            }
        }
        return copy;
    }

    private COSDictionary duplicate(COSDictionary original) throws IOException {
        if (!(original instanceof COSStream stream)) {
            return new COSDictionary(original);
        }
        COSStream copy = doc.getDocument().createCOSStream();
        copy.addAll(stream);
        try (InputStream in = stream.createRawInputStream(); OutputStream out = copy.createRawOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(buffer)) >= 0; ) {
                PdfFiles.stopIfInterrupted();
                total += n;
                if (total > ContentTokens.MAX_CONTENT_BYTES) {
                    throw new IOException("A form or pattern is too large to scale safely");
                }
                out.write(buffer, 0, n);
            }
        }
        return copy;
    }
}
