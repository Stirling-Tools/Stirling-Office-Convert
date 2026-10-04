package stirling.software.officeconvert.topdf.doc6;

import stirling.software.officeconvert.topdf.testing.NoNetwork;

public final class Word6Hostile {

    private Word6Hostile() {}

    public static byte[] build(NoNetwork net) {
        return new Word6Fixture().text("\u0013 INCLUDEPICTURE \"" + net.url("include.png") + "\" \u0014CACHED\u0015",
                "\u0013 HYPERLINK \"" + net.url("link") + "\" \u0014link\u0015").build();
    }
}
