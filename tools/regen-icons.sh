#!/usr/bin/env bash
#
# Regenerate the legacy launcher PNGs and the 512×512 Play Store icon
# from the canonical favicon SVG in the ws-nostr-publish-go dashboard.
#
# Adaptive icons (mipmap-anydpi-v26) are already vector and don't need
# regen — only the per-density raster fallbacks and the Play Store
# listing asset are produced here.
#
# Requires:
#   - rsvg-convert (brew install librsvg)
#
# Usage:
#   ./tools/regen-icons.sh                       # uses default SVG path
#   ./tools/regen-icons.sh path/to/favicon.svg   # override

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SVG="$REPO_ROOT/../btclock-ws-nostr-publish-go/web/static/favicon.svg"
SRC_SVG="${1:-$DEFAULT_SVG}"

if [ ! -f "$SRC_SVG" ]; then
    echo "Source SVG not found: $SRC_SVG" >&2
    exit 1
fi

if ! command -v rsvg-convert >/dev/null 2>&1; then
    echo "rsvg-convert is required (brew install librsvg)." >&2
    exit 1
fi

# Round-variant SVG: same panels, but the background is a full circle
# instead of a rounded rectangle so launchers that pull the round PNG
# fallback (and don't apply their own circular mask) get a properly
# clipped icon.
ROUND_SVG="$(mktemp -t btclock-round.XXXXXX.svg)"
trap 'rm -f "$ROUND_SVG"' EXIT
cat > "$ROUND_SVG" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <circle cx="16" cy="16" r="16" fill="#0a0a0a"/>
  <rect x="4" y="8" width="6" height="16" rx="1.2" fill="#dadbde" stroke="#f1c64a" stroke-width="1"/>
  <rect x="13" y="8" width="6" height="16" rx="1.2" fill="#dadbde" stroke="#f1c64a" stroke-width="1"/>
  <rect x="22" y="8" width="6" height="16" rx="1.2" fill="#dadbde" stroke="#f1c64a" stroke-width="1"/>
</svg>
SVG

# Density buckets → target px (1× = 48 dp launcher icon).
declare -a DENSITIES=("mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192")

cd "$REPO_ROOT"

for entry in "${DENSITIES[@]}"; do
    bucket="${entry% *}"
    size="${entry#* }"
    out_dir="app/src/main/res/mipmap-$bucket"
    mkdir -p "$out_dir"
    rsvg-convert -w "$size" -h "$size" "$SRC_SVG"   -o "$out_dir/ic_launcher.png"
    rsvg-convert -w "$size" -h "$size" "$ROUND_SVG" -o "$out_dir/ic_launcher_round.png"
    echo "rendered $out_dir/ic_launcher{,_round}.png at ${size}×${size}"
done

# Play Store hi-res listing icon. Square 512×512, no transparency.
mkdir -p playstore
rsvg-convert -w 512 -h 512 -b '#0A0A0A' "$SRC_SVG" -o playstore/ic_launcher-512.png
echo "rendered playstore/ic_launcher-512.png at 512×512"
