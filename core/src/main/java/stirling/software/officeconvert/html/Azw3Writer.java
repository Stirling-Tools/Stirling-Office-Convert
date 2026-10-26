package stirling.software.officeconvert.html;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;

public final class Azw3Writer implements DocSink, Closeable {

    private final OutputStream target;
    private final Path epub;
    private final OutputStream epubOut;
    private final EpubWriter book;

    public Azw3Writer(OutputStream target, Pictures pictures) throws IOException {
        this(target, pictures, null);
    }

    public Azw3Writer(OutputStream target, Pictures pictures, byte[] cover) throws IOException {
        this.target = target;
        this.epub = Files.createTempFile("office-convert-", ".epub");
        try {
            this.epubOut = Files.newOutputStream(epub);
        } catch (IOException e) {
            Files.deleteIfExists(epub);
            throw e;
        }
        this.book = new EpubWriter(epubOut, pictures, cover);
    }

    @Override
    public Picture.MediaRef media(byte[] bytes, String ext, int pixelWidth, int pixelHeight, Object key)
            throws IOException {
        return book.media(bytes, ext, pixelWidth, pixelHeight, key);
    }

    @Override
    public Picture.MediaRef media(Object key) {
        return book.media(key);
    }

    @Override
    public void begin(StyleSheet styles, HeaderFooterSet running) {
        book.begin(styles, running);
    }

    @Override
    public void block(Block block) throws IOException {
        book.block(block);
    }

    @Override
    public void footnote(int id, List<Paragraph> paragraphs) {
        book.footnote(id, paragraphs);
    }

    @Override
    public void finish(Section last, HeaderFooterSet running, Numbering numbering, StyleSheet styles, String title,
            String author) throws IOException {
        book.finish(last, running, numbering, styles, title, author);
        book.close();
        epubOut.close();
        Kf8Writer.write(epub, target);
        target.flush();
    }

    @Override
    public void close() throws IOException {
        try (book; epubOut) {
            Files.deleteIfExists(epub);
        }
    }
}
