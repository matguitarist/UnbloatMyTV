#!/bin/bash
# Builds UnbloatMyTV.app and UnbloatMyTV-mac.zip in mac/build.
#
#   ./build-app.sh                         this Mac's architecture, ad-hoc signed (local testing)
#   ARCHS="arm64 x86_64" ./build-app.sh    universal (needs Xcode)
#
# Release builds (GitHub Actions sets these):
#   SIGN_IDENTITY="Developer ID Application: …"   sign with hardened runtime
#   NOTARY_APPLE_ID=… NOTARY_PASSWORD=… NOTARY_TEAM_ID=…  notarize and staple (Apple ID with an
#                                                         app-specific password; not the App Store)
#   SPARKLE_KEY_FILE=…  [NOTES_FILE=notes.md]      also write build/appcast.xml for Sparkle
#
# The version comes from the Windows project, so all builds always match.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-$(sed -nE 's|.*<Version>(.*)</Version>.*|\1|p' ../src/AndroidTVDebloater/AndroidTVDebloater.csproj | head -1)}"
ARCHS="${ARCHS:-$(uname -m)}"
IDENTITY="${SIGN_IDENTITY:--}"
REPO="matguitarist/UnbloatMyTV"
ARCH_FLAGS=()
for a in $ARCHS; do ARCH_FLAGS+=(--arch "$a"); done

swift build -c release "${ARCH_FLAGS[@]}"
BIN_DIR="$(swift build -c release "${ARCH_FLAGS[@]}" --show-bin-path)"

APP="build/UnbloatMyTV.app"
ZIP="build/UnbloatMyTV-mac.zip"
rm -rf build
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/Frameworks"
cp "$BIN_DIR/AndroidTVDebloater" "$APP/Contents/MacOS/AndroidTVDebloater"
install_name_tool -add_rpath "@executable_path/../Frameworks" "$APP/Contents/MacOS/AndroidTVDebloater"
ditto "$BIN_DIR/Sparkle.framework" "$APP/Contents/Frameworks/Sparkle.framework"
cp -R Resources/en.lproj Resources/fr.lproj Resources/AppIcon.icns "$APP/Contents/Resources/"
cp ../shared/catalog.json "$APP/Contents/Resources/catalog.json"
cp ../helper/umtv-helper.dex "$APP/Contents/Resources/umtv-helper.dex"
sed "s/__VERSION__/$VERSION/g" Info.plist > "$APP/Contents/Info.plist"

# Sign from the inside out (Sparkle's helpers, the framework, then the app).
# Developer ID builds use the hardened runtime and a secure timestamp, as notarization requires.
if [ "$IDENTITY" = "-" ]; then
  SIGN=(codesign --force --sign - --timestamp=none)
else
  SIGN=(codesign --force --sign "$IDENTITY" --options runtime --timestamp)
fi
FW="$APP/Contents/Frameworks/Sparkle.framework/Versions/B"
"${SIGN[@]}" "$FW/XPCServices/Installer.xpc"
"${SIGN[@]}" --preserve-metadata=entitlements "$FW/XPCServices/Downloader.xpc"
"${SIGN[@]}" "$FW/Autoupdate"
"${SIGN[@]}" "$FW/Updater.app"
"${SIGN[@]}" "$APP/Contents/Frameworks/Sparkle.framework"
"${SIGN[@]}" "$APP"
codesign --verify --deep --strict "$APP"

ditto -c -k --keepParent "$APP" "$ZIP"

if [ -n "${NOTARY_APPLE_ID:-}" ]; then
  xcrun notarytool submit "$ZIP" --apple-id "$NOTARY_APPLE_ID" --password "$NOTARY_PASSWORD" \
    --team-id "$NOTARY_TEAM_ID" --wait --timeout 30m
  xcrun stapler staple "$APP"
  rm "$ZIP"
  ditto -c -k --keepParent "$APP" "$ZIP"
  spctl --assess --type execute --verbose "$APP"
fi

if [ -n "${SPARKLE_KEY_FILE:-}" ]; then
  # sign_update prints: sparkle:edSignature="…" length="…"
  SIGNATURE="$(.tools/sparkle/bin/sign_update --ed-key-file "$SPARKLE_KEY_FILE" "$ZIP")"
  VERSION="$VERSION" SIGNATURE="$SIGNATURE" REPO="$REPO" NOTES_FILE="${NOTES_FILE:-}" python3 - <<'PY' > build/appcast.xml
import html, os, email.utils
v, sig, repo = os.environ["VERSION"], os.environ["SIGNATURE"], os.environ["REPO"]
notes = open(os.environ["NOTES_FILE"], encoding="utf-8").read() if os.environ.get("NOTES_FILE") else ""
# Release notes as simple HTML: "- " lines become a list, other lines paragraphs.
parts, items = [], []
for line in notes.splitlines() + [""]:
    line = line.strip()
    if line.startswith(("- ", "* ")):
        items.append(f"<li>{html.escape(line[2:])}</li>")
        continue
    if items:
        parts.append("<ul>" + "".join(items) + "</ul>"); items = []
    if line:
        parts.append(f"<p>{html.escape(line)}</p>")
url = f"https://github.com/{repo}/releases/download/v{v}/UnbloatMyTV-mac.zip"
print(f"""<?xml version="1.0" encoding="utf-8"?>
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>UnbloatMyTV</title>
    <link>https://github.com/{repo}</link>
    <item>
      <title>Version {v}</title>
      <pubDate>{email.utils.formatdate(usegmt=True)}</pubDate>
      <sparkle:version>{v}</sparkle:version>
      <sparkle:shortVersionString>{v}</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>14.0</sparkle:minimumSystemVersion>
      <sparkle:fullReleaseNotesLink>https://github.com/{repo}/releases</sparkle:fullReleaseNotesLink>
      <description><![CDATA[{"".join(parts)}]]></description>
      <enclosure url="{url}" type="application/octet-stream" {sig} />
    </item>
  </channel>
</rss>""")
PY
fi

echo "Built $APP ($VERSION, $ARCHS, signed: $IDENTITY)"
