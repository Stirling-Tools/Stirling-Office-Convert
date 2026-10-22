package stirling.software.officeconvert.topdf;

import java.io.IOException;

record Ebook(EpubBook.Book book, HtmlAssets assets) {

    static final String PAGE = "book.html";

    interface Files {
        byte[] read(String reference) throws IOException;
    }

    static HtmlAssets assets(Files files) {
        return new HtmlAssets() {
            @Override
            public String base() {
                return PAGE;
            }

            @Override
            public byte[] read(String reference) throws IOException {
                return reference == null ? null : files.read(reference);
            }
        };
    }
}
