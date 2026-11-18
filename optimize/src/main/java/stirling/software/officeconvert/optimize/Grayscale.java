package stirling.software.officeconvert.optimize;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;

import stirling.software.officeconvert.extract.BrotliBudget;
import stirling.software.officeconvert.extract.Jpeg;
import stirling.software.officeconvert.extract.PdfFiles;

final class Grayscale {

    private static final COSName DECODED_LENGTH = COSName.getPDFName("DL");

    private static final COSName INLINE_GRAY = COSName.getPDFName("G");

    private static final COSName INLINE_HEX = COSName.getPDFName("AHx");

    private static final byte[] HEX_DIGITS = "0123456789ABCDEF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static final COSName DEFAULT_GRAY = COSName.getPDFName("DefaultGray");

    private static final COSName DEFAULT_RGB = COSName.getPDFName("DefaultRGB");

    private static final COSName DEFAULT_CMYK = COSName.getPDFName("DefaultCMYK");

    private static final COSName GTS_PDFA1 = COSName.getPDFName("GTS_PDFA1");

    private static final COSName DEST_OUTPUT_PROFILE = COSName.getPDFName("DestOutputProfile");

    private static final COSName OUTPUT_CONDITION = COSName.getPDFName("OutputCondition");

    private static final COSName OUTPUT_CONDITION_IDENTIFIER = COSName.getPDFName("OutputConditionIdentifier");

    private static final COSName REGISTRY_NAME = COSName.getPDFName("RegistryName");

    private static final String SRGB = "sRGB IEC61966-2.1";

    private static final int SAMPLES_1D = 256;

    private static final int SAMPLES_2D = 64;

    record Applied(List<COSBase> changed, Set<COSStream> images, int unconverted) {}

    private final Fresh fresh;

    private final BrotliBudget budget;

    private final Set<COSBase> changed = new LinkedHashSet<>();

    private final Set<COSStream> images = new LinkedHashSet<>();

    private final Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<COSArray, PDColorSpace> grayPatterns = new IdentityHashMap<>();

    private final Map<COSDictionary, PDResources> defaultsToDrop = new LinkedHashMap<>();

    private final Map<COSDictionary, COSBase> defaultHolders = new IdentityHashMap<>();

    private final Set<COSDictionary> defaultsInUse = Collections.newSetFromMap(new IdentityHashMap<>());

    private int unconverted;

    private boolean quiet;

    private boolean failed;

    private void fail() {
        failed = true;
        if (!quiet) {
            unconverted++;
        }
    }

    private Grayscale(Fresh fresh, BrotliBudget budget) {
        this.fresh = fresh;
        this.budget = budget;
    }

    static Applied apply(PDDocument doc, Fresh fresh) throws IOException {
        Grayscale run = new Grayscale(fresh, BrotliBudget.of(doc));
        for (PDPage page : doc.getPages()) {
            PdfFiles.stopIfInterrupted();
            COSDictionary dict = page.getCOSObject();
            PDResources resources = page.getResources();
            COSBase holder = holderOf(resources, dict);
            run.group(dict, dict);
            COSBase contents = dict.getDictionaryObject(COSName.CONTENTS);
            if (contents instanceof COSStream stream) {
                run.content(stream, resources, holder);
            } else if (contents instanceof COSArray array) {
                run.contentArray(array, dict, resources, holder);
            }
            run.fonts(resources, holder);
            if (dict.getDictionaryObject(COSName.ANNOTS) instanceof COSArray annots) {
                for (int i = 0; i < annots.size(); i++) {
                    if (annots.getObject(i) instanceof COSDictionary annot) {
                        run.annotation(annot, annots.get(i) instanceof COSObject ? annot : dict, resources, holder);
                    }
                }
            }
        }
        COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
        if (catalog.getDictionaryObject(COSName.ACRO_FORM) instanceof COSDictionary form) {
            COSBase holder = catalog.getItem(COSName.ACRO_FORM) instanceof COSObject ? form : catalog;
            run.appearanceString(form, holder);
            if (form.getDictionaryObject(COSName.FIELDS) instanceof COSArray fields) {
                run.fields(fields, form);
            }
        }
        run.dropUnusedDefaults();
        if (!run.changed.isEmpty() || !run.images.isEmpty()) {
            run.pdfaIntent(catalog);
        }
        return new Applied(new ArrayList<>(run.changed), run.images, run.unconverted);
    }

    private void pdfaIntent(COSDictionary catalog) throws IOException {
        if (Roles.pdfaPart(catalog) == 0) {
            return;
        }
        COSArray intents = catalog.getDictionaryObject(COSName.OUTPUT_INTENTS) instanceof COSArray a ? a : null;
        COSDictionary model = null;
        if (intents != null) {
            for (int i = 0; i < intents.size(); i++) {
                if (intents.getObject(i) instanceof COSDictionary intent) {
                    if (GTS_PDFA1.equals(intent.getCOSName(COSName.S))) {
                        return;
                    }
                    if (model == null && intent.getItem(DEST_OUTPUT_PROFILE) != null) {
                        model = intent;
                    }
                }
            }
        }
        COSDictionary intent = new COSDictionary();
        intent.setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"));
        intent.setItem(COSName.S, GTS_PDFA1);
        if (model != null) {
            for (COSName key : new COSName[] {OUTPUT_CONDITION, OUTPUT_CONDITION_IDENTIFIER, REGISTRY_NAME,
                    COSName.INFO, DEST_OUTPUT_PROFILE}) {
                if (model.getItem(key) != null) {
                    intent.setItem(key, model.getItem(key));
                }
            }
        } else {
            COSStream profile = new COSStream();
            try (OutputStream out = profile.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
            }
            profile.setInt(COSName.N, 3);
            intent.setItem(OUTPUT_CONDITION_IDENTIFIER, new COSString(SRGB));
            intent.setItem(COSName.INFO, new COSString(SRGB));
            intent.setItem(REGISTRY_NAME, new COSString("http://www.color.org"));
            intent.setItem(DEST_OUTPUT_PROFILE, fresh.add(profile));
        }
        if (intents == null) {
            intents = new COSArray();
            catalog.setItem(COSName.OUTPUT_INTENTS, intents);
        }
        intents.add(intent);
        changed.add(intents.getKey() != null ? intents : catalog);
    }

    private void fields(COSArray fields, COSBase holder) throws IOException {
        ArrayDeque<COSArray> queue = new ArrayDeque<>();
        queue.add(fields);
        while (!queue.isEmpty()) {
            COSArray level = queue.poll();
            for (int i = 0; i < level.size(); i++) {
                if (level.getObject(i) instanceof COSDictionary field && visited.add(field)) {
                    appearanceString(field, level.get(i) instanceof COSObject ? field : holder);
                    if (field.getDictionaryObject(COSName.KIDS) instanceof COSArray kids) {
                        queue.add(kids);
                    }
                }
            }
        }
    }

    private void annotation(COSDictionary annot, COSBase holder, PDResources pageResources, COSBase pageHolder)
            throws IOException {
        if (!visited.add(annot)) {
            return;
        }
        boolean edited = grayArray(annot, COSName.C) | grayArray(annot, COSName.IC);
        if (annot.getDictionaryObject(COSName.MK) instanceof COSDictionary mk) {
            edited |= grayArray(mk, COSName.BC) | grayArray(mk, COSName.BG);
        }
        if (edited) {
            changed.add(holder);
        }
        appearanceString(annot, holder);
        if (annot.getDictionaryObject(COSName.AP) instanceof COSDictionary ap) {
            for (COSName which : new COSName[] {COSName.N, COSName.R, COSName.D}) {
                COSBase entry = ap.getDictionaryObject(which);
                if (entry instanceof COSStream stream) {
                    form(stream, pageResources, pageHolder);
                } else if (entry instanceof COSDictionary states) {
                    for (COSName state : states.keySet()) {
                        if (states.getDictionaryObject(state) instanceof COSStream stream) {
                            form(stream, pageResources, pageHolder);
                        }
                    }
                }
            }
        }
    }

    private boolean grayArray(COSDictionary dict, COSName key) {
        if (!(dict.getDictionaryObject(key) instanceof COSArray colour) || colour.size() != 3 && colour.size() != 4) {
            return false;
        }
        float[] components = numbers(colour);
        if (components == null) {
            return false;
        }
        COSArray gray = new COSArray();
        gray.add(new COSFloat(colour.size() == 3 ? fromRgb(components) : fromCmyk(components)));
        dict.setItem(key, gray);
        return true;
    }

    private void appearanceString(COSDictionary dict, COSBase holder) throws IOException {
        if (!(dict.getDictionaryObject(COSName.DA) instanceof COSString da)) {
            return;
        }
        byte[] rewritten = rewrite(da.getBytes(), null, holder);
        if (rewritten != null) {
            dict.setItem(COSName.DA, new COSString(rewritten));
            changed.add(holder);
        }
    }

    private void form(COSStream stream, PDResources parent, COSBase parentHolder) throws IOException {
        if (visited.contains(stream)) {
            return;
        }
        PDFormXObject form = new PDFormXObject(stream);
        PDResources resources = form.getResources() != null ? form.getResources() : parent;
        COSBase holder = form.getResources() != null ? holderOf(resources, stream) : parentHolder;
        group(stream, stream);
        fonts(resources, holder);
        content(stream, resources, holder);
    }

    private void dropDefaults(PDResources resources, COSBase holder) {
        if (resources != null && defaultsToDrop.putIfAbsent(resources.getCOSObject(), resources) == null) {
            defaultHolders.put(resources.getCOSObject(), holder);
        }
    }

    private void keepDefaults(PDResources resources) {
        if (resources != null) {
            defaultsInUse.add(resources.getCOSObject());
        }
    }

    private void dropUnusedDefaults() {
        for (Map.Entry<COSDictionary, PDResources> e : defaultsToDrop.entrySet()) {
            if (!defaultsInUse.contains(e.getKey())) {
                removeDefaults(e.getValue(), defaultHolders.get(e.getKey()));
            }
        }
    }

    private void removeDefaults(PDResources resources, COSBase holder) {
        if (resources == null || !visited.add(resources.getCOSObject())
                || !(resources.getCOSObject().getDictionaryObject(COSName.COLORSPACE) instanceof COSDictionary spaces)) {
            return;
        }
        boolean edited = false;
        for (COSName key : new COSName[] {DEFAULT_GRAY, DEFAULT_RGB, DEFAULT_CMYK}) {
            if (spaces.containsKey(key)) {
                spaces.removeItem(key);
                edited = true;
            }
        }
        if (edited) {
            changed.add(spaces.getKey() != null ? spaces
                    : resources.getCOSObject().getKey() != null ? resources.getCOSObject() : holder);
        }
    }

    private static COSBase holderOf(PDResources resources, COSDictionary owner) {
        if (resources == null) {
            return owner;
        }
        COSDictionary dict = resources.getCOSObject();
        if (dict.getKey() != null) {
            return dict;
        }
        COSDictionary node = owner;
        for (int depth = 0; node != null && depth < 64; depth++) {
            if (node.getDictionaryObject(COSName.RESOURCES) == dict) {
                return node;
            }
            node = node.getDictionaryObject(COSName.PARENT) instanceof COSDictionary parent ? parent : null;
        }
        return owner;
    }

    private static COSBase holderOf(PDResources resources, COSName category, COSDictionary target, COSBase holder) {
        if (target.getKey() != null) {
            return target;
        }
        if (resources != null && resources.getCOSObject().getDictionaryObject(category) instanceof COSDictionary sub
                && sub.getKey() != null) {
            return sub;
        }
        return holder;
    }

    private void group(COSDictionary dict, COSBase holder) {
        if (dict.getDictionaryObject(COSName.GROUP) instanceof COSDictionary group
                && group.getDictionaryObject(COSName.CS) != null
                && !COSName.DEVICEGRAY.equals(group.getDictionaryObject(COSName.CS))) {
            group.setItem(COSName.CS, COSName.DEVICEGRAY);
            changed.add(holder);
        }
    }

    private void fonts(PDResources resources, COSBase holder) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            PDFont font;
            try {
                font = resources.getFont(name);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (font instanceof PDType3Font type3 && visited.add(type3.getCOSObject())
                    && type3.getCharProcs() != null) {
                PDResources own = type3.getResources() != null ? type3.getResources() : resources;
                COSBase ownHolder = type3.getResources() != null
                        ? holderOf(own, type3.getCOSObject().getKey() != null ? type3.getCOSObject()
                                : holder instanceof COSDictionary d ? d : type3.getCOSObject())
                        : holder;
                if (own != resources) {
                    fonts(own, ownHolder);
                }
                for (COSName glyph : type3.getCharProcs().keySet()) {
                    if (type3.getCharProcs().getDictionaryObject(glyph) instanceof COSStream proc) {
                        content(proc, own, ownHolder);
                    }
                }
            }
        }
    }

    private void contentArray(COSArray array, COSDictionary page, PDResources resources, COSBase holder)
            throws IOException {
        List<COSStream> streams = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSStream stream) {
                streams.add(stream);
            }
        }
        List<byte[]> decoded = new ArrayList<>();
        List<byte[]> rewritten = new ArrayList<>();
        boolean split = false;
        int countedBefore = unconverted;
        quiet = true;
        try {
            for (COSStream stream : streams) {
                byte[] data = Recoder.decoded(stream, budget);
                if (data == null) {
                    unconverted++;
                    return;
                }
                decoded.add(data);
                rewritten.add(visited.contains(stream) ? null : rewrite(data, resources, holder));
                split |= failed;
            }
        } finally {
            quiet = false;
        }
        if (!split) {
            for (int i = 0; i < streams.size(); i++) {
                COSStream stream = streams.get(i);
                if (visited.add(stream) && rewritten.get(i) != null) {
                    overwrite(stream, rewritten.get(i));
                    changed.add(stream);
                }
            }
            dropDefaults(resources, holder);
            return;
        }
        unconverted = countedBefore;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] data : decoded) {
            all.write(data);
            all.write('\n');
        }
        byte[] combined = rewrite(all.toByteArray(), resources, holder);
        if (combined == null) {
            if (!failed) {
                dropDefaults(resources, holder);
            }
            return;
        }
        COSStream single = new COSStream();
        try (OutputStream out = single.createOutputStream()) {
            out.write(combined);
        }
        page.setItem(COSName.CONTENTS, fresh.add(single));
        changed.add(page);
        if (!failed) {
            dropDefaults(resources, holder);
        }
    }

    private static void overwrite(COSStream stream, byte[] data) throws IOException {
        stream.removeItem(COSName.DECODE_PARMS);
        stream.removeItem(DECODED_LENGTH);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(data);
        }
    }

    private void content(COSStream stream, PDResources resources, COSBase holder) throws IOException {
        if (!visited.add(stream)) {
            return;
        }
        byte[] data = Recoder.decoded(stream, budget);
        if (data == null) {
            unconverted++;
            return;
        }
        byte[] rewritten = rewrite(data, resources, holder);
        if (rewritten != null) {
            overwrite(stream, rewritten);
            changed.add(stream);
        }
        if (!failed) {
            dropDefaults(resources, holder);
        }
    }

    private static final class State {

        PDColorSpace fill;

        PDColorSpace stroke;

        boolean fillPattern;

        boolean strokePattern;

        PDColorSpace fillUnder;

        PDColorSpace strokeUnder;

        State copy() {
            State s = new State();
            s.fill = fill;
            s.stroke = stroke;
            s.fillPattern = fillPattern;
            s.strokePattern = strokePattern;
            s.fillUnder = fillUnder;
            s.strokeUnder = strokeUnder;
            return s;
        }
    }

    private byte[] rewrite(byte[] data, PDResources resources, COSBase holder) throws IOException {
        failed = false;
        List<Object> tokens;
        try {
            tokens = new PDFStreamParser(data).parse();
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            fail();
            return null;
        }
        List<Object> out = new ArrayList<>(tokens.size());
        List<COSBase> operands = new ArrayList<>();
        State state = new State();
        ArrayDeque<State> saved = new ArrayDeque<>();
        boolean edited = false;
        for (Object token : tokens) {
            if (token instanceof COSBase operand) {
                operands.add(operand);
                continue;
            }
            if (!(token instanceof Operator op)) {
                continue;
            }
            List<Object> replacement = convert(op, operands, state, saved, resources, holder);
            if (replacement != null) {
                out.addAll(replacement);
                edited = true;
            } else {
                out.addAll(operands);
                out.add(op);
            }
            operands.clear();
        }
        if (!operands.isEmpty()) {
            fail();
            return null;
        }
        if (!edited) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length);
        new ContentStreamWriter(bytes).writeTokens(out);
        return bytes.toByteArray();
    }

    private List<Object> convert(Operator op, List<COSBase> operands, State state, ArrayDeque<State> saved,
            PDResources resources, COSBase holder) throws IOException {
        String name = op.getName();
        switch (name) {
            case "q" -> saved.push(state.copy());
            case "Q" -> {
                if (!saved.isEmpty()) {
                    State back = saved.pop();
                    state.fill = back.fill;
                    state.stroke = back.stroke;
                    state.fillPattern = back.fillPattern;
                    state.strokePattern = back.strokePattern;
                    state.fillUnder = back.fillUnder;
                    state.strokeUnder = back.strokeUnder;
                }
            }
            case "rg", "RG" -> {
                float[] c = numbers(operands, 3);
                setSpace(state, name.equals("rg"), null, false);
                return c == null ? null : gray(fromRgb(c), name.equals("rg") ? "g" : "G");
            }
            case "k", "K" -> {
                float[] c = numbers(operands, 4);
                setSpace(state, name.equals("k"), null, false);
                return c == null ? null : gray(fromCmyk(c), name.equals("k") ? "g" : "G");
            }
            case "g", "G" -> setSpace(state, name.equals("g"), null, false);
            case "cs", "CS" -> {
                return colourSpace(operands, state, name.equals("cs"), resources, holder);
            }
            case "sc", "scn", "SC", "SCN" -> {
                return colour(op, operands, state, name.equals("sc") || name.equals("scn"), resources, holder);
            }
            case "sh" -> {
                if (operands.size() == 1 && operands.get(0) instanceof COSName shading && resources != null) {
                    PDShading found = safeShading(resources, shading);
                    if (found != null) {
                        shading(found, holderOf(resources, COSName.SHADING, found.getCOSObject(), holder));
                    }
                }
            }
            case "gs" -> {
                if (operands.size() == 1 && operands.get(0) instanceof COSName gs && resources != null) {
                    graphicsState(resources, gs, holder);
                }
            }
            case "Do" -> {
                if (operands.size() == 1 && operands.get(0) instanceof COSName xobject && resources != null) {
                    xobject(resources, xobject, holder);
                }
            }
            case "BI" -> {
                return inlineImage(op, resources);
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    private static void setSpace(State state, boolean fill, PDColorSpace space, boolean pattern) {
        if (fill) {
            state.fill = space;
            state.fillPattern = pattern;
            state.fillUnder = null;
        } else {
            state.stroke = space;
            state.strokePattern = pattern;
            state.strokeUnder = null;
        }
    }

    private List<Object> colourSpace(List<COSBase> operands, State state, boolean fill, PDResources resources,
            COSBase holder) throws IOException {
        if (operands.size() != 1 || !(operands.get(0) instanceof COSName name)) {
            return null;
        }
        PDColorSpace space;
        try {
            space = PDColorSpace.create(name, resources);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            unconverted++;
            setSpace(state, fill, null, false);
            return null;
        }
        if (space instanceof PDPattern pattern) {
            setSpace(state, fill, null, true);
            PDColorSpace under = pattern.getUnderlyingColorSpace();
            COSDictionary spaces = resources == null ? null
                    : resources.getCOSObject().getDictionaryObject(COSName.COLORSPACE) instanceof COSDictionary d ? d
                    : null;
            COSBase entry = spaces == null ? null : spaces.getDictionaryObject(name);
            if (entry instanceof COSArray array && grayPatterns.containsKey(array)) {
                under = grayPatterns.get(array);
            } else if (under != null && !(under instanceof PDDeviceGray) && entry instanceof COSArray) {
                COSArray grayPattern = new COSArray();
                grayPattern.add(COSName.PATTERN);
                grayPattern.add(COSName.DEVICEGRAY);
                spaces.setItem(name, grayPattern);
                grayPatterns.put(grayPattern, under);
                changed.add(spaces.getKey() != null ? spaces
                        : resources.getCOSObject().getKey() != null ? resources.getCOSObject() : holder);
            }
            if (fill) {
                state.fillUnder = under;
            } else {
                state.strokeUnder = under;
            }
            return null;
        }
        if (space instanceof PDDeviceGray || space.getNumberOfComponents() == 1
                && "ICCBased".equals(space.getName()) || "CalGray".equals(space.getName())) {
            setSpace(state, fill, null, false);
            return null;
        }
        setSpace(state, fill, space, false);
        List<Object> out = new ArrayList<>();
        out.add(COSName.DEVICEGRAY);
        out.add(Operator.getOperator(fill ? "cs" : "CS"));
        float initial = toGray(space, space.getInitialColor().getComponents());
        if (initial != 0) {
            out.add(new COSFloat(initial));
            out.add(Operator.getOperator(fill ? "sc" : "SC"));
        }
        return out;
    }

    private List<Object> colour(Operator op, List<COSBase> operands, State state, boolean fill,
            PDResources resources, COSBase holder) throws IOException {
        boolean pattern = fill ? state.fillPattern : state.strokePattern;
        if (pattern) {
            if (!operands.isEmpty() && operands.get(operands.size() - 1) instanceof COSName name
                    && resources != null) {
                pattern(resources, name, holder);
                PDColorSpace under = fill ? state.fillUnder : state.strokeUnder;
                if (operands.size() > 1 && !(under instanceof PDDeviceGray)) {
                    float[] components = under == null ? null
                            : numbers(operands.subList(0, operands.size() - 1), under.getNumberOfComponents());
                    if (components == null) {
                        unconverted++;
                        return null;
                    }
                    List<Object> out = new ArrayList<>(3);
                    out.add(new COSFloat(toGray(under, components)));
                    out.add(name);
                    out.add(op);
                    return out;
                }
            }
            return null;
        }
        PDColorSpace space = fill ? state.fill : state.stroke;
        if (space == null) {
            return null;
        }
        float[] components = numbers(operands, space.getNumberOfComponents());
        if (components == null) {
            unconverted++;
            return null;
        }
        return gray(toGray(space, components), op.getName().startsWith("s") ? "sc" : "SC");
    }

    private static float toGray(PDColorSpace space, float[] components) throws IOException {
        if (space instanceof PDDeviceRGB) {
            return fromRgb(components);
        }
        if (space instanceof PDDeviceCMYK) {
            return fromCmyk(components);
        }
        return fromRgb(space.toRGB(components));
    }

    private static float fromRgb(float[] c) {
        return clamp(0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]);
    }

    private static float fromCmyk(float[] c) {
        return clamp(1 - Math.min(1, 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2] + c[3]));
    }

    private static float clamp(float v) {
        return Math.round(Math.max(0, Math.min(1, v)) * 10000) / 10000f;
    }

    private static List<Object> gray(float value, String operator) {
        List<Object> out = new ArrayList<>(2);
        out.add(new COSFloat(value));
        out.add(Operator.getOperator(operator));
        return out;
    }

    private static float[] numbers(List<COSBase> operands, int count) {
        if (operands.size() != count) {
            return null;
        }
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            if (!(operands.get(i) instanceof COSNumber n)) {
                return null;
            }
            values[i] = n.floatValue();
        }
        return values;
    }

    private static float[] numbers(COSArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            if (!(array.getObject(i) instanceof COSNumber n)) {
                return null;
            }
            values[i] = n.floatValue();
        }
        return values;
    }

    private void graphicsState(PDResources resources, COSName name, COSBase holder) throws IOException {
        if (!(resources.getCOSObject().getDictionaryObject(COSName.EXT_G_STATE) instanceof COSDictionary states)
                || !(states.getDictionaryObject(name) instanceof COSDictionary state) || !visited.add(state)) {
            return;
        }
        COSBase owner = holderOf(resources, COSName.EXT_G_STATE, state, holder);
        for (COSName key : new COSName[] {COSName.TR, COSName.TR2}) {
            if (state.getDictionaryObject(key) instanceof COSArray) {
                state.setItem(key, COSName.IDENTITY);
                changed.add(owner);
            }
        }
        if (state.getDictionaryObject(COSName.SMASK) instanceof COSDictionary mask) {
            COSBase maskOwner = mask.getKey() != null ? mask : owner;
            if (grayArray(mask, COSName.BC)) {
                changed.add(maskOwner);
            }
            if (mask.getDictionaryObject(COSName.G) instanceof COSStream group) {
                form(group, resources, maskOwner);
            }
        }
    }

    private void xobject(PDResources resources, COSName name, COSBase holder) throws IOException {
        PDXObject xobject;
        try {
            xobject = resources.getXObject(name);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            return;
        }
        if (xobject instanceof PDImageXObject image) {
            images.add(image.getCOSObject());
        } else if (xobject instanceof PDFormXObject form) {
            form(form.getCOSObject(), resources, holder);
        }
    }

    private void pattern(PDResources resources, COSName name, COSBase holder) throws IOException {
        PDAbstractPattern pattern;
        try {
            pattern = resources.getPattern(name);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            return;
        }
        if (pattern instanceof PDTilingPattern tiling && tiling.getCOSObject() instanceof COSStream stream) {
            PDResources own = tiling.getResources() != null ? tiling.getResources() : resources;
            COSBase ownHolder = tiling.getResources() != null ? holderOf(own, stream) : holder;
            fonts(own, ownHolder);
            content(stream, own, ownHolder);
        } else if (pattern instanceof PDShadingPattern shadingPattern) {
            COSBase patternHolder = holderOf(resources, COSName.PATTERN, shadingPattern.getCOSObject(), holder);
            PDShading found = safeShading(shadingPattern);
            if (found != null) {
                shading(found, found.getCOSObject().getKey() != null ? found.getCOSObject() : patternHolder);
            }
        }
    }

    private static PDShading safeShading(PDResources resources, COSName name) {
        try {
            return resources.getShading(name);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static PDShading safeShading(PDShadingPattern pattern) {
        try {
            return pattern.getShading();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void shading(PDShading shading, COSBase holder) throws IOException {
        if (!visited.add(shading.getCOSObject())) {
            return;
        }
        COSDictionary dict = shading.getCOSObject();
        PDColorSpace space;
        try {
            space = shading.getColorSpace();
        } catch (IOException | RuntimeException e) {
            unconverted++;
            return;
        }
        if (space == null || space instanceof PDDeviceGray || space.getNumberOfComponents() == 1
                && "ICCBased".equals(space.getName())) {
            return;
        }
        PDFunction function;
        try {
            function = shading.getFunction();
        } catch (IOException | RuntimeException e) {
            function = null;
        }
        if (function == null) {
            if (shading.getShadingType() >= 4 && dict instanceof COSStream mesh && mesh(mesh, space)) {
                changed.add(mesh);
            } else {
                unconverted++;
            }
            return;
        }
        int inputs = shading.getShadingType() == 1 ? 2 : 1;
        float[] domain = new float[2 * inputs];
        for (int i = 0; i < inputs; i++) {
            domain[2 * i] = function.getDomainForInput(i).getMin();
            domain[2 * i + 1] = function.getDomainForInput(i).getMax();
        }
        int size = inputs == 1 ? SAMPLES_1D : SAMPLES_2D;
        byte[] samples = new byte[inputs == 1 ? size : size * size];
        float[] input = new float[inputs];
        for (int s = 0; s < samples.length; s++) {
            for (int i = 0; i < inputs; i++) {
                int step = i == 0 ? s % size : s / size;
                input[i] = domain[2 * i] + (domain[2 * i + 1] - domain[2 * i]) * step / (size - 1);
            }
            float gray;
            try {
                gray = toGray(space, shading.evalFunction(input));
            } catch (IOException | RuntimeException e) {
                unconverted++;
                return;
            }
            samples[s] = (byte) Math.round(gray * 255);
        }
        COSStream sampled = new COSStream();
        sampled.setInt(COSName.FUNCTION_TYPE, 0);
        COSArray domainArray = new COSArray();
        domainArray.setFloatArray(domain);
        sampled.setItem(COSName.DOMAIN, domainArray);
        COSArray range = new COSArray();
        range.setFloatArray(new float[] {0, 1});
        sampled.setItem(COSName.RANGE, range);
        COSArray sizes = new COSArray();
        for (int i = 0; i < inputs; i++) {
            sizes.add(COSInteger.get(size));
        }
        sampled.setItem(COSName.SIZE, sizes);
        sampled.setInt(COSName.BITS_PER_SAMPLE, 8);
        try (OutputStream out = sampled.createOutputStream()) {
            out.write(samples);
        }
        if (dict.getDictionaryObject(COSName.BACKGROUND) instanceof COSArray background) {
            float[] values = numbers(background);
            if (values != null && values.length == space.getNumberOfComponents()) {
                COSArray gray = new COSArray();
                gray.add(new COSFloat(toGray(space, values)));
                dict.setItem(COSName.BACKGROUND, gray);
            } else {
                dict.removeItem(COSName.BACKGROUND);
            }
        }
        dict.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        dict.setItem(COSName.FUNCTION, fresh.add(sampled));
        changed.add(dict.getKey() != null ? dict : holder);
    }

    private boolean mesh(COSStream stream, PDColorSpace space) throws IOException {
        int type = stream.getInt(COSName.SHADING_TYPE, 0);
        int coordinateBits = stream.getInt(COSName.BITS_PER_COORDINATE, 0);
        int componentBits = stream.getInt(COSName.BITS_PER_COMPONENT, 0);
        int flagBits = type == 5 ? 0 : stream.getInt(COSName.BITS_PER_FLAG, 0);
        int components = space.getNumberOfComponents();
        if (!(stream.getDictionaryObject(COSName.DECODE) instanceof COSArray decodeArray)
                || decodeArray.size() < 4 + 2 * components || coordinateBits < 1 || coordinateBits > 32
                || componentBits < 1 || componentBits > 16 || type != 5 && flagBits != 2 && flagBits != 4
                && flagBits != 8) {
            return false;
        }
        float[] decode = numbers(decodeArray);
        byte[] data = Recoder.decoded(stream, budget);
        if (decode == null || data == null) {
            return false;
        }
        BitReader in = new BitReader(data);
        BitWriter out = new BitWriter(Math.max(64, data.length / components + 64));
        long max = (1L << componentBits) - 1;
        float[] colour = new float[components];
        while (true) {
            int flag = 0;
            if (flagBits > 0) {
                if (!in.has(flagBits)) {
                    break;
                }
                flag = (int) in.read(flagBits);
                out.bits(flag, flagBits);
            }
            int points;
            int colours;
            if (type == 4 || type == 5) {
                points = 1;
                colours = 1;
            } else {
                boolean first = flag == 0;
                points = type == 6 ? first ? 12 : 8 : first ? 16 : 12;
                colours = first ? 4 : 2;
            }
            long need = 2L * points * coordinateBits + (long) colours * components * componentBits;
            if (!in.has(need)) {
                if (flagBits > 0) {
                    return false;
                }
                break;
            }
            for (int c = 0; c < 2 * points; c++) {
                out.bits(in.read(coordinateBits), coordinateBits);
            }
            for (int k = 0; k < colours; k++) {
                for (int c = 0; c < components; c++) {
                    float lo = decode[4 + 2 * c];
                    float hi = decode[5 + 2 * c];
                    colour[c] = lo + in.read(componentBits) * (hi - lo) / max;
                }
                out.bits(Math.round(toGray(space, colour) * max), componentBits);
            }
            in.align();
            out.align();
        }
        COSArray grayDecode = new COSArray();
        grayDecode.setFloatArray(new float[] {decode[0], decode[1], decode[2], decode[3], 0, 1});
        overwrite(stream, out.bytes());
        stream.setItem(COSName.DECODE, grayDecode);
        stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        if (stream.getDictionaryObject(COSName.BACKGROUND) instanceof COSArray background) {
            float[] values = numbers(background);
            if (values != null && values.length == components) {
                COSArray gray = new COSArray();
                gray.add(new COSFloat(toGray(space, values)));
                stream.setItem(COSName.BACKGROUND, gray);
            } else {
                stream.removeItem(COSName.BACKGROUND);
            }
        }
        return true;
    }

    private static final class BitReader {

        private final byte[] data;

        private long at;

        BitReader(byte[] data) {
            this.data = data;
        }

        boolean has(long bits) {
            return at + bits <= (long) data.length * 8;
        }

        long read(int bits) {
            long value = 0;
            for (int i = 0; i < bits; i++, at++) {
                value = value << 1 | (data[(int) (at >>> 3)] >> 7 - (int) (at & 7) & 1);
            }
            return value;
        }

        void align() {
            at = at + 7 & ~7L;
        }
    }

    private static final class BitWriter {

        private final ByteArrayOutputStream bytes;

        private int pending;

        private int count;

        BitWriter(int capacity) {
            bytes = new ByteArrayOutputStream(capacity);
        }

        void bits(long value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                pending = pending << 1 | (int) (value >>> i & 1);
                if (++count == 8) {
                    bytes.write(pending);
                    pending = 0;
                    count = 0;
                }
            }
        }

        void align() {
            if (count > 0) {
                bits(0, 8 - count);
            }
        }

        byte[] bytes() {
            align();
            return bytes.toByteArray();
        }
    }

    private List<Object> inlineImage(Operator op, PDResources resources) throws IOException {
        COSDictionary params = op.getImageParameters();
        if (params == null || op.getImageData() == null) {
            return null;
        }
        PDInlineImage image;
        try {
            if (!inlineFits(params, op.getImageData())) {
                return keptInColour(resources);
            }
            image = new PDInlineImage(params, op.getImageData(), resources);
            if (image.isStencil() || image.getColorSpace() instanceof PDDeviceGray) {
                return null;
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            return keptInColour(resources);
        }
        BufferedImage rgb;
        try {
            rgb = image.getImage();
        } catch (IOException | RuntimeException e) {
            if (e instanceof InterruptedIOException interrupted) {
                throw interrupted;
            }
            return keptInColour(resources);
        }
        if (rgb == null) {
            return keptInColour(resources);
        }
        int width = rgb.getWidth();
        int height = rgb.getHeight();
        byte[] samples = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = rgb.getRGB(x, y);
                samples[y * width + x] = (byte) (((c >> 16 & 0xFF) * 30 + (c >> 8 & 0xFF) * 59 + (c & 0xFF) * 11)
                        / 100);
            }
        }
        COSDictionary gray = new COSDictionary();
        gray.setInt(COSName.W, width);
        gray.setInt(COSName.H, height);
        gray.setInt(COSName.BPC, 8);
        gray.setItem(COSName.CS, INLINE_GRAY);
        gray.setItem(COSName.F, INLINE_HEX);
        if (params.getBoolean(COSName.I, false) || params.getBoolean(COSName.INTERPOLATE, false)) {
            gray.setBoolean(COSName.I, true);
        }
        Operator replacement = Operator.getOperator("BI");
        replacement.setImageParameters(gray);
        replacement.setImageData(hex(samples));
        List<Object> out = new ArrayList<>(1);
        out.add(replacement);
        return out;
    }

    private static byte[] hex(byte[] samples) {
        byte[] out = new byte[samples.length * 2 + 1];
        for (int i = 0; i < samples.length; i++) {
            out[2 * i] = HEX_DIGITS[samples[i] >> 4 & 0xF];
            out[2 * i + 1] = HEX_DIGITS[samples[i] & 0xF];
        }
        out[out.length - 1] = '>';
        return out;
    }

    private List<Object> keptInColour(PDResources resources) {
        unconverted++;
        keepDefaults(resources);
        return null;
    }

    private static boolean inlineFits(COSDictionary params, byte[] data) throws IOException {
        long width = params.getInt(COSName.W, params.getInt(COSName.WIDTH, -1));
        long height = params.getInt(COSName.H, params.getInt(COSName.HEIGHT, -1));
        if (width <= 0 || height <= 0 || width * height * 4 > Recoder.MAX_DECODED_BYTES) {
            return false;
        }
        List<COSName> filters = inlineFilters(params);
        byte[] bytes = data;
        for (int i = 0; i < filters.size(); i++) {
            COSName name = filters.get(i);
            boolean last = i == filters.size() - 1;
            if (COSName.DCT_DECODE.equals(name) || COSName.DCT_DECODE_ABBREVIATION.equals(name)) {
                Jpeg.Frame frame = Jpeg.frame(bytes);
                return last && frame != null
                        && (long) frame.width() * Math.max(frame.height(), height) * Math.max(1, frame.components())
                                <= Recoder.MAX_DECODED_BYTES;
            }
            if (COSName.CCITTFAX_DECODE.equals(name) || COSName.CCITTFAX_DECODE_ABBREVIATION.equals(name)) {
                COSDictionary parms = inlineParms(params, i);
                long columns = parms == null ? 1728 : parms.getInt(COSName.COLUMNS, 1728);
                long rows = parms == null ? height : parms.getInt(COSName.ROWS, (int) height);
                return last && columns > 0 && (columns + 7) / 8 * Math.max(rows, height) <= Recoder.MAX_DECODED_BYTES;
            }
            if (!Filters.generalized(name)) {
                return false;
            }
            Filter filter = FilterFactory.INSTANCE.getFilter(name);
            Limited out = new Limited(Recoder.MAX_DECODED_BYTES, !last);
            try {
                filter.decode(new ByteArrayInputStream(bytes), out, params, i);
            } catch (Limited.Exceeded e) {
                return false;
            }
            bytes = out.toByteArray();
        }
        return true;
    }

    private static List<COSName> inlineFilters(COSDictionary params) {
        COSBase f = params.getDictionaryObject(COSName.F, COSName.FILTER);
        List<COSName> names = new ArrayList<>();
        if (f instanceof COSName n) {
            names.add(n);
        } else if (f instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                if (a.getObject(i) instanceof COSName n) {
                    names.add(n);
                }
            }
        }
        return names;
    }

    private static COSDictionary inlineParms(COSDictionary params, int index) {
        COSBase p = params.getDictionaryObject(COSName.DP, COSName.DECODE_PARMS);
        if (p instanceof COSDictionary d) {
            return index == 0 ? d : null;
        }
        if (p instanceof COSArray a && index < a.size() && a.getObject(index) instanceof COSDictionary d) {
            return d;
        }
        return null;
    }

    private static final class Limited extends OutputStream {

        static final class Exceeded extends IOException {
            Exceeded() {
                super("decoded past the size limit");
            }
        }

        private final ByteArrayOutputStream bytes;

        private final long limit;

        private long written;

        Limited(long limit, boolean keep) {
            this.limit = limit;
            this.bytes = keep ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            if (written > limit) {
                throw new Exceeded();
            }
            if (bytes != null) {
                bytes.write(b, off, len);
            }
        }

        byte[] toByteArray() {
            return bytes == null ? new byte[0] : bytes.toByteArray();
        }
    }
}
