# Lossless PDF optimization

The `optimize` module rewrites a PDF smaller without changing how any page draws. It is the plain-Java replacement
for the qpdf passes Stirling-PDF runs, and it doubles as a repair pass: the input is read by PDFBox's lenient parser,
which rebuilds a damaged cross-reference table, and written out fresh.

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

## What the optimizer does

- Writes only what the trailer reaches (the catalog, the document information and everything they reference).
- Packs every object that can be packed into object streams and writes a cross-reference stream with a PNG predictor.
- Decodes Flate, LZW, ASCII85, ASCIIHex and RunLength, strictly: a stream that does not decode cleanly is copied as
  it is. JPEG, JPEG 2000, JBIG2 and CCITT data is never decoded.
- Tries several encodings of each stream (zlib strategies, and for Flate images the PNG predictors) and keeps the
  smallest, including the stream as it came when nothing beats it.
- Rewrites content streams with minimal whitespace and the shortest spelling of each number and string; a content
  stream holding an inline image, or anything it does not fully parse, is left alone.
- Merges identical objects, such as a font, image or ICC profile embedded more than once, and writes an object
  referenced once in the place that references it.
- Drops resources no content stream uses, and private application data (`/PieceInfo`), which no viewer draws.
- Keeps the document ID, version (raised to 1.5 for object streams), metadata, tagging, forms and annotations.

Encrypted PDFs are refused: optimizing one would have to remove its encryption.
