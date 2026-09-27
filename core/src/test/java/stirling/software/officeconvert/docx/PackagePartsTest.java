package stirling.software.officeconvert.docx;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackagePartsTest {

    @Test
    void subPartGetsTheRelationshipsItUses() {
        PartContext ctx = new PartContext();
        String used = ctx.linkRel("https://example.com/a");
        ctx.linkRel("https://example.com/unused");
        String rels = PackageParts.subPartRels("<w:hyperlink r:id=\"" + used + "\"/>", ctx);
        assertTrue(rels.contains("Id=\"" + used + "\"") && rels.contains("https://example.com/a"));
        assertTrue(!rels.contains("unused"), "only what the part refers to");
    }

    @Test
    void subPartWithoutReferencesHasNoRelationships() {
        assertNull(PackageParts.subPartRels("<w:p/>", new PartContext()));
    }
}
