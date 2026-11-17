#!/usr/bin/env bash
# Regenerates Zenlygram's launcher icons, notification icon, Android 12+ splash icon, account/contact icon,
# Terms of Service logo, intro sphere texture and the chat-list wordmark from the iOS sources.
#
# Usage: Tools/zenlygram/icons/make_icons.sh [path to the ZenlyGram iOS repo, default ~/zenlygram]
# Needs macOS (xcrun swift with CoreGraphics/CoreText, sips) and python3. Output goes to TMessagesProj/src/main/res.
#
# Inputs (iOS repo, working tree or git HEAD):
#   Telegram/Telegram-iOS/Zenlygram.icon/Assets/signature_1024.png                                   (launcher art)
#   Swiftgram/SGSettingsUI/Images.xcassets/SwiftgramContextMenu.imageset/swiftgram_context_menu.pdf  (Z glyph)
#
# Hand-maintained files that use these outputs (not written here):
#   mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml, drawable/zg_launcher_background.xml
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
RES="$ROOT/TMessagesProj/src/main/res"
FONT="$ROOT/TMessagesProj/src/main/assets/fonts/rextrabold.ttf"
IOS="${1:-$HOME/zenlygram}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ios_file() { # ios_file <path in iOS repo> <out>
  if [ -f "$IOS/$1" ]; then cp "$IOS/$1" "$2"; else git -C "$IOS" show "HEAD:$1" > "$2"; fi
}
ios_file Telegram/Telegram-iOS/Zenlygram.icon/Assets/signature_1024.png "$WORK/master.png"
ios_file Swiftgram/SGSettingsUI/Images.xcassets/SwiftgramContextMenu.imageset/swiftgram_context_menu.pdf "$WORK/glyph.pdf"

# High-resolution masters: fg.png, legacy.png, legacy_round.png, disc.png
xcrun swift "$HERE/zg_render.swift" "$WORK/master.png" "$WORK"

DENSITIES="mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4"

out() { # out <master> <dp> <scale> <dest.png>  (also removes a same-named .webp, which would be a duplicate resource)
  local px
  px=$(awk -v d="$2" -v s="$3" 'BEGIN { printf "%d", d * s + 0.5 }')
  mkdir -p "$(dirname "$4")"
  sips -z "$px" "$px" "$1" --out "$4" >/dev/null
  rm -f "${4%.png}.webp"
  echo "  $(basename "$(dirname "$4")")/$(basename "$4") ${px}px"
}

for entry in $DENSITIES; do
  d=${entry%%:*}; s=${entry##*:}
  out "$WORK/fg.png"           108 "$s" "$RES/mipmap-$d/zg_launcher_foreground.png"   # adaptive foreground
  out "$WORK/legacy.png"        48 "$s" "$RES/mipmap-$d/ic_launcher.png"              # API 23-25 square
  out "$WORK/legacy_round.png"  48 "$s" "$RES/mipmap-$d/ic_launcher_round.png"        # API 25 round
  out "$WORK/disc.png"          48 "$s" "$RES/drawable-$d/ic_launcher_dr.png"         # account, contacts, call, person
  out "$WORK/disc.png"          68 "$s" "$RES/drawable-$d/logo_middle.png"            # Terms of Service logo
  if [ "$d" != "xxxhdpi" ]; then                                                      # like Telegram's intro textures
    out "$WORK/disc.png"       150 "$s" "$RES/drawable-$d/zg_intro_logo.png"          # IntroActivity sphere texture
  fi
done

# The notification icon is now a vector (drawable/notification.xml); density bitmaps would shadow it.
rm -f "$RES"/drawable-*dpi/notification.webp "$RES"/drawable-*dpi/notification.png

python3 -I "$HERE/zg_vectors.py" "$WORK/glyph.pdf" "$RES"
xcrun swift "$HERE/zg_wordmark.swift" "$FONT" 15.31 "$RES/drawable/telegram_logo_2.xml"
xcrun swift "$HERE/zg_wordmark.swift" "$FONT" 14.08 "$RES/drawable/telegram_logo.xml"
echo "done"
