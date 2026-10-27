# HTML to PDF validation

The HTML renderer lays a page out as a Word document and draws it with the DOCX renderer. Current coverage
includes font-aware word and line-height estimates, wrapping flex rows and grid gutters, generated content and
scoped counters, English automatic hyphenation, running strings, and first/left/right page margin boxes.

URL input and linked resources in local HTML have separate explicit access controls. Tests cover local HTTPS
with a trusted test authority, SNI, wrong-host, expired and untrusted certificates, bounded host lookup, redirects
and private-address policy. No external internet fetch was needed for these checks. TrueType and WOFF 1 containing
TrueType web fonts are supported. WOFF 2 and CFF/PostScript-outline web fonts use an installed fallback with a
warning; those formats have not been added by this work.

## WeasyPrint comparison

The 2026-10-27 inventory contains 47 tailored HTML inputs and 47 candidate outputs. WeasyPrint produced 44
reference PDFs. The CSS-bomb, deep-nesting and many-span cases have no usable WeasyPrint reference, so they are
excluded from parity scores. Reference and candidate PDFs were measured in offline containers using the same
installed fonts. These 44 cases differ from the earlier 20-case suite, so their means are not a before/after score.

| Set | Cases | Word recall | Word order | Dark-pixel IoU | Coarse greyscale similarity | Same page count |
|---|---:|---:|---:|---:|---:|---:|
| All references | 44 | 0.9737 | 0.9565 | 0.3820 | 0.9345 | 39/44 |
| Website layouts | 19 | 0.9955 | 0.9710 | 0.2221 | 0.9005 | 16/19 |
| Email layouts | 16 | 0.9992 | 0.9992 | 0.4953 | 0.9914 | 16/16 |
| Markdown layouts | 4 | 0.9857 | 0.9122 | 0.3692 | 0.9903 | 4/4 |
| Hostile cases with references | 5 | 0.8000 | 0.8000 | 0.6375 | 0.8372 | 3/5 |

The form specimen is an AcroForm in both outputs. These measurements show strong text retention with partial
layout parity. Bootstrap and Tailwind specimens take two pages instead of three, the newsletter takes one instead
of two, and the huge-table case takes 99 instead of 104. The long-word case takes 33 instead of 10 pages and has
zero word-token recall with different line breaking. A separate PDFBox extraction confirms that both
PDFs retain the source's 200,000 `x` and 20,000 `y` characters. That diagnostic does not replace the original token
metric or establish visual equivalence.

A naturally sized table can still split a narrow header when non-breaking spaces surround nested styled text.
Table sizing uses normalized plain cell text, while rendered runs retain their spacing. The ebook regression set
exposes this with a wrapped “Location” label; the original main already wraps a “Chair” label in the same table.
All characters remain present. This remains a sizing limitation.


## Combined Office regression corpus

The frozen integration candidate was compared with a fresh main build on 5,488 unique inputs in an offline
Linux container, using Java 25, two conversion threads, a 1,100 MiB heap, a 1,500 MiB container limit and two CPUs.
The candidate includes changes from the other conversion branches, so Office-wide differences cannot all be
attributed to HTML. The saved baseline jar matched the fresh main build's 1,915 project classes.

Long-lived JVM runs reached the container admission limit in both builds. The raw candidate run completed
4,189 conversions and the fresh main run completed 3,452; neither is a clean regression result. All 1,737 inputs
refused for memory in either run were retried for both builds in fresh, matching batches of 25. No larger heap or
harness-forced garbage collection was used. Every retry batch completed without another memory refusal.
The final corpus combines these paired retries with the unaffected original results. This establishes per-input
conversion coverage under bounded batches; it does not establish reliability for an uninterrupted 5,488-file JVM.

| Final result | Main | Combined candidate |
|---|---:|---:|
| Successful conversions | 5,027 | 5,031 |
| Source, unsupported-format or conversion errors | 461 | 457 |
| Missing results or expected PDFs | 0 | 0 |
| Memory refusals after paired retries | 0 | 0 |

Four XLS inputs newly convert; no main success becomes a conversion error. Of 5,027 successful pairs, 141 have
identical normalized PDF hashes, 4,636 have the same render and extracted text, 246 have pixel changes, three have
page-count changes, and one has only an extracted-text difference. The pixel comparison uses 72 dpi, a channel
difference threshold of eight and the first 60 pages of each PDF. Text extraction covers all pages. These bounds
mean the result is not an all-page visual guarantee for longer documents. The manifest/results/PDF union audit
found no duplicates, missing sources, malformed results, empty successful PDFs or unreferenced PDFs.

The three page-count changes are two copies of `forcepoint100.html` (nine to twelve pages) and `39512.xls`
(one to two). Across all 180 successful HTML pairs, 176 retain exactly the main character multiset in a separate
MuPDF extraction. The remaining four are two copies each of malformed fuzz inputs `ofz-msan-1.html` and
`ofz39850-1.html`; their mean-inclusive HTML character recall is 0.9971, with a minimum of 0.7624. PDFBox retains
all stored non-whitespace characters for both builds in those two inputs, but visual inspection confirms existing
one-page overflow and more tail content outside the visible page after the font-metric changes. This remains a
visible-content limitation. Stored PDF text alone does not establish that text is visible.

A separate all-page text/bounds/link audit of the 180 HTML pairs and 26 other changed-text Office pairs found no
new out-of-page words or invalid internal links. This geometric test cannot detect every form of clipping.
The `forcepoint100.html` layout still has an extremely narrow side column. Representative DOCX/PPTX chart changes
show the shared chart renderer's plot fill and 3D perspective changes; the WIPO chart's missing series and a long
PPTX legend remain pre-existing limits. A full CSS layout engine and general browser geometry parity remain
outside the implemented coverage.
