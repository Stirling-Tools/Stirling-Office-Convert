# Limits

Every limit the converter applies to a document, what it costs when a document reaches it, and which ones a host
can change. The converter is meant to run on files from people you do not trust, inside a long-lived JVM, so each
limit exists to stop one document from taking the memory, CPU or disk that other conversions need.

Limits marked **fixed** are compile-time constants. Everything else can be set by the host.

## Choosing limits for a server

| Server | Heap (`-Xmx`) | Suggested settings |
|---|---|---|
| Small private | 1 GB | `maxConcurrent=1` or `2`, `refuseOversize=true`, timeout 3 minutes |
| Shared or public | 2 GB | default `maxConcurrent` (auto), `refuseOversize=true`, `maxWaitSeconds` 60–120, timeout 3 minutes, `maxPages` 300–1000 |
| Large private or batch | 4 GB and up | defaults (callers queue until room is free or their timeout passes) |

Below about 512 MB of heap most real documents still convert, but a single large spreadsheet or scanned PDF can
use the whole budget. Watch `Admission.jvm().status()` (below) and restart the process when every slot is held by a
stuck worker.

## Memory admission

One JVM-wide gate, `stirling.software.officeconvert.memory.Admission`, decides when a conversion may start, in
both directions (PDF to Office, Office to PDF, PDF to PDF/A). Each conversion first estimates the memory it needs;
it starts once that estimate fits in the budget and a slot is free.

| Setting | Default | System property | API |
|---|---|---|---|
| Memory budget | 60 % of the max heap (10–100) | `stirling.officeconvert.memoryBudgetPercent` | `Admission.Limits.memoryBudgetPercent` |
| Conversions at once | auto: two per processor, at most one per 192 MB of budget, at least two | `stirling.officeconvert.maxConcurrent` | `Admission.Limits.maxConcurrent` (0 = auto) |
| Longest wait for room | 0 (no limit of its own; the conversion's timeout still applies) | `stirling.officeconvert.maxWaitSeconds` | `Admission.Limits.maxWait` |
| Refuse a document larger than the whole budget | false (it runs alone instead) | `stirling.officeconvert.refuseOversize` | `Admission.Limits.refuseOversize` |
| Retry a conversion stopped for memory while others ran | false | `stirling.officeconvert.readmitStopped` | `Admission.Limits.readmitStopped` |

Set them before the first conversion with system properties, or at any time with
`Admission.configure(new Admission.Limits(percent, maxConcurrent, maxWait, refuseOversize, readmitStopped))`;
conversions already running finish under the old limits.

The gate queues by default: with no `maxWaitSeconds` a conversion waits until room is free, bounded only by its own
timeout. A conversion with timeout 0, or one started through an entry point that takes a loaded `PDDocument` and no
timeout (such as `PdfToDocx.convert(PDDocument, OutputStream, Options)`), can therefore wait forever behind stuck
workers; set `maxWaitSeconds` on a shared server.

With `readmitStopped`, a path-to-path PDF to Office or line-art conversion that was stopped for memory while others
ran is tried again once one of them ends, at most three attempts per call. A call through `OfficeConvert` retries
both around its timeout and inside it, so each outer attempt gets the full timeout again. The waits between one
call's attempts share one allowance of `maxWaitSeconds` (300 s at most, and 300 s when it is 0), so the worst case is
three timeouts plus that allowance.

What the caller sees:

| Case | Result |
|---|---|
| No room within the longest wait | `Admission.Busy`: "The converter is busy; try again later" |
| Estimate larger than the budget with `refuseOversize` | `Admission.TooLarge`: "The document needs more memory to convert than the converter may use" |
| Heap over 90 % (97 % when converting alone) or container memory over 90 % (95 % when converting alone) | the largest conversion stops with "The document needs more memory to convert than is available" |
| Heap over 75 % or container memory over 85 % | no new conversion starts until it drops (**fixed** thresholds) |

Only one conversion is stopped at a time. A stopped conversion whose worker was abandoned after its timeout, or one
that has not ended 5 s (**fixed**) after it was stopped, no longer keeps the gate from stopping the next largest.

The heap figure is the one after the last collection. Before stopping a conversion, and before turning away a new one
with nothing running, the gate calls `System.gc()`, at most once a second, with its lock released so other
conversions can start and end meanwhile; while the heap stays over 90 % that is a full collection a second. With
`-XX:+DisableExplicitGC` the call does nothing and the gate decides on the figure from the JVM's own last
collection, so it may stop a conversion later or, if the heap was since freed, sooner than needed.

`Busy` and `TooLarge` extend `InterruptedIOException`; check for them before treating that type as a cancellation.
Time spent waiting for room counts towards the conversion's timeout.

A conversion nested inside another on the same thread (an EPUB cover drawn during PDF to EPUB, for example) is
charged to the outer conversion and never waits for it.

### Status and stuck workers

`Admission.jvm().status()` returns `Status(budgetBytes, inUseBytes, running, slots, stuck, heapUsed)`.

When a conversion passes its timeout, the caller waits for the worker to stop (5 s for PDF to Office and Office to
PDF, 10 s for PDF/A, **fixed**) and then returns `TimedOut`. A worker still running after that is counted in
`stuck`; it keeps its memory and its slot until it really ends. When `stuck` equals `slots`, nothing new can start:
restart the process. The test app does this itself with `EXIT_WHEN_STUCK`.

## Timeouts and per-conversion options

| Limit | Default | How to set | When reached |
|---|---|---|---|
| PDF to Office timeout | 5 minutes (0 = none) | `OfficeConvert.Settings.timeout`, CLI `--timeout` | `OfficeConvert.TimedOut` |
| Office to PDF timeout | 5 minutes (0 = none) | `OfficeToPdf.Options.timeout`, CLI `--timeout` | `OfficeToPdf.TimedOut` |
| PDF to PDF/A timeout | 5 minutes (0 = none) | `PdfToPdfA.Options.timeout`, CLI `--timeout` | `PdfToPdfA.TimedOut` |
| Office to PDF pages | 10,000 (0 = all) | `OfficeToPdf.Options.maxPages`, CLI `--max-pages` | stops there with a warning; a DOCX stops reading its body once page breaks reach it |
| Office to PDF scratch space | 2 GB (0 = none) | `OfficeToPdf.Options.maxScratchBytes` | `OfficeToPdf.OutputTooLarge` |
| PDF to PDF/A pages | 10,000 (0 = all) | `PdfToPdfA.Options.maxPages`, CLI `--max-pages` | refused |
| PDF to Office pages | all | `OfficeConvert.Settings` page range, CLI `--pages` | only the range is converted |
| Office input size | 512 MB | **fixed** | refused before reading |
| PDF/A input size | 1 GB | **fixed** | refused before parsing |
| Worker thread stack | 8 MB | **fixed** | deeper nesting fails with "The document nests too deeply to convert" (PDF/A: "The PDF nests its objects too deeply to convert"; page drawing: "The page is nested too deeply to draw") |
| Damaged Office parts left out | 8 | **fixed** | one more damaged part fails the conversion |

The CLI applies `--timeout` to every direction, including PDF to `.ppt`.

## PDF input (PDF to Office, PDF/A, covers, previews)

Every page render goes through one guarded renderer, so these limits hold for page drawing in every output format.

| Limit | Value | When reached |
|---|---|---|
| Content operators per page | 5,000,000 | the rest of the page is skipped with a warning; PDF/A flattening fails |
| Form XObjects per page | 50,000 | further forms are skipped; PDF/A flattening fails |
| Interrupt and memory check | every 1,024 operators | the conversion stops at the timeout or under memory pressure |
| Annotations drawn per page | 10,000 | the rest are not drawn; PDF/A removes them with a warning |
| Type3 glyph nesting | 8 | deeper glyphs are not drawn |
| Decoded image size | 256 MB, counting the raster PDFBox builds | the image is drawn at the smallest subsampling (up to 1/16) that fits; beyond that it is left out |
| JPEG 2000 samples | 2^26, judged from the codestream the decoder reads; decoding needs about 6 bytes of working memory per sample (some 400 MB at the limit), outside the 256 MB decoded-image figure | the image is left out |
| Decoded stream size | 256 MB | a content stream is emptied, an image refused |
| Path segments in PDF/A flattening | 100,000 | flattening fails |
| Transparency group nesting in PDF/A flattening | 64 | flattening fails |
| PDF/A flattening raster | 40 M pixels | the resolution is lowered |
| Photo split pages / pixels | 1,000 / 100 M | refused |

Stack overflows and out-of-memory errors while drawing are reported as ordinary conversion failures; an EPUB or
AZW3 cover that fails is left out and the book still converts.

The memory estimate for a PDF scans at most 2,000 pages, 20,000 objects and 64 MB of content, and forms nested 8
deep; anything it cannot see or read is charged as a large picture. Besides a fixed cost per page and the largest
picture, content past the first 16 KB of a page (its streams, forms, patterns and appearances) is charged for the
glyphs, lines and words built from it: 96 bytes per byte on each of the first 60 pages, held for document statistics, and another 176 per byte
(at most 64 MB) on the page with the most, while it is laid out. These figures were measured on dense text; vector
pages cost less, so for them the estimate errs high. A form is read once but charged on every page that draws it.

## Office input (Office to PDF)

| Limit | Value | When reached |
|---|---|---|
| Package entries | 10,000 | refused |
| Size of one part / all parts | 512 MB / 1 GB | refused (`OfficeZip.Oversized`) when the package is opened |
| Inflation ratio per part | 100:1 | a part with no compressed size is refused; other denser parts are read up to 48 MB in all, then left out as damaged |
| Inflation ratio of the whole package | 100:1, checked only past 48 MB uncompressed | refused |
| XML element depth | 1,000 | parse error; the part is left out |
| XML elements in any XML part POI reads | 250,000 | the part is left out with a warning; for the main part or its relationships the conversion fails |
| XML elements in a DOCX header, footer, notes, numbering, chart or theme part | 500,000 | the part is left out with a warning naming it |
| XML elements in other parts read whole (workbooks, spreadsheet drawings, Visio pages and others) | memory budget ÷ 512 bytes, between 500,000 and 4,000,000 | the part is left out with a warning naming it; for the main part the conversion fails |
| XML elements in a DOCX body | one conversion slot's share of the memory budget ÷ 384 bytes, between 250,000 and 8,000,000 | the rest of the body is skipped with "The document is too long" and the result is marked partial; a final section break still keeps up to 10,000 |
| XML elements in spreadsheet styles | 500,000 | default styles are used, with a warning |
| Custom shape path commands | 100,000 | the path is cut short |
| Rotated table cells measured inside each other | 8 | deeper cells use the row's set height |
| Picture decode / largest picture | 32 M / 100 M pixels | the picture is downscaled or refused |
| RTF group nesting | 2,048 | deeper groups are dropped |
| HTML nesting | 200 | deeper content is kept, flattened to plain text |
| CSS rules / style sheet characters (inline `<style>` included) | 200,000 / 8 MB | the rest is dropped with a warning |
| CSS selector match work (weighted by selector complexity) | 10 M | later elements keep default styles, with a warning |
| CSS `@import` depth / at-rule nesting | 8 / 16 | ignored deeper |
| SVG elements / depth / pixels | 20,000 / 48 / 4 M | further elements and deeper ones are skipped; larger pictures are scaled down |
| ODS repeated rows and columns | 8–192 MB of work | truncated with a warning |
| Spreadsheet cells / merged ranges | 4 M / 100,000 | truncated |
| Spreadsheet data read before admission | 256 MB | the rest is charged conservatively |

The library changes this JVM-wide state, which also affects other code in the same JVM:

- Apache POI's shared XML options (`POIXMLTypeLoader.DEFAULT_XML_OPTIONS`), on the first Office to PDF conversion:
  DOCTYPE declarations are refused and the library's own element-counting `XMLReader` is installed, so every other
  POI user in the JVM parses through it from then on. A reader the host set first is never replaced;
  Office conversions then fail with `IllegalStateException` rather than parse without the hardened reader.
- Apache POI's zip file count (`ZipSecureFile.setMaxFileCount`), raised above its default only when a package needs
  more entries and never lowered again until the JVM restarts.
- The ImageIO registry: the library's JPEG 2000 reader is registered and ordered ahead of any other on every
  conversion (see the [usage guide](usage.md) for `-Dstirling.officeconvert.jpxReader=imageio`).
- `System.gc()` calls from the memory gate (above), and, in a Linux container that is 80 % full, a call to the
  `DiagnosticCommand` MBean's `systemTrimNativeHeap` at most once a second, as `jcmd <pid> System.trim_native_heap`.

`PoiXml.raiseProcessLimits()` raises the JDK XML limits POI needs, but only when the host calls it and only for
properties the host has not set. Call it only if you use Office to PDF.

## Test app

The local test app has its own request limits (`MAX_UPLOAD_MB`, `MAX_PAGES`, `CONVERT_TIMEOUT_SECONDS`,
`MAX_CONCURRENT`, `MAX_QUEUED`, `QUEUE_WAIT_SECONDS`, `MAX_PER_CLIENT`, `EXIT_WHEN_STUCK` and others); see the
[usage guide](usage.md#local-test-app).
