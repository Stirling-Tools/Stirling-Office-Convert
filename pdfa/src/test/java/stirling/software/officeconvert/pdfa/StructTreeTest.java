package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;

class StructTreeTest {

    @Test
    void rebuiltParentTreeKeysOwnersInStructureOrder() throws Exception {
        for (int run = 0; run < 24; run++) {
            List<COSDictionary> pages = new ArrayList<>();
            COSArray kids = new COSArray();
            for (int i = 0; i < 16; i++) {
                COSDictionary page = new COSDictionary();
                page.setItem(COSName.TYPE, COSName.PAGE);
                pages.add(page);
                COSDictionary element = new COSDictionary();
                element.setItem(COSName.TYPE, COSName.STRUCT_ELEM);
                element.setItem(COSName.S, COSName.P);
                element.setItem(COSName.PG, page);
                element.setItem(COSName.K, COSInteger.get(0));
                kids.add(element);
            }
            COSDictionary root = new COSDictionary();
            root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
            root.setItem(COSName.K, kids);
            StructTree.read(root).rebuildParentTree();
            for (int i = 0; i < pages.size(); i++) {
                assertEquals(i, pages.get(i).getInt(COSName.STRUCT_PARENTS));
            }
        }
    }
}
