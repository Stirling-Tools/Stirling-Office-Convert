package stirling.software.officeconvert.lineart;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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

import stirling.software.officeconvert.extract.PdfFiles;

final class ImageWalk {

    record Found(COSStream stream, PDResources resources, int page) {}

    record Content(COSStream stream, PDResources resources, int page) {}

    record Walk(List<Found> images, List<Content> contents) {}

    private record Pending(COSDictionary dictionary, PDResources resources, int page) {}

    private final Map<COSStream, Found> images = new LinkedHashMap<>();
    private final Map<COSStream, Content> contents = new LinkedHashMap<>();
    private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<COSBase> masks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final PDDocument document;

    private ImageWalk(PDDocument document) {
        this.document = document;
    }

    static List<Found> images(PDDocument document) throws IOException {
        return walk(document).images();
    }

    static Walk walk(PDDocument document) throws IOException {
        ImageWalk walk = new ImageWalk(document);
        int number = 0;
        for (PDPage page : document.getPages()) {
            PdfFiles.stopIfInterrupted();
            number++;
            PDResources resources = page.getResources();
            COSBase body = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            if (body instanceof COSStream stream) {
                walk.content(stream, resources, number);
            } else if (body instanceof COSArray array) {
                for (COSBase part : array) {
                    if (part.getCOSObject() instanceof COSStream stream) {
                        walk.content(stream, resources, number);
                    }
                }
            }
            if (resources != null) {
                walk.pending.push(new Pending(resources.getCOSObject(), resources, number));
            }
            walk.annotations(page, resources, number);
            walk.drain();
        }
        List<Found> found = new ArrayList<>();
        for (Found f : walk.images.values()) {
            if (!walk.masks.contains(f.stream())) {
                found.add(f);
            }
        }
        return new Walk(found, new ArrayList<>(walk.contents.values()));
    }

    private void drain() throws IOException {
        while (!pending.isEmpty()) {
            PdfFiles.stopIfInterrupted();
            Pending p = pending.pop();
            resources(p.dictionary(), p.resources(), p.page());
        }
    }

    private void annotations(PDPage page, PDResources pageResources, int number) {
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
                    form(form, pageResources, number);
                } else if (appearance instanceof COSDictionary states) {
                    for (COSName name : states.keySet()) {
                        if (states.getDictionaryObject(name) instanceof COSStream form) {
                            form(form, pageResources, number);
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
                    form(stream, resources, page);
                }
            }
        }
        if (dictionary.getDictionaryObject(COSName.PATTERN) instanceof COSDictionary patterns) {
            for (COSName name : patterns.keySet()) {
                if (patterns.getDictionaryObject(name) instanceof COSStream pattern && pattern.getInt(COSName.PATTERN_TYPE) == 1) {
                    form(pattern, resources, page);
                }
            }
        }
        if (dictionary.getDictionaryObject(COSName.FONT) instanceof COSDictionary fonts) {
            for (COSName name : fonts.keySet()) {
                if (fonts.getDictionaryObject(name) instanceof COSDictionary font) {
                    type3(font, resources, page);
                }
            }
        }
    }

    private void type3(COSDictionary font, PDResources inherited, int page) {
        if (!COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE)) || !visited.add(font)) {
            return;
        }
        PDResources resources = inherited;
        if (font.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary own) {
            resources = new PDResources(own, document.getResourceCache());
            pending.push(new Pending(own, resources, page));
        }
        if (font.getDictionaryObject(COSName.CHAR_PROCS) instanceof COSDictionary procs) {
            for (COSName glyph : procs.keySet()) {
                if (procs.getDictionaryObject(glyph) instanceof COSStream proc) {
                    content(proc, resources, page);
                }
            }
        }
    }

    private void form(COSStream form, PDResources inherited, int page) {
        if (!visited.add(form)) {
            return;
        }
        PDResources resources = inherited;
        if (form.getDictionaryObject(COSName.RESOURCES) instanceof COSDictionary own) {
            resources = new PDResources(own, document.getResourceCache());
            pending.push(new Pending(own, resources, page));
        }
        content(form, resources, page);
    }

    private void content(COSStream stream, PDResources resources, int page) {
        contents.putIfAbsent(stream, new Content(stream, resources, page));
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
