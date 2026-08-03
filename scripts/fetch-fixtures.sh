#!/usr/bin/env bash
# Fetch the only two test fixtures that are NOT committed to this repository.
#
# Everything else under src/test/resources/ is committed (Correction C26) -- fixtures used to live in
# a sibling oracle directory that CI could not see, which made every parity assertion skip silently.
#
# These two are the exception: ewinglab.org publishes no redistribution terms, so we reference them by
# URL instead of republishing them. Their GOLDENS are committed, which is not an inconsistency -- those
# hold per-scan counts, hex sums and SHA-256 digests, never a peak array.
#
# NOTE (C26): a test that needs these must FAIL when they are absent, not skip. Fixtures.require()
# does exactly that, and prints this script's name in the failure message. The previous
# skip-when-absent behaviour is the regression C26 removed -- do not reintroduce it here by making a
# failed fetch exit 0.
set -uo pipefail

cd "$(dirname "$0")/.."
DEST=src/test/resources/data
mkdir -p "$DEST"
STATUS=0

# fetch <url> <dest> <expected-bytes> <note>
fetch() {
  local url=$1 dest=$2 want=$3 note=$4

  if [ -f "$dest" ]; then
    local have; have=$(stat -f '%z' "$dest" 2>/dev/null || stat -c '%s' "$dest")
    if [ "$have" = "$want" ]; then
      printf '  %-26s PRESENT (%s bytes)\n' "$(basename "$dest")" "$have"; return 0
    fi
    printf '  %-26s re-fetching (size %s != expected %s)\n' "$(basename "$dest")" "$have" "$want"
  fi

  # ewinglab.org has been observed to stall mid-transfer (once at 35 KB of 3.7 MB) and then serve
  # the remainder at 15 MB/s. It honours range requests (HTTP 206), so retry with resume rather
  # than restarting: -C - resumes, --speed-time/--speed-limit abort a stalled connection quickly
  # instead of burning the whole timeout.
  printf '  %-26s fetching... ' "$(basename "$dest")"
  local ok=0
  for attempt in 1 2 3 4; do
    if curl -fsSL -C - --max-time 240 --speed-time 30 --speed-limit 1000 -o "$dest" "$url"; then
      ok=1; break
    fi
    # A completed file makes curl exit 33/416 on a further resume attempt; treat exact size as success.
    local now; now=$(stat -f '%z' "$dest" 2>/dev/null || stat -c '%s' "$dest" 2>/dev/null || echo 0)
    [ "$now" = "$want" ] && { ok=1; break; }
    printf 'retry%s ' "$attempt"
  done
  if [ "$ok" -ne 1 ]; then
    echo "FAIL (download error after 4 attempts)"; STATUS=1; return 0
  fi

  local have; have=$(stat -f '%z' "$dest" 2>/dev/null || stat -c '%s' "$dest")
  if [ "$have" = "$want" ]; then
    printf 'OK (%s bytes) -- %s\n' "$have" "$note"
  else
    # Fail loudly: a silently-changed upstream file would otherwise surface much later as an
    # inexplicable golden mismatch.
    printf 'FAIL (got %s bytes, expected %s) -- upstream file may have changed\n' "$have" "$want"
    STATUS=1
  fi
}

echo "Fetching the two uncommitted Ewing-lab fixtures into $DEST/"
echo

E=https://www.ewinglab.org/omicsanalysistutorial/data
fetch "$E/DP00570_F02.mzxml" "$DEST/DP00570_F02.mzxml" 3761778 \
      "916 scans (229 MS1 / 687 MS2), mzXML 2.0, precision=32, byteOrder=network, NESTED, ZERO precursorScanNum"
fetch "$E/DP00570_F02.mgf"   "$DEST/DP00570_F02.mgf"   2196881 \
      "same experiment as the mzXML above -- Step 12 cross-format Pair B"

echo
if [ "$STATUS" -eq 0 ]; then
  echo "Both fixtures present. Ms1ScanDocumentOrderIT can now run -- it is the only test that can"
  echo "distinguish document-order ms1scan from precursorScanNum resolution."
else
  echo "One or more fetches failed. Tests needing these fixtures will FAIL, not skip (C26)." >&2
fi
exit "$STATUS"
