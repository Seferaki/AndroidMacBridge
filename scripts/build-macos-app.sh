#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/macos"
SWIFT_ARCH_ARGS=()
SHARE_ARCH_ARGS=()
if [[ "${BRIDGE_MAC_UNIVERSAL:-0}" == "1" ]]; then
    SWIFT_ARCH_ARGS=(--arch arm64 --arch x86_64)
    SHARE_ARCH_ARGS=("ARCHS=arm64 x86_64" ONLY_ACTIVE_ARCH=NO)
fi
swift build --configuration release ${SWIFT_ARCH_ARGS[@]+"${SWIFT_ARCH_ARGS[@]}"} --product BridgeMac
BIN_DIR="$(swift build --configuration release ${SWIFT_ARCH_ARGS[@]+"${SWIFT_ARCH_ARGS[@]}"} --show-bin-path)"
SHARE_DERIVED_DATA="$ROOT/macos/.build/share-extension"
xcodebuild -project "$ROOT/macos/ShareExtension/ShareExtension.xcodeproj" \
    -scheme SamsungMacBridgeShare -configuration Release \
    -derivedDataPath "$SHARE_DERIVED_DATA" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
    ${SHARE_ARCH_ARGS[@]+"${SHARE_ARCH_ARGS[@]}"}
SHARE_EXTENSION="$SHARE_DERIVED_DATA/Build/Products/Release/SamsungMacBridgeShare.appex"
APP="${BRIDGE_MAC_APP_PATH:-$ROOT/dist/AndroidMacBridge.app}"
ICONSET="$ROOT/dist/AppIcon.iconset"
MAC_ICON="$ROOT/assets/app-icon-mac.png"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$APP/Contents/PlugIns"
cp "$BIN_DIR/BridgeMac" "$APP/Contents/MacOS/BridgeMac"
cp "$ROOT/macos/Info.plist" "$APP/Contents/Info.plist"
ditto "$SHARE_EXTENSION" "$APP/Contents/PlugIns/SamsungMacBridgeShare.appex"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
for entry in "16:icon_16x16.png" "32:icon_16x16@2x.png" "32:icon_32x32.png" "64:icon_32x32@2x.png" "128:icon_128x128.png" "256:icon_128x128@2x.png" "256:icon_256x256.png" "512:icon_256x256@2x.png" "512:icon_512x512.png" "1024:icon_512x512@2x.png"; do
    size="${entry%%:*}"
    filename="${entry#*:}"
    sips -s format png -z "$size" "$size" "$MAC_ICON" --out "$ICONSET/$filename" >/dev/null
done
iconutil --convert icns "$ICONSET" --output "$APP/Contents/Resources/SamsungMacBridgeIcon20261102.icns"
rm -rf "$ICONSET"
codesign --force --sign - --entitlements "$ROOT/macos/ShareExtension/ShareExtension.entitlements" \
    --identifier dev.seferaki.SamsungMacBridge.Share "$APP/Contents/PlugIns/SamsungMacBridgeShare.appex"
codesign --force --sign - --entitlements "$ROOT/macos/BridgeMac.entitlements" \
    --identifier dev.seferaki.SamsungMacBridge "$APP"
printf 'Готово: %s\n' "$APP"
printf 'Откройте приложение; когда macOS спросит, разрешите доступ к локальной сети.\n'
printf 'В Finder выберите файл → Поделиться → AndroidMacBridge. Файлы добавятся в очередь автоматически.\n'
