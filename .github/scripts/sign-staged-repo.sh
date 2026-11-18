#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="${1:?usage: sign-staged-repo.sh <maven repo dir>}"
: "${GPG_SIGNING_KEY:?GPG_SIGNING_KEY is required}"
: "${GPG_SIGNING_PASSWORD?GPG_SIGNING_PASSWORD must be set, even if empty}"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
chmod 700 "$GNUPGHOME"
trap 'gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf "$GNUPGHOME" "${files:-}"' EXIT

printf '%s' "$GPG_SIGNING_KEY" | gpg --batch --quiet --pinentry-mode loopback \
  --passphrase-fd 3 --import 3<<<"$GPG_SIGNING_PASSWORD"

checksums() {
  local file="$1"
  for algo in md5 sha1 sha256 sha512; do
    openssl dgst "-$algo" -r "$file" | cut -d' ' -f1 | tr -d '\n' > "$file.$algo"
  done
}

existing="$(find "$REPO_DIR" -type f -name '*.asc' | head -n 1)"
if [ -n "$existing" ]; then
  echo "ERROR: $REPO_DIR already holds signatures ($existing)"; exit 1
fi

files="$(mktemp)"
find "$REPO_DIR" -type f \
  ! -name 'maven-metadata.xml*' \
  ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
  -print0 | sort -z > "$files"

signed=0
while IFS= read -r -d '' file; do
  gpg --batch --quiet --yes --pinentry-mode loopback --passphrase-fd 3 \
    --armor --detach-sign --output "$file.asc" "$file" 3<<<"$GPG_SIGNING_PASSWORD"
  gpg --batch --quiet --verify "$file.asc" "$file" 2>/dev/null
  checksums "$file.asc"
  signed=$((signed + 1))
done < "$files"

[ "$signed" -gt 0 ] || { echo "ERROR: nothing to sign in $REPO_DIR"; exit 1; }
echo "Signed $signed files in $REPO_DIR"
