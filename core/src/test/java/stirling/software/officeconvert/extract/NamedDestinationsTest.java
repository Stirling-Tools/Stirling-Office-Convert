package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.junit.jupiter.api.Test;

class NamedDestinationsTest {

    @Test
    void manyLinksToNamedDestinationsResolveQuickly() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument d = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.LETTER);
            PDPage second = new PDPage(PDRectangle.LETTER);
            d.addPage(first);
            d.addPage(second);
            COSArray names = new COSArray();
            COSArray annots = new COSArray();
            for (int i = 0; i < 20_000; i++) {
                String name = String.format("d%07d", i);
                names.add(new COSString(name));
                names.add(fit(second.getCOSObject()));
                COSDictionary a = new COSDictionary();
                a.setItem(COSName.TYPE, COSName.ANNOT);
                a.setItem(COSName.SUBTYPE, COSName.LINK);
                a.setItem(COSName.RECT, new PDRectangle(i % 600, 10, 1, 1).getCOSArray());
                a.setItem(COSName.DEST, new COSString(name));
                annots.add(a);
            }
            first.getCOSObject().setItem(COSName.ANNOTS, annots);
            COSDictionary tree = new COSDictionary();
            tree.setItem(COSName.NAMES, names);
            COSDictionary namesDict = new COSDictionary();
            namesDict.setItem(COSName.DESTS, tree);
            d.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, namesDict);
            d.save(bytes);
        }
        List<PageData.Link> links = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (PDDocument d = Loader.loadPDF(bytes.toByteArray())) {
                new PageReader(d).read(0, 0, true, p -> links.addAll(p.links()));
            }
        });
        assertEquals(PageReader.MAX_LINKS, links.size());
        assertTrue(links.stream().allMatch(l -> l.targetPage() == 1));
    }

    @Test
    void resolvesTheSameDestinationsAsPdfBox() throws Exception {
        Random random = new Random(11);
        for (int round = 0; round < 400; round++) {
            try (PDDocument d = new PDDocument()) {
                PDPage page = new PDPage();
                d.addPage(page);
                COSDictionary catalog = d.getDocumentCatalog().getCOSObject();
                List<COSDictionary> made = new ArrayList<>();
                if (random.nextInt(5) > 0) {
                    COSDictionary namesDict = new COSDictionary();
                    namesDict.setItem(COSName.DESTS, node(random, page, 0, made));
                    catalog.setItem(COSName.NAMES, namesDict);
                }
                if (random.nextBoolean()) {
                    COSDictionary dests = new COSDictionary();
                    for (int i = 0; i < 4; i++) {
                        dests.setItem(COSName.getPDFName(name(random)), value(random, page));
                    }
                    catalog.setItem(COSName.DESTS, dests);
                }
                if (!made.isEmpty() && random.nextInt(10) == 0) {
                    COSArray kids = new COSArray();
                    kids.add(made.getFirst());
                    made.get(random.nextInt(made.size())).setItem(COSName.KIDS, kids);
                    made.get(random.nextInt(made.size())).removeItem(COSName.NAMES);
                }
                NamedDestinations fast = new NamedDestinations(d);
                for (int q = 0; q < 30; q++) {
                    PDNamedDestination named = new PDNamedDestination(name(random));
                    Object expected = outcome(() -> d.getDocumentCatalog().findNamedDestinationPage(named));
                    Object actual = outcome(() -> fast.find(named.getNamedDestination()));
                    if (expected instanceof PDPageDestination e) {
                        assertTrue(actual instanceof PDPageDestination, "round " + round + " " + actual);
                        assertSame(e.getCOSObject(), ((PDPageDestination) actual).getCOSObject(), "round " + round);
                    } else {
                        assertEquals(expected, actual, "round " + round + " " + named.getNamedDestination());
                    }
                }
            }
        }
    }

    private interface Lookup {
        PDPageDestination get() throws Exception;
    }

    private static Object outcome(Lookup lookup) {
        try {
            PDPageDestination d = lookup.get();
            return d == null ? "none" : d;
        } catch (Exception | StackOverflowError e) {
            return "failed";
        }
    }

    private static COSDictionary node(Random random, PDPage page, int depth, List<COSDictionary> made) {
        COSDictionary node = new COSDictionary();
        made.add(node);
        if (depth < 3 && random.nextInt(3) > 0) {
            COSArray kids = new COSArray();
            for (int i = random.nextInt(4); i > 0; i--) {
                kids.add(random.nextInt(12) == 0 ? COSInteger.get(1) : node(random, page, depth + 1, made));
            }
            node.setItem(COSName.KIDS, kids);
        }
        if (depth == 3 || random.nextInt(3) == 0) {
            COSArray names = new COSArray();
            for (int i = random.nextInt(6); i > 0; i--) {
                names.add(random.nextInt(25) == 0 ? COSInteger.get(2) : new COSString(name(random)));
                names.add(value(random, page));
            }
            node.setItem(COSName.NAMES, names);
        }
        if (depth > 0 && random.nextInt(4) > 0) {
            COSArray limits = new COSArray();
            limits.add(new COSString(name(random)));
            if (random.nextInt(6) > 0) {
                limits.add(new COSString(name(random)));
            }
            node.setItem(COSName.LIMITS, limits);
        }
        return node;
    }

    private static COSBase value(Random random, PDPage page) {
        return switch (random.nextInt(5)) {
            case 0 -> new COSString("x");
            case 1 -> {
                COSDictionary d = new COSDictionary();
                d.setItem(COSName.D, fit(page.getCOSObject()));
                yield d;
            }
            default -> fit(page.getCOSObject());
        };
    }

    private static String name(Random random) {
        return String.valueOf((char) ('a' + random.nextInt(8)));
    }

    private static COSArray fit(COSDictionary page) {
        COSArray dest = new COSArray();
        dest.add(page);
        dest.add(COSName.getPDFName("Fit"));
        return dest;
    }
}
