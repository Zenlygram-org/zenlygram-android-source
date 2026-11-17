#!/usr/bin/env bash
# Regenerates Zenlygram's alternate launcher icons (the iOS Z*.alticon pack) as Android launcher resources.
#
# Usage: Tools/zenlygram/icons/make_alt_icons.sh [path to the ZenlyGram iOS repo, default ~/Loyihalar/zenlygram] [git ref]
# Reads Telegram/Telegram-iOS/Z<Name>.alticon/Z<Name>@3x.png (180px, the largest the pack has) via `git show <ref>:`.
# ZSignature is the default Zenlygram icon (make_icons.sh), so it is not an alternate here.
#
# Writes, per icon (lower-case name n):
#   mipmap-anydpi-v26/zg_icon_n.xml, zg_icon_n_round.xml    adaptive icons (background + foreground + monochrome Z)
#   drawable/zg_icon_n_background.xml                       solid background = the art's border colour
#   mipmap-<d>/zg_icon_n_foreground.png                     108dp foreground, art at 68%
#   mipmap-<d>/zg_icon_n.png, zg_icon_n_round.png            API 23-25 legacy icons
# Hand-maintained files that use these outputs: AndroidManifest.xml aliases, LauncherIconController.LauncherIcon.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
RES="$ROOT/TMessagesProj/src/main/res"
IOS="${1:-$HOME/Loyihalar/zenlygram}"
REF="${2:-origin/release/appstore}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

NAMES="Aero Baltic Circle Clean Corner Dark Duotone Flow Focus Fold Glass Gradient Isometric Minimal Monochrome Neon Outline Premium Wave"
# No xxxhdpi: the iOS art is 180px, xxxhdpi would only upscale it (and double the size); launchers scale xxhdpi.
DENSITIES="mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3"

out() { # out <master> <dp> <scale> <dest.png>
  local px
  px=$(awk -v d="$2" -v s="$3" 'BEGIN { printf "%d", d * s + 0.5 }')
  mkdir -p "$(dirname "$4")"
  sips -z "$px" "$px" "$1" --out "$4" >/dev/null
}

adaptive() { # adaptive <n> <dest.xml>
  cat > "$2" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- ZENLYGRAM: alternate launcher icon from the iOS pack (Tools/zenlygram/icons/make_alt_icons.sh) -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/zg_icon_$1_background" />
    <foreground android:drawable="@mipmap/zg_icon_$1_foreground" />
    <monochrome android:drawable="@drawable/zg_launcher_monochrome" />
</adaptive-icon>
EOF
}

mkdir -p "$RES/mipmap-anydpi-v26" "$RES/drawable"
for N in $NAMES; do
  n=$(echo "$N" | tr '[:upper:]' '[:lower:]')
  git -C "$IOS" show "$REF:Telegram/Telegram-iOS/Z$N.alticon/Z$N@3x.png" > "$WORK/$N.png"
  mkdir -p "$WORK/$N"
  color=$(xcrun swift "$HERE/zg_alt_render.swift" "$WORK/$N.png" "$WORK/$N")
  cat > "$RES/drawable/zg_icon_${n}_background.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- ZENLYGRAM: background layer of the $N alternate launcher icon (border colour of the iOS art) -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FF${color#\#}" />
</shape>
EOF
  adaptive "$n" "$RES/mipmap-anydpi-v26/zg_icon_$n.xml"
  adaptive "$n" "$RES/mipmap-anydpi-v26/zg_icon_${n}_round.xml"
  for entry in $DENSITIES; do
    d=${entry%%:*}; s=${entry##*:}
    out "$WORK/$N/fg.png"           108 "$s" "$RES/mipmap-$d/zg_icon_${n}_foreground.png"
    out "$WORK/$N/legacy.png"        48 "$s" "$RES/mipmap-$d/zg_icon_$n.png"
    out "$WORK/$N/legacy_round.png"  48 "$s" "$RES/mipmap-$d/zg_icon_${n}_round.png"
  done
  echo "$N $color"
done
echo "done"
