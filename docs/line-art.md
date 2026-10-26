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

- The image stream is rewritten in place, so every reference to it (pages, nested forms, tiling patterns,
  annotation appearances) draws the new picture, at the same place and size.
- `/SMask`, `/Interpolate`, `/Intent`, `/OC`, `/Metadata` and `/StructParent` stay. A colour-key `/Mask` becomes a
  1-bit stencil `/Mask` of the same pixels, so keyed-out areas stay transparent.
- Images used as another image's mask, `/ImageMask` stencils, external (`/F`) streams, images with a pre-blended
  (`/Matte`) soft mask and images over `maxPixels` (50 million) are left alone. Inline images are not touched.
- An image that is already 1-bit is only replaced when the new stream is smaller, except with `EDGES`, which redraws it.
- `inkColour(true)` keeps the ink's colour: a 2-entry `/Indexed` palette of the mean ink colour and white, the
  separation itself for a spot-colour image, and plain black when the ink is grey.
- The codec is chosen per image. `SMALLEST` tries all three and keeps the shortest:
  - `JBIG2`: generic region, arithmetic coded with typical prediction (`JBIG2Decode`, PDF 1.4; the document version
    is raised to 1.4 when needed). Usually the smallest.
  - `CCITT_G4`: what Stirling-PDF writes today.
  - `FLATE`: packed rows, with the PNG Up predictor when that is shorter. Wins on halftones and tiny pictures.

## Benchmark against ImageMagick

Run on 2026-10-26 in Docker (`--cpus=2`): ImageMagick 7.1.2-32 from the `stirling-pdf:3.2.0` image with Stirling-PDF's
exact command, against the module on Java 25. 167 pictures: 160 from 58 PDFs of our corpora (scanned pages from
the olmOCR set and `corpus/real`, decoded the way Stirling-PDF does) and 7 synthetic ones made in code with a known
clean ink mask: clean text, a line drawing, a JPEG'd scan with shading, a very noisy scan, blue ink on cream paper,
a 6 px halftone and a red stamp over text. Sizes are the bytes of the image stream in the PDF; ImageMagick's is the
`CCITTFaxDecode` stream Stirling-PDF embeds.

### Matching ImageMagick (`EDGES`)

| Set | Pixel agreement | Identical pictures | ImageMagick G4 | Our G4 | Our JBIG2 | Smallest | ImageMagick time | Java time |
|---|---|---|---|---|---|---|---|---|
| Synthetic, levels 1, 2 and 3 | 100% | 21 of 21 | 7.00 MB | 6.88 MB | 3.42 MB | 3.39 MB | 7.8 s | 2.3 s |
| Corpus, opaque, level 1 | 100% | 139 of 139 | 2.16 MB | 2.02 MB | 1.10 MB | 1.09 MB | 17.2 s | 3.1 s |
| Corpus, with a soft mask, level 1 | 89.1% | 5 of 21 | 0.34 MB | 0.18 MB | 0.09 MB | 0.09 MB | 1.3 s | 0.2 s |

Java time is binarising plus encoding; ImageMagick time is the `magick` process alone, before Stirling-PDF also
writes the PNG (3.1 s for the corpus) and reads the TIFF back. The pictures with a soft mask differ on purpose:
ImageMagick weights its convolution by alpha, and the edge kernel's weights sum to zero, so on any picture with an
alpha channel (even a fully opaque one) every faint edge saturates to black, and Stirling-PDF then drops the mask.
The module takes edges of the colour and keeps the mask.

### Thresholding methods

Synthetic set, error against the clean ink mask, and bytes with the smallest codec:

| Picture | `EDGES` 1 | `SAUVOLA` | `OTSU` | `FIXED` 50 | Stirling-PDF G4 | `SAUVOLA`, smallest |
|---|---|---|---|---|---|---|
| Clean text | 17.4% | 0.28% | 0.28% | 0.92% | 102,432 | 26,982 |
| Line drawing | 16.3% | 0.72% | 1.24% | 1.80% | 54,907 | 12,110 |
| JPEG'd scan, shaded | 25.5% | 0.44% | 0.58% | 0.55% | 570,976 | 35,038 |
| Very noisy scan | 40.4% | 2.02% | 11.7% | 8.18% | 990,998 | 89,388 |
| Blue ink on cream | 17.7% | 0.25% | 0.16% | 1.33% | 146,469 | 31,638 |
| Halftone | 85.1% | 11.9% | 4.92% | 4.20% | 102,135 | 7,908 |
| Red stamp over text | 15.8% | 0.32% | 0.08% | 0.88% | 26,138 | 7,979 |
| All | 27.2% | 1.35% | 2.90% | 2.63% | 1.99 MB | 0.21 MB |

On the 160 corpus pictures `SAUVOLA` with the smallest codec writes 0.66 MB (Stirling-PDF today: 2.50 MB) in 2.2 s.
`SAUVOLA` is the default because it is the only method that holds up on shaded and noisy scans; `OTSU` is better on
evenly lit pictures, halftones and coloured ink. The mean absolute difference from the source grey is 14.3 for
`SAUVOLA`, 10.4 for `OTSU` and 33.2 for `EDGES` on the opaque corpus pictures.

### Codecs

JBIG2 is the smallest on everything but halftones, where Flate with the Up predictor wins (9.8 KB against 16.9 KB
JBIG2 and 99.8 KB G4). Our G4 is 4% to 6% smaller in total than ImageMagick's for the same pixels. Every stream decodes to
the expected bitmap in PDFBox and, rendered at 1:1, in Ghostscript with no differing pixel; Poppler smooths 1-bit
pictures when it renders, identically for all three codecs.
