# Stirling Office Convert

Convert PDFs to editable office documents in pure Java, built on PDFBox. No native dependencies and no external
processes.

Supported output formats:

| Type | Formats |
|---|---|
| Documents | DOCX, ODT, flat ODT (`.fodt`), RTF (`.rtf` / `.doc`), plain text |
| Presentations | PPTX, ODP, PPT (via the `legacy` module) |
| Spreadsheets | XLSX, ODS |

Documents come out as real paragraphs, headings, lists, tables, footnotes, headers and footers rather than
positioned text boxes. Office to PDF is planned.

## Requirements

Java 21 or later.

## Build

```bash
./gradlew build
```

## CLI

```bash
java -jar cli/build/libs/stirling-office-convert-cli.jar in.pdf -o out.docx
java -jar cli/build/libs/stirling-office-convert-cli.jar folder/ -o outdir/ --format docx
```

The output extension picks the format. Options:

| Option | Description |
|---|---|
| `--pages a-b` | Page range to convert |
| `--password p` | Password for protected PDFs |
| `--no-tables` | Skip table detection |
| `--dpi n` | Resolution for vector figures rendered as pictures |
| `--pictures compact\|lossless` | `compact` (default) re-encodes large pictures as JPEG, `lossless` keeps every pixel |
| `--picture-fallback` | Keep a page that cannot be converted as a picture instead of failing |
| `--sheets page\|table\|single` | Spreadsheet layout: a sheet per page, per table, or one sheet |
| `--format ext` | Output format when converting a folder |
| `-q` | Quiet |

## Library

```kotlin
implementation("com.stirling:stirling-office-convert:<version>")
```

```xml
<dependency>
  <groupId>com.stirling</groupId>
  <artifactId>stirling-office-convert</artifactId>
  <version>VERSION</version>
</dependency>
```

`OfficeConvert` is the main entry point. The output file's extension picks the format.

```java
OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.docx"));

OfficeConvert.convert(Path.of("in.pdf"), Path.of("out.pptx"), Settings.defaults()
        .pages(2, 5)
        .password("secret")
        .tables(false)
        .pictureFallback(true)
        .figureDpi(200)
        .sheets(PdfToXlsx.Sheets.TABLE)
        .pictures(Pictures.LOSSLESS)
        .timeout(Duration.ofMinutes(2)));

String text = OfficeConvert.text(Path.of("in.pdf"));

// Convert an already open document into your own stream
try (PDDocument doc = Loader.loadPDF(bytes)) {
    OfficeConvert.convert(doc, out, OfficeConvert.Format.DOCX, Settings.defaults());
}
```

Notes:

- All failures are thrown as `IOException`. A timeout throws `OfficeConvert.TimedOut`. Always set a timeout when
  converting untrusted files.
- The `Path` overload writes to a temp file and moves it into place, so a failed conversion leaves nothing behind.
- Different documents can be converted in parallel, but a single `PDDocument` must not be used from two threads.
- Per-format classes (`PdfToDocx`, `PdfToOdt`, `PdfToRtf`, `PdfToText`, `PdfToPptx`, `PdfToOdp`, `PdfToXlsx`) expose
  extra options not on the facade.
- `.ppt` output needs `com.stirling:stirling-office-convert-legacy` (`PdfToPpt`), which adds Apache POI.
- Certificate-encrypted PDFs need BouncyCastle (`bcpkix`) on the classpath.
- Dependencies: `org.apache.pdfbox:pdfbox` and `commons-logging`.

## Test app

`app` is a small web page for trying conversions locally. It is not published to Maven.

```bash
./gradlew :app:jar
java -jar app/build/libs/stirling-office-convert-app.jar   # http://localhost:8177
```

It also accepts `POST /convert?format=docx` with the PDF as the body. A Docker image is available:

```bash
docker build -t stirling-office-convert -f app/Dockerfile app/build/libs
docker run --rm -p 8080:80 stirling-office-convert
```

## Project layout

| Module | Purpose |
|---|---|
| `core` | The converter library |
| `legacy` | PowerPoint 97-2003 (`.ppt`) writer |
| `cli` | Command line tool |
| `app` | Local test web app |

## Tests

```bash
./gradlew test
```

## License

MIT, see [LICENSE](LICENSE).
