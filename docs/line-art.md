# Line art: bilevel images

Stirling-PDF's compress tool has a "line art" option that turns every picture in a PDF into a 1-bit black and white
image. Today it shells out to ImageMagick. This page records exactly what that step does, so the pure Java
replacement in the `lineart` module can match it.

## What Stirling-PDF does today

Sources: `CompressController.applyLineArtConversion`, `OptimizePdfRequest` and
`ImageMagickLineArtConversionService` (Stirling-PDF 3.2).

Request fields: `lineArt` (default false), `lineArtThreshold` (0 to 100, default 55) and `lineArtEdgeLevel`
(1 to 3, default 1). The step runs before Ghostscript and the JPEG pass, and needs the ImageMagick group enabled.

### Which images

- Every image XObject named in a page's `/Resources /XObject`, and every image one level down inside a form XObject
  named there. Deeper forms, patterns, annotation appearances, Type 3 glyphs and inline images are not touched.
- No filter on size, colour space, bit depth or image masks: an `/ImageMask` stencil or an image that is already
  1-bit is converted like any other.
- Images are grouped by a content hash, so each distinct image is converted once.

### The command

Each image is decoded with PDFBox `PDImageXObject.getImage()` (masks applied, so a masked image has alpha), written
to PNG and run through

```
magick in.png -colorspace Gray -edge <edgeLevel> -negate -normalize -type Bilevel -threshold <t>% -compress Group4 out.tiff
```

Measured on ImageMagick 7.1.2-32 Q16-HDRI, the binary in the `stirling-pdf:3.2.0` image:

| Step | Effect |
|---|---|
| `-colorspace Gray` | `0.212656 R + 0.715158 G + 0.072186 B` on the gamma-encoded sRGB values, kept as floats |
| `-edge r` | Convolution with a `(2r+1)` square kernel: `-1` everywhere, `(2r+1)^2 - 1` at the centre. Edge pixels repeat; HDRI keeps negative and over-range results |
| `-negate` | `65535 - v` |
| `-normalize` | Contrast stretch on a 65536-bin histogram: the darkest 2% go to black, the lightest 1% to white, linear between |
| `-type Bilevel` | Thresholds at 50% straight away |
| `-threshold t%` | No effect: the image is already bilevel, so `lineArtThreshold` changes nothing |
| `-compress Group4` | 1-bit TIFF, CCITT Group 4, min-is-black |

The result is an outline drawing, not a binarised copy: a solid black area becomes a white area with a black edge,
and strokes thicker than the kernel turn hollow. `lineArtEdgeLevel` 2 and 3 widen the kernel to 5 and 7 pixels.

### How it goes back into the PDF

`PDImageXObject.createFromByteArray` reads the TIFF into a new `CCITTFaxDecode` image (1 bit, `DeviceGray`), and the
resource name on the page (or in the form) is pointed at the new image. Consequences:

- Placement is kept, because the content stream and resource name are unchanged.
- `/SMask`, `/Mask`, `/Decode`, `/Interpolate`, `/Intent` and `/OC` of the original are dropped, so transparency is
  lost and a masked picture becomes an opaque rectangle. Stencil masks are converted as ordinary images and stop
  painting in their fill colour.
- Ink colour is lost: every image becomes black on white.
- A reference the search does not reach (a deeper form, a pattern, an annotation) still draws the original.

## The `lineart` module

`stirling-office-convert-lineart` does the same job in plain Java on PDFBox, with no external process.

```java
LineArt.Result r = LineArt.convert(Path.of("in.pdf"), Path.of("out.pdf"), LineArt.Options.defaults());
LineArt.convert(pdDocument, LineArt.Options.imageMagickEdges(1));   // in place; the caller saves
Bitmap bits = LineArt.binarize(bufferedImage, LineArt.Options.defaults().method(LineArt.Method.OTSU));
```

```
java -jar cli/build/libs/stirling-office-convert-cli.jar scan.pdf --line-art sauvola
java -jar cli/build/libs/stirling-office-convert-cli.jar scan.pdf --line-art edges --edge-level 2 -o out.pdf
```

### Methods

| Method | Threshold | Options |
|---|---|---|
| `SAUVOLA` (default) | Per pixel, from the mean and spread of its neighbourhood: copes with shading, stains and uneven light | `window` (0 picks about 1/48 of the shorter side, 15 to 127 px), `k` (0.5; higher keeps less ink and fewer specks) |
| `OTSU` | One level for the whole picture, chosen to split it best into two tones | |
| `FIXED` | `threshold` percent of white (50) | `threshold` |
| `EDGES` | Stirling-PDF's outline drawing, pixel for pixel | `edgeLevel` 1 to 3, `threshold` (50 is what ImageMagick applies) |

`Options.imageMagickEdges(level)` is `EDGES` at 50%, the same picture Stirling-PDF's ImageMagick step makes for an
opaque image. Grey is `0.212656 R + 0.715158 G + 0.072186 B` in every method.

### What goes back into the PDF

- The image stream is rewritten in place, so every reference to it (pages, forms nested to any depth, tiling
  patterns, annotation appearances, Type 3 glyphs) draws the new picture, at the same place and size.
- Inline images (`BI` ... `EI`) in page, form, pattern, appearance and Type 3 glyph content are rewritten in place
  with `CCITTFaxDecode` or `FlateDecode` (inline images cannot use `JBIG2Decode`); every other byte of the content
  stream is kept.
- `/SMask`, `/Interpolate`, `/Intent`, `/OC`, `/Metadata` and `/StructParent` stay. A colour-key `/Mask` becomes a
  1-bit stencil `/Mask` of the same pixels, so keyed-out areas stay transparent. A soft mask with `/Matte` (colour
  pre-blended with the matte) is undone before thresholding, and the image gets its own copy of the soft mask
  without `/Matte`; the original stays for any other image sharing it.
- Images used as another image's mask, `/ImageMask` stencils (XObject or inline), external (`/F`) streams and images
  over `maxPixels` (one billion) are left alone.
- An image that is already 1-bit is only replaced when the new stream is smaller, except with `EDGES`, which redraws it.
- `inkColour(true)` keeps the ink's colour: a 2-entry `/Indexed` palette of the mean ink colour and white, the
  separation itself for a spot-colour image, and plain black when the ink is grey.
- The codec is chosen per image. `SMALLEST` tries all three and keeps the shortest:
  - `JBIG2`: lossless, arithmetic coded (`JBIG2Decode`, PDF 1.4; the document version is raised to 1.4 when needed).
    Each picture is coded twice and the shorter kept: as one generic region with typical prediction, and as a
    symbol dictionary of every connected shape that occurs at least twice (exact copies only), a text region placing
    them, and a generic region for the rest. Usually the smallest; the symbol form wins on clean text.
  - `CCITT_G4`: what Stirling-PDF writes today.
  - `FLATE`: packed rows, with the PNG Up predictor when that is shorter. Wins on halftones and tiny pictures.

### Memory

Pictures larger than the band budget (half the memory admission budget at 28 bytes a pixel, between 1 and 4
million pixels) are decoded in bands of rows: Flate, LZW, run-length and ASCII streams are inflated once, as a
stream, and each band is decoded by PDFBox on its own; JPEG and JPEG 2000 are decoded by region. Thresholding only
ever holds a band plus the rows its window needs, and the output is a 1-bit bitmap, so a 20000 x 20000 picture
converts in a 1.2 GB heap. Each picture takes a ticket from the JVM's memory admission for its estimate first.

## Benchmark against ImageMagick

Run on 2026-10-26 in Docker (`--cpus=2`, `--memory=1500m`, shared with other jobs, so times are relative): ImageMagick
7.1.2-32 from the `stirling-pdf:3.2.0` image with Stirling-PDF's exact command, against the module on Java 25. 167
pictures: 160 from 58 PDFs of our corpora (scanned pages from the olmOCR set and `corpus/real`, decoded the way
Stirling-PDF does) and 7 synthetic ones made in code with a known clean ink mask: clean text, a line drawing, a
JPEG'd scan with shading, a very noisy scan, blue ink on cream paper, a 6 px halftone and a red stamp over text.
Sizes are the bytes of the image stream in the PDF; ImageMagick's is the `CCITTFaxDecode` stream Stirling-PDF embeds.

### Matching ImageMagick (`EDGES`)

| Set | Pixel agreement | Identical pictures | ImageMagick G4 | Our G4 | Our JBIG2 | Smallest | ImageMagick time | Java time |
|---|---|---|---|---|---|---|---|---|
| Synthetic, levels 1, 2 and 3 | 100% | 21 of 21 | 7.00 MB | 6.88 MB | 3.34 MB | 3.32 MB | 9.9 s | 5.1 s |
| Corpus, opaque, level 1 | 100% | 139 of 139 | 2.16 MB | 2.02 MB | 1.08 MB | 1.08 MB | 22.9 s | 5.8 s |
| Corpus, with a soft mask, level 1 | 89.1% | 5 of 21 | 0.34 MB | 0.18 MB | 0.09 MB | 0.09 MB | 1.6 s | 0.4 s |

Java time is binarising plus G4 encoding; ImageMagick time is the `magick` process alone, before Stirling-PDF also
writes the PNG and reads the TIFF back. The pictures with a soft mask differ on purpose: ImageMagick weights its
convolution by alpha, and the edge kernel's weights sum to zero, so on any picture with an alpha channel (even a
fully opaque one) every faint edge saturates to black, and Stirling-PDF then drops the mask. The module takes edges
of the colour and keeps the mask.

### Thresholding methods

Synthetic set, error against the clean ink mask, and bytes with the smallest codec:

| Picture | `EDGES` 1 | `SAUVOLA` | `OTSU` | `FIXED` 50 | Stirling-PDF G4 | `SAUVOLA`, smallest |
|---|---|---|---|---|---|---|
| Clean text | 17.4% | 0.28% | 0.28% | 0.92% | 102,432 | 7,293 |
| Line drawing | 16.3% | 0.72% | 1.24% | 1.80% | 54,907 | 12,110 |
| JPEG'd scan, shaded | 25.5% | 0.44% | 0.58% | 0.55% | 570,976 | 34,209 |
| Very noisy scan | 40.4% | 2.02% | 11.7% | 8.18% | 990,998 | 89,388 |
| Blue ink on cream | 17.7% | 0.25% | 0.16% | 1.33% | 146,469 | 21,542 |
| Halftone | 85.1% | 11.9% | 4.92% | 4.20% | 102,135 | 7,908 |
| Red stamp over text | 15.8% | 0.32% | 0.08% | 0.88% | 26,138 | 5,042 |
| All | 27.2% | 1.35% | 2.90% | 2.63% | 1.99 MB | 0.18 MB |

On the 160 corpus pictures `SAUVOLA` with the smallest codec writes 0.63 MB (Stirling-PDF today: 2.50 MB).
`SAUVOLA` is the default because it is the only method that holds up on shaded and noisy scans; `OTSU` is better on
evenly lit pictures, halftones and coloured ink.

### Codecs

JBIG2 is the smallest on everything but halftones, where Flate with the Up predictor wins (9.8 KB against 15.8 KB
JBIG2 and 99.8 KB G4). The symbol dictionary makes clean text 3.7 times smaller than one generic region (7.3 KB
against 27.0 KB) and the corpus 4.5% smaller; on noisy scans, where no two shapes are exactly alike, the generic
region is kept. Our G4 is 4% to 6% smaller in total than ImageMagick's for the same pixels. Every stream, symbol
coded or not, decodes to the expected bitmap in PDFBox and, rendered at 1:1, in Ghostscript with no differing pixel;
Poppler smooths 1-bit pictures when it renders, and its rendering is byte for byte the same for all three codecs.

### Whole PDFs, time and peak heap

`LineArt.convert(Path, Path)` in Docker with `-Xmx1200m`; peak heap is the sum of the heap pools' peaks.

| Input | Before banding (round 1) | Now |
|---|---|---|
| 188 olmOCR pages, `SAUVOLA` | 24.2 s, 830 MB | 19.4 s, 1106 MB |
| 188 olmOCR pages, `EDGES` | 27.9 s, 974 MB | 27.2 s, 1006 MB |
| 6000 x 8000 grey Flate, `SAUVOLA` / `EDGES` | 4.5 s / 10.4 s | 4.1 s / 9.5 s |
| 20000 x 20000 grey Flate, `SAUVOLA` / `EDGES` | out of memory | 36.5 s / 88.0 s, 956 / 1022 MB |
| 20000 x 20000 RGB Flate, `SAUVOLA` / `EDGES` | out of memory | 42.7 s / 92.3 s, 1083 / 1129 MB |

The olmOCR runs now also do symbol coding. Peak pool sums include garbage not yet collected; the huge pictures stay
inside a 1.2 GB heap because only a band is ever decoded.

### pdf.js test corpus

All 1010 PDFs of the pdf.js test suite, in Docker with `--network=none`, converted with the defaults and rendered by
PDFBox before and after: 993 convert and render, 7 cannot be opened by PDFBox at all, 10 need a password. 5453
images seen, 1681 converted; the only per-image warnings are three broken streams (a bad JPEG, a bad JPEG 2000 and a
bad ASCII85 content stream), which are left as they were. The 14 encrypted files first failed to save; the file API now removes the encryption, as `PdfToPdfA` does, and says so in a warning. With `EDGES` (level 1) the same 993 convert and render, with 1734 images converted.


## Round 3 memory follow-up

The rolling greyscale buffer now moves overlapping rows within the existing allocation, bands are capped at
4 Mi pixels, and each temporary PDFBox band stream is closed after decoding. These changes preserve the image
bytes: all 188 olmOCR outputs and the three generated large-image outputs match the previous branch head after
normalizing document metadata. The existing band-versus-whole-image tests cover every threshold method and
ink colour, including bands narrower than the threshold window.

Measured on 2026-10-27 in Docker with `--network=none --cpus=2 --memory=1500m` and `-Xmx1200m`:

| Input | Previous head, Sauvola / edges | New, Sauvola / edges |
|---|---|---|
| 188 olmOCR PDFs | 925 / 548 MiB | 704 / 376 MiB |
| 6000 x 8000 grey | 455 / 868 MiB | 286 / 197 MiB |
| 20000 x 20000 grey | 986 / 1042 MiB | 926 / 921 MiB |
| 20000 x 20000 RGB | 1130 / 1067 MiB | 966 / 949 MiB |

All cases completed without conversion failures. These comparable numbers use the original harness, which
requests collection before each group and sums the separate heap pools' peaks. That sum can combine peaks
that occurred at different times, and includes uncollected garbage.

A separate run with no explicit collection sampled simultaneous heap usage every millisecond. Its first
188-file Sauvola group peaked at 697 MiB heap and 858 MiB process resident high-water memory, meeting the
original 830 MiB heap target. Edges immediately afterwards in the same JVM peaked at 948 MiB heap and
1286 MiB resident high-water memory. The target is therefore a measured result for the default Sauvola group,
not a guaranteed process-memory bound or a bound across successive methods in one JVM.
The same run also completed every large-image case; sampled heap for the 400 MP grey image was 539 / 629 MiB
and for the 400 MP RGB image 725 / 835 MiB (Sauvola / edges). Resident high-water memory is cumulative for
the process, so it cannot be interpreted as a separate peak for each subsequent group.
