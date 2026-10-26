# Lossless PDF optimization

The `optimize` module (`stirling-office-convert-optimize`) rewrites a PDF smaller without changing how any page
draws. It is the plain-Java replacement for the qpdf passes Stirling-PDF runs, and it doubles as a repair pass: the
input is read by PDFBox's lenient parser, which rebuilds a damaged cross-reference table, and written out fresh.

```java
PdfOptimizer.Result r = PdfOptimizer.optimize(Path.of("in.pdf"), Path.of("out.pdf"),
        PdfOptimizer.Options.defaults().effort(PdfOptimizer.Effort.DEFAULT));
```

```
java -jar cli/build/libs/stirling-office-convert-cli.jar in.pdf --optimize [--effort fast|default|max] [-o out.pdf]
```

`--optimize` writes `in.optimized.pdf` unless `-o` names the output, and takes `--password`, `--timeout`,
`--overwrite` and `-q`. An output that would not be smaller is a copy of the input
(`Options.alwaysRewrite(true)` writes the rewrite regardless, which is what a repair wants).

## What Stirling-PDF asks qpdf for

| Caller | qpdf command | What it does |
|---|---|---|
| Compress, every level | `--decode-level=generalized --recompress-flate --compression-level=N --compress-streams=y --stream-data=compress --object-streams=generate` | Decodes Flate, LZW, ASCII85, ASCIIHex and RunLength streams, re-deflates every stream with zlib at level N (3, 5, 7 or 9 for levels 1, 2, 3-5 and 6-9), deflates uncompressed streams, packs non-stream objects into object streams and writes a cross-reference stream |
| Compress, levels 1-3 | `--preserve-unreferenced` | Keeps objects nothing references; from level 4 they are dropped |
| Compress, levels 5-9 | `--optimize-images --jpeg-quality=78..34` | Lossy: re-encodes 8-bit Flate or raw images of at least 128x128 as JPEG when that is smaller |
| Compress, levels 8-9 | `QPDF_ZOPFLI=silent` | Uses zopfli when qpdf was built with it; the Stirling-PDF image's qpdf 12 is not, so it changes nothing |
| Compress, on request | `--normalize-content=y`, `--linearize` | Content stream newlines become LF; web-optimized output with hint tables |
| Compress | | Keeps qpdf's output only when it is smaller than its input |
| Repair, after Ghostscript | `--qdf --object-streams=disable` | Rewrites what qpdf could parse in QDF form: every stream it can decode left uncompressed, content normalized, no object streams; exit 3 (recovered with warnings) counts as repaired |
| PDF/A, before Ghostscript or LibreOffice | `--normalize-content=y --object-streams=preserve` | A parse and rewrite pass with content newlines normalized |
| PDF/A-1 | `--remove-unreferenced-resources=yes --normalize-content=y --object-streams=preserve` | Also drops each page's resources its content does not use |

The lossless part of every row maps to `PdfOptimizer`; `--optimize-images` is lossy and stays with Stirling-PDF's own
image recompression, and `--linearize` has no equivalent.

## What the optimizer does

- Writes only what the trailer reaches (the catalog, the document information and everything they reference).
- Packs every object that can be packed into object streams and writes a cross-reference stream with a PNG predictor.
  A PDF/A-1 document keeps a classic cross-reference table, since PDF/A-1 forbids object streams.
- Decodes Flate, LZW, ASCII85, ASCIIHex and RunLength, strictly: a stream that does not decode cleanly is copied as
  it is. JPEG, JPEG 2000, JBIG2 and CCITT data is never decoded to pixels.
- Tries several encodings of each stream and keeps the smallest, including the stream as it came when nothing beats
  it: zlib, an optimal-parse deflater with block splitting (`default` and `max`), and for Flate images the PNG
  predictors. Every deflated stream is inflated again and compared before it is used.
- Re-codes the Huffman tables of baseline JPEGs (`default` and `max`); the coefficients are untouched and checked to
  decode the same.
- Rewrites content streams with minimal whitespace and the shortest spelling of each number and string; a content
  stream holding an inline image, or anything it does not fully parse, is left alone.
- Merges identical objects, such as a font, image or ICC profile embedded more than once, and writes an object
  referenced once in the place that references it. Pages, annotations, fields, structure elements and other objects
  whose identity matters are never merged.
- Drops resources no content stream uses (default colour spaces stay), the padding of XMP packets, and private
  application data (`/PieceInfo`), which no viewer draws, unless `keepPrivateData`.
- Keeps the document ID, version (raised to 1.5 for object streams), metadata, tagging, forms, annotations and
  encryption: an encrypted PDF is written with the same key, permissions and security handler. A signed PDF is
  rewritten with a warning that its signatures no longer validate.

Work is bounded like the rest of the library: a timeout (5 minutes by default), a decoded size cap per stream, a cap
on objects, and the shared memory admission.
