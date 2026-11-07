#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${REPO_URL:?REPO_URL is required}"
REPO_URL="${REPO_URL%/}"
VERSION="${VERSION:?VERSION is required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULES=(stirling-office-convert stirling-office-convert-legacy stirling-office-convert-topdf
         stirling-office-convert-pdfa stirling-office-convert-lineart stirling-office-convert-optimize)

missing=0
for m in "${MODULES[@]}"; do
  base="$REPO_URL/com/stirling/$m/$VERSION/$m-$VERSION"
  for suffix in .pom .pom.asc .module .jar .jar.asc -sources.jar -javadoc.jar; do
    if curl -fsS -r 0-0 -o /dev/null --retry 3 "$base$suffix" 2>/dev/null; then
      echo "  [ok] $m-$VERSION$suffix"
    else
      echo "  [MISSING] $base$suffix"
      missing=$((missing + 1))
    fi
  done
done
[ "$missing" -eq 0 ] || { echo "ERROR: $missing staged files are missing"; exit 1; }

INSECURE=false
case "$REPO_URL" in http://*) INSECURE=true ;; esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src/main/java"
cat > "$WORK/settings.gradle.kts" <<'EOF'
rootProject.name = "staged-smoke"
EOF
cat > "$WORK/build.gradle.kts" <<EOF
plugins { application }
repositories {
    exclusiveContent {
        forRepository {
            maven {
                url = uri("$REPO_URL")
                isAllowInsecureProtocol = $INSECURE
            }
        }
        filter { includeGroup("com.stirling") }
    }
    mavenCentral()
}
dependencies {
    implementation("com.stirling:stirling-office-convert:$VERSION")
    implementation("com.stirling:stirling-office-convert-topdf:$VERSION")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
application { mainClass.set("Smoke") }
EOF
cat > "$WORK/src/main/java/Smoke.java" <<'EOF'
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.topdf.OfficeToPdf;

public class Smoke {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("smoke");
        Path txt = Files.writeString(dir.resolve("in.txt"), "Staged release smoke test\nSecond line\n");
        Path pdf = dir.resolve("in.pdf");
        OfficeToPdf.convert(txt, pdf);
        Path docx = dir.resolve("out.docx");
        OfficeConvert.convert(pdf, docx);
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            String body = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes());
            if (!body.contains("Staged release smoke test")) throw new IllegalStateException("text lost in round trip");
        }
        System.out.println("smoke ok: txt -> pdf (" + Files.size(pdf) + " bytes) -> docx (" + Files.size(docx) + " bytes)");
    }
}
EOF
"$ROOT/gradlew" -p "$WORK" run --no-daemon --quiet
