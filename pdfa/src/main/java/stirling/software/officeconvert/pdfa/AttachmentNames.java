package stirling.software.officeconvert.pdfa;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;

final class AttachmentNames {

    private AttachmentNames() {}

    static void retain(PDDocument doc, List<COSDictionary> retained) throws IOException {
        if (retained.isEmpty()) {
            return;
        }
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        if (names == null) {
            names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        }
        Map<String, PDComplexFileSpecification> files = new TreeMap<>();
        Set<COSDictionary> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(names.getCOSObject().getCOSDictionary(COSName.EMBEDDED_FILES), files, listed);
        boolean changed = false;
        for (COSDictionary spec : retained) {
            if (listed.add(spec)) {
                put(files, EmbeddedFiles.fileName(spec), spec);
                changed = true;
            }
        }
        if (changed) {
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(files);
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
        }
    }

    private static void collect(COSDictionary root, Map<String, PDComplexFileSpecification> files,
            Set<COSDictionary> listed) throws IOException {
        if (root == null) {
            return;
        }
        Visits visits = new Visits();
        ArrayDeque<COSDictionary> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            COSDictionary node = pending.removeFirst();
            if (!visits.first(node)) {
                continue;
            }
            COSArray entries = ContentGraph.array(node.getDictionaryObject(COSName.NAMES));
            if (entries != null) {
                for (int i = 0; i + 1 < entries.size(); i += 2) {
                    COSDictionary spec = ContentGraph.dict(entries.getObject(i + 1));
                    if (entries.getObject(i) instanceof COSString key && spec != null) {
                        put(files, key.getString(), spec);
                        listed.add(spec);
                    }
                }
            }
            COSArray kids = ContentGraph.array(node.getDictionaryObject(COSName.KIDS));
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    COSDictionary child = ContentGraph.dict(kids.getObject(i));
                    if (child != null) {
                        pending.add(child);
                    }
                }
            }
        }
    }

    private static void put(Map<String, PDComplexFileSpecification> files, String name, COSDictionary spec) {
        String unique = name;
        for (int suffix = 2; files.containsKey(unique); suffix++) {
            if (files.get(unique).getCOSObject() == spec) {
                return;
            }
            unique = name + " (" + suffix + ")";
        }
        files.put(unique, new PDComplexFileSpecification(spec));
    }
}
