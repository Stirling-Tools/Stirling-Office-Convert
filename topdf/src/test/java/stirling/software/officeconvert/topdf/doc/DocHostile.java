package stirling.software.officeconvert.topdf.doc;

import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class DocHostile {

    private DocHostile() {}

    public static byte[] build(NoNetwork net) {
        return new WordFixture().para("Hostile Word 97 document")
                .para("\u0013 INCLUDEPICTURE \"" + net.url("include.png") + "\" \\d \u0014CACHED-PICTURE\u0015")
                .para("\u0013 HYPERLINK \"" + net.url("link") + "\" \u0014link\u0015")
                .para("\u0013 INCLUDETEXT \"" + net.uncPath("x.doc") + "\" \u0014CACHED-TEXT\u0015").build();
    }
}
