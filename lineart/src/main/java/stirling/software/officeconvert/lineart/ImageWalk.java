package stirling.software.officeconvert.lineart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;

final class ImageWalk {

    record Found(COSStream stream, PDResources resources, int page) {}

    private final Map<COSStream, Found> images = new LinkedHashMap<>();
    private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSBase> masks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final PDDocument document;

    private ImageWalk(PDDocument document) {
        this.document = document;
    }

    static List<Found> images(PDDocument document) {
        ImageWalk walk = new ImageWalk(document);
        int number = 0;
        for (PDPage page : document.getPages()) {
            number++;
            PDResources resources = page.getResources();
            if (resources != null) {
                walk.resources(resources.getCOSObject(), resources, number);
            }
            walk.annotations(page, number);
        }
        List<Found> found = new ArrayList<>();
        for (Found f : walk.images.values()) {
            if (!walk.masks.contains(f.stream())) {
                found.add(f);
            }
        }
        return found;
    }

    private void annotations(PDPage page, int number) {
        COSArray annotations = page.getCOSObject().getCOSArray(COSName.ANNOTS);
        if (annotations == null) {
            return;
        }
        for (COSBase entry : annotations) {
            if (!(entry.getCOSObject() instanceof COSDictionary annotation)
                    || !(annotation.getDictionaryObject(COSName.AP) instanceof COSDictionary appearances)) {
                continue;
            }
            for (COSName state : new COSName[] {COSName.N, COSName.R, COSName.D}) {
                COSBase appearance = appearances.getDictionaryObject(state);
                if (appearance instanceof COSStream form) {
                    form(form, number);
                } else if (appearance instanceof COSDictionary states) {
                    for (COSName name : states.keySet()) {
                        if (states.getDictionaryObject(name) instanceof COSStream form) {
                            form(form, number);
                        }
                    }
                }
            }
        }
    }

    private void resources(COSDictionary dictionary, PDResources resources, int page) {
        if (!visited.add(dictionary)) {
            return;
        }
        if (dictionary.getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary xobjects) {
            for (COSName name : xobjects.keySet()) {
                if (!(xobjects.getDictionaryObject(name) instanceof COSStream stream)) {
                    continue;
                }
                COSName subtype = stream.getCOSName(COSName.SUBTYPE);
                if (COSName.IMAGE.equals(subtype)) {
                    image(stream, resources, page);
                } else if (COSName.FORM.equals(subtype)) {
                    form(stream, page);
                }
            }
        }
        if (dictionary.getDictionaryObject(COSName.PATTERN) instanceof COSDictionary patterns) {
            for (COSName name : patterns.keySet()) {
                if (patterns.getDictionaryObject(name) instanceof COSStream pattern && pattern.getInt(COSName.PATTERN_TYPE) == 1) {
                    form(pattern, page);
                }
            }
        }
    }

    private void form(COSStream form, int page) {
        if (visited.add(form) && form.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary own) {
            resources(own, new PDResources(own, document.getResourceCache()), page);
        }
    }

    private void image(COSStream stream, PDResources resources, int page) {
        images.putIfAbsent(stream, new Found(stream, resources, page));
        for (COSName key : new COSName[] {COSName.SMASK, COSName.MASK}) {
            if (stream.getDictionaryObject(key) instanceof COSStream mask) {
                masks.add(mask);
            }
        }
    }
}
